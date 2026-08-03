package me.rimuru.ultraoptimize.paper;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main-thread hang detection.
 *
 * <p>The previous implementation could not work by construction. It scheduled
 * itself with {@code runTaskTimer(plugin, 100L, 100L)} - 100 <i>ticks</i>, not
 * five seconds - and then treated the wall-clock gap between its own runs as
 * the hang duration. On a server at 5 TPS those 100 ticks take twenty seconds,
 * so a server that was merely <i>slow</i> reported a fresh twenty-second "hang"
 * on every cycle, tripped emergency mode, and got a full world entity scan plus
 * a forced System.gc() on top of the load it was already failing to keep up
 * with - every five seconds, with no cooldown, until it died. It also ran on
 * the very thread it was trying to observe, so it could never actually see a
 * hang while one was happening.
 *
 * <p>This version keeps a 1-tick heartbeat on the main thread (a single
 * volatile store) and watches it from a plain daemon thread. Bukkit's async
 * scheduler is not usable here: async tasks are dispatched from the main tick
 * loop, so a hung main thread stalls those too.
 */
public class WatchdogMonitor {

    private static final long HEARTBEAT_INTERVAL_TICKS = 1L;
    private static final long CHECK_INTERVAL_MILLIS = 1000L;

    // A struggling server must not be handed a full world scan every few
    // seconds. One emergency pass, then leave it alone long enough to recover.
    private static final long EMERGENCY_COOLDOWN_MILLIS = 5 * 60 * 1000L;

    // Consecutive healthy checks before emergency mode stands down.
    private static final int RECOVERY_CHECKS = 30;

    private final UltraOptimize plugin;

    private volatile long lastHeartbeatNanos;
    private volatile boolean emergencyMode;
    private volatile int hangCount;
    private volatile boolean running;

    private long hangThreshold;
    private long criticalHangThreshold;
    private int emergencyTriggerCount;
    private boolean autoEmergencyOptimization;

    private BukkitTask heartbeatTask;
    private Thread watcherThread;

    private final LinkedList<HangReport> recentHangs;
    private final Map<String, Integer> hangCauses;

    private volatile int totalHangs;
    private volatile long totalHangTime;
    private volatile long lastEmergencyTime;
    private int healthyChecks;

    public WatchdogMonitor(UltraOptimize plugin) {
        this.plugin = plugin;
        this.recentHangs = new LinkedList<>();
        this.hangCauses = new ConcurrentHashMap<>();
        this.lastHeartbeatNanos = System.nanoTime();
        this.emergencyMode = false;
        this.hangCount = 0;

        loadConfiguration();
    }

    private void loadConfiguration() {
        this.hangThreshold = plugin.getConfigManager().getPaperHangThreshold();
        this.criticalHangThreshold = plugin.getConfigManager().getPaperCriticalThreshold();
        this.emergencyTriggerCount = plugin.getConfigManager().getPaperEmergencyTriggerCount();
        this.autoEmergencyOptimization = plugin.getConfigManager().isPaperAutoEmergencyOptimization();

        Logger.info("Watchdog configuration loaded:");
        Logger.info("  Hang threshold: " + hangThreshold + "ms");
        Logger.info("  Critical threshold: " + criticalHangThreshold + "ms");
        Logger.info("  Emergency trigger count: " + emergencyTriggerCount);
        Logger.info("  Auto emergency optimization: " + (autoEmergencyOptimization ? "ENABLED" : "DISABLED"));
    }

    public void start() {
        if (!plugin.getConfigManager().isPaperWatchdogEnabled()) {
            Logger.info("Watchdog monitor disabled in config");
            return;
        }

        lastHeartbeatNanos = System.nanoTime();
        running = true;

        heartbeatTask = new BukkitRunnable() {
            @Override
            public void run() {
                lastHeartbeatNanos = System.nanoTime();
            }
        }.runTaskTimer(plugin, HEARTBEAT_INTERVAL_TICKS, HEARTBEAT_INTERVAL_TICKS);

        watcherThread = new Thread(this::watchLoop, "UltraOptimize-Watchdog");
        watcherThread.setDaemon(true);
        watcherThread.start();

        Logger.info("Watchdog monitor started (heartbeat thread, 1s resolution)");
    }

    public void shutdown() {
        running = false;

        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }

        if (watcherThread != null) {
            watcherThread.interrupt();
            watcherThread = null;
        }

        if (emergencyMode) {
            emergencyMode = false;
            Logger.info("Emergency mode deactivated");
        }

        Logger.info("Watchdog monitor stopped");
    }

    private void watchLoop() {
        while (running) {
            try {
                Thread.sleep(CHECK_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            if (!running) return;

            try {
                checkForHang();
            } catch (Exception e) {
                Logger.warning("Watchdog check failed: " + e.getMessage());
            }
        }
    }

    private void checkForHang() {
        long stalledMillis = (System.nanoTime() - lastHeartbeatNanos) / 1_000_000L;

        // This is the time since the main thread last completed a tick. A
        // healthy server sits at ~50ms; even a server at 5 TPS only reaches
        // ~200ms. Anything near the threshold really is a stall, not slowness.
        if (stalledMillis <= hangThreshold) {
            if (hangCount > 0 && stalledMillis < 1000) {
                hangCount--;
            }

            if (emergencyMode && stalledMillis < 1000) {
                if (++healthyChecks >= RECOVERY_CHECKS) {
                    disableEmergencyMode();
                }
            }
            return;
        }

        healthyChecks = 0;
        handleHang(stalledMillis, stalledMillis > criticalHangThreshold);
    }

    private void handleHang(long duration, boolean critical) {
        hangCount++;
        totalHangs++;
        totalHangTime += duration;

        if (critical) {
            Logger.severe("CRITICAL: main thread stalled for " + duration + "ms");
        } else if (plugin.getConfigManager().isNotifyHangs()) {
            Logger.warning("Server hang detected: " + duration + "ms (count: " + hangCount + ")");
        }

        boolean shouldTriggerEmergency =
                (critical || hangCount >= emergencyTriggerCount) && !emergencyMode;

        // Everything below touches the Bukkit API, which is main-thread only,
        // so it is handed to the scheduler. If the main thread is still hung
        // the task simply waits, which is the correct behaviour.
        Bukkit.getScheduler().runTask(plugin, () -> {
            HangReport report = createSimpleReport(duration);

            synchronized (recentHangs) {
                recentHangs.add(report);
                if (recentHangs.size() > 5) {
                    recentHangs.removeFirst();
                }
            }

            if (report.suspectedCause != null) {
                hangCauses.merge(report.suspectedCause, 1, Integer::sum);
            }

            if (plugin.getConfigManager().isNotifyHangs()) {
                notifyHang(duration);
            }

            if (shouldTriggerEmergency) {
                enableEmergencyMode();
            }
        });
    }

    private HangReport createSimpleReport(long duration) {
        HangReport report = new HangReport();
        report.timestamp = System.currentTimeMillis();
        report.duration = duration;
        report.tps = plugin.getPerformanceMonitor().getCurrentTPS();
        report.entityCount = getEntityCount();
        report.chunkCount = getChunkCount();
        report.memoryUsage = getMemoryUsage();
        report.suspectedCause = detectCauseFromMetrics(report);
        return report;
    }

    private String detectCauseFromMetrics(HangReport report) {
        if (report.entityCount > 5000) return "HIGH_ENTITY_COUNT";
        if (report.chunkCount > 3000) return "HIGH_CHUNK_COUNT";
        if (report.memoryUsage > 90) return "HIGH_MEMORY_USAGE";
        if (report.tps < 10) return "TICK_PROCESSING";
        return "UNKNOWN";
    }

    private void notifyHang(long duration) {
        String message = "§e[Watchdog] §7Server hang detected: " + duration + "ms";

        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("ultraoptimize.notify")) {
                player.sendMessage(message);
            }
        }
    }

    private void enableEmergencyMode() {
        emergencyMode = true;
        healthyChecks = 0;

        Logger.severe("EMERGENCY MODE ACTIVATED - main thread stalls detected");

        if (plugin.getConfigManager().isNotifyEmergency()) {
            for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("ultraoptimize.notify")) {
                    player.sendMessage("§c§l[!] EMERGENCY MODE - Server lag detected!");
                }
            }
        }

        if (autoEmergencyOptimization) {
            performEmergencyOptimization();
        }
    }

    private void disableEmergencyMode() {
        emergencyMode = false;
        healthyChecks = 0;
        hangCount = 0;

        Logger.info("Emergency mode deactivated - server has recovered");

        if (plugin.getConfigManager().isNotifyEmergency()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                    if (player.hasPermission("ultraoptimize.notify")) {
                        player.sendMessage("§a[+] Emergency mode deactivated");
                    }
                }
            });
        }
    }

    /**
     * One cleanup pass, rate limited, on the main thread.
     *
     * <p>Deliberately does not call System.gc(). Forcing a full stop-the-world
     * collection on a heap that is already struggling adds a multi-second
     * freeze to the exact problem it claims to solve, and the old code did it
     * on every emergency trigger with no cooldown at all.
     */
    private void performEmergencyOptimization() {
        long now = System.currentTimeMillis();
        if (now - lastEmergencyTime < EMERGENCY_COOLDOWN_MILLIS) {
            Logger.info("Emergency optimization skipped - ran " +
                    ((now - lastEmergencyTime) / 1000) + "s ago");
            return;
        }
        lastEmergencyTime = now;

        Logger.warning("Emergency optimization starting...");

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                int removed = 0;
                for (World world : Bukkit.getWorlds()) {
                    removed += plugin.getEntityManager().optimizeWorld(world);
                }
                Logger.warning("Emergency: removed " + removed + " entities");

                int unloaded = plugin.getChunkManager().unloadEmptyChunks();
                Logger.warning("Emergency: unloaded " + unloaded + " chunks");

            } catch (Exception e) {
                Logger.severe("Emergency optimization failed: " + e.getMessage());
            }
        });
    }

    private int getEntityCount() {
        int total = 0;
        for (World world : Bukkit.getWorlds()) {
            total += world.getEntities().size();
        }
        return total;
    }

    private int getChunkCount() {
        int total = 0;
        for (World world : Bukkit.getWorlds()) {
            total += world.getLoadedChunks().length;
        }
        return total;
    }

    private double getMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long max = runtime.maxMemory();
        return (used * 100.0) / max;
    }

    public WatchdogStats getStatistics() {
        WatchdogStats stats = new WatchdogStats();
        stats.totalHangs = totalHangs;
        stats.totalHangTime = totalHangTime;
        stats.currentHangCount = hangCount;
        stats.emergencyMode = emergencyMode;

        synchronized (recentHangs) {
            stats.recentHangs = new ArrayList<>(recentHangs);
        }

        stats.hangCauses = new HashMap<>(hangCauses);

        if (totalHangs > 0) {
            stats.averageHangDuration = totalHangTime / totalHangs;
        }

        return stats;
    }

    public void setHangThreshold(long milliseconds) {
        this.hangThreshold = milliseconds;
        Logger.info("Hang threshold updated to " + milliseconds + "ms");
    }

    public void setCriticalHangThreshold(long milliseconds) {
        this.criticalHangThreshold = milliseconds;
        Logger.info("Critical hang threshold updated to " + milliseconds + "ms");
    }

    public void setEmergencyTriggerCount(int count) {
        this.emergencyTriggerCount = count;
        Logger.info("Emergency trigger count updated to " + count);
    }

    public boolean isEmergencyMode() {
        return emergencyMode;
    }

    public void resetHangCount() {
        hangCount = 0;
        healthyChecks = 0;
        Logger.info("Hang counter reset");
    }

    public static class HangReport {
        public long timestamp;
        public long duration;
        public double tps;
        public int entityCount;
        public int chunkCount;
        public double memoryUsage;
        public String suspectedCause;

        @Override
        public String toString() {
            return String.format("Hang[%dms, TPS: %.1f, Entities: %d, Cause: %s]",
                    duration, tps, entityCount, suspectedCause != null ? suspectedCause : "UNKNOWN");
        }
    }

    public static class WatchdogStats {
        public int totalHangs;
        public long totalHangTime;
        public long averageHangDuration;
        public int currentHangCount;
        public boolean emergencyMode;
        public List<HangReport> recentHangs;
        public Map<String, Integer> hangCauses;
    }
}
