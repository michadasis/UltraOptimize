package me.rimuru.ultraoptimize.paper;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.*;

/**
 * CPU-optimized watchdog monitor
 * Lightweight hang detection with minimal overhead
 */
public class WatchdogMonitor {

    private final UltraOptimize plugin;

    private BukkitTask monitorTask;
    private volatile long lastTickTime;
    private volatile boolean emergencyMode;
    private volatile int hangCount;

    // Watchdog configuration - loaded from config
    private long hangThreshold;
    private long criticalHangThreshold;
    private int emergencyTriggerCount;
    private boolean autoEmergencyOptimization;

    // Paper Watchdog API (optional)
    private Method getTickDurationMethod;
    private boolean paperWatchdogSupported;

    // Minimal hang tracking - keep only last 5
    private final LinkedList<HangReport> recentHangs;
    private final Map<String, Integer> hangCauses;

    // Statistics
    private int totalHangs;
    private long totalHangTime;

    public WatchdogMonitor(UltraOptimize plugin) {
        this.plugin = plugin;
        this.recentHangs = new LinkedList<>();
        this.hangCauses = new HashMap<>();
        this.lastTickTime = System.currentTimeMillis();
        this.emergencyMode = false;
        this.hangCount = 0;

        // Load configuration values
        loadConfiguration();

        initializePaperWatchdog();
    }

    private void loadConfiguration() {
        // Load from config instead of hardcoding
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

    private void initializePaperWatchdog() {
        try {
            Class<?> watchdogClass = Class.forName("com.destroystokyo.paper.Watchdog");
            getTickDurationMethod = watchdogClass.getMethod("getTickDuration");
            paperWatchdogSupported = true;
            Logger.info("Paper Watchdog: ENABLED");
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            paperWatchdogSupported = false;
            Logger.info("Paper Watchdog: DISABLED (using standard detection)");
        }
    }

    public void start() {
        if (!plugin.getConfigManager().isPaperWatchdogEnabled()) {
            Logger.info("Watchdog monitor disabled in config");
            return;
        }

        // Check every 5 seconds instead of every second - much lower CPU usage
        monitorTask = new BukkitRunnable() {
            @Override
            public void run() {
                checkForHang();
            }
        }.runTaskTimer(plugin, 100L, 100L); // Every 5 seconds

        Logger.info("Watchdog monitor started (interval: 5s)");
    }

    public void shutdown() {
        if (monitorTask != null) {
            monitorTask.cancel();
        }

        if (emergencyMode) {
            disableEmergencyMode();
        }

        Logger.info("Watchdog monitor stopped");
    }

    private void checkForHang() {
        long currentTime = System.currentTimeMillis();
        long timeSinceLastTick = currentTime - lastTickTime;

        // Update last tick time
        lastTickTime = currentTime;

        // Get tick duration from Paper if available
        long tickDuration = getTickDuration();

        // Only process if actually hanging
        if (tickDuration > hangThreshold) {
            handleHang(tickDuration);
        } else if (tickDuration < 100 && hangCount > 0) {
            // Gradually decrease hang count when server is healthy
            hangCount = Math.max(0, hangCount - 1);
        }

        // Check for critical hang
        if (tickDuration > criticalHangThreshold) {
            handleCriticalHang(tickDuration);
        }
    }

    private long getTickDuration() {
        if (paperWatchdogSupported && getTickDurationMethod != null) {
            try {
                Object watchdog = Class.forName("com.destroystokyo.paper.Watchdog")
                        .getMethod("getInstance").invoke(null);
                return (long) getTickDurationMethod.invoke(watchdog);
            } catch (Exception ignored) {
                // Fall through to estimate
            }
        }

        // Estimate from TPS
        double tps = plugin.getPerformanceMonitor().getCurrentTPS();
        if (tps < 1) return 20000; // Server is severely lagging
        return (long) (1000.0 / tps);
    }

    private void handleHang(long duration) {
        hangCount++;
        totalHangs++;
        totalHangTime += duration;

        // Only log if notify-hangs is enabled
        if (plugin.getConfigManager().isNotifyHangs()) {
            Logger.warning("Server hang detected: " + duration + "ms (count: " + hangCount + ")");
        }

        // Lightweight hang analysis - no heavy stack trace analysis
        HangReport report = createSimpleReport(duration);

        // Keep only last 5 hangs
        synchronized (recentHangs) {
            recentHangs.add(report);
            if (recentHangs.size() > 5) {
                recentHangs.removeFirst();
            }
        }

        // Track cause
        if (report.suspectedCause != null) {
            hangCauses.merge(report.suspectedCause, 1, Integer::sum);
        }

        // Notify admins if configured
        if (plugin.getConfigManager().isNotifyHangs()) {
            notifyHang(duration);
        }

        // Trigger emergency mode if threshold reached
        if (hangCount >= emergencyTriggerCount && !emergencyMode) {
            enableEmergencyMode();
        }
    }

    private void handleCriticalHang(long duration) {
        Logger.severe("CRITICAL HANG: " + duration + "ms - Emergency optimization!");

        if (!emergencyMode) {
            enableEmergencyMode();
        }

        if (autoEmergencyOptimization) {
            performEmergencyOptimization();
        }
    }

    /**
     * Lightweight hang report - no heavy stack analysis
     */
    private HangReport createSimpleReport(long duration) {
        HangReport report = new HangReport();
        report.timestamp = System.currentTimeMillis();
        report.duration = duration;
        report.tps = plugin.getPerformanceMonitor().getCurrentTPS();
        report.entityCount = getEntityCount();
        report.chunkCount = getChunkCount();
        report.memoryUsage = getMemoryUsage();

        // Simple cause detection based on metrics
        report.suspectedCause = detectCauseFromMetrics(report);

        return report;
    }

    /**
     * Lightweight cause detection without stack traces
     */
    private String detectCauseFromMetrics(HangReport report) {
        // High entity count
        if (report.entityCount > 5000) {
            return "HIGH_ENTITY_COUNT";
        }

        // High chunk count
        if (report.chunkCount > 3000) {
            return "HIGH_CHUNK_COUNT";
        }

        // High memory
        if (report.memoryUsage > 90) {
            return "HIGH_MEMORY_USAGE";
        }

        // Low TPS with normal metrics = likely tick issue
        if (report.tps < 10) {
            return "TICK_PROCESSING";
        }

        return "UNKNOWN";
    }

    private void notifyHang(long duration) {
        String message = "§e[Watchdog] §7Server hang detected: " + duration + "ms";

        Bukkit.getOnlinePlayers().forEach(player -> {
            if (player.hasPermission("ultraoptimize.notify")) {
                player.sendMessage(message);
            }
        });
    }

    private void enableEmergencyMode() {
        emergencyMode = true;
        Logger.severe("╔═══════════════════════════╗");
        Logger.severe("║  EMERGENCY MODE ACTIVATED  ║");
        Logger.severe("╚═══════════════════════════╝");

        // Check config before notifying
        if (plugin.getConfigManager().isNotifyEmergency()) {
            Bukkit.getOnlinePlayers().forEach(player -> {
                if (player.hasPermission("ultraoptimize.notify")) {
                    player.sendMessage("§c§l[!] EMERGENCY MODE - Server lag detected!");
                }
            });
        }

        if (autoEmergencyOptimization) {
            performEmergencyOptimization();
        }
    }

    private void disableEmergencyMode() {
        emergencyMode = false;
        Logger.info("Emergency mode deactivated");

        if (plugin.getConfigManager().isNotifyEmergency()) {
            Bukkit.getOnlinePlayers().forEach(player -> {
                if (player.hasPermission("ultraoptimize.notify")) {
                    player.sendMessage("§a[✓] Emergency mode deactivated");
                }
            });
        }
    }

    /**
     * Lightweight emergency optimization
     */
    private void performEmergencyOptimization() {
        Logger.warning("Emergency optimization starting...");

        // Run async to avoid blocking
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                int removed = 0;

                // Clear non-essential entities
                for (World world : Bukkit.getWorlds()) {
                    removed += plugin.getEntityManager().optimizeWorld(world);
                }

                Logger.warning("Emergency: Removed " + removed + " entities");

                // Sync operations on main thread
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        // Unload chunks
                        int unloaded = plugin.getChunkManager().unloadEmptyChunks();
                        Logger.warning("Emergency: Unloaded " + unloaded + " chunks");

                        // GC
                        System.gc();
                        Logger.warning("Emergency: Forced GC");

                    } catch (Exception e) {
                        Logger.severe("Emergency optimization failed: " + e.getMessage());
                    }
                });

                // Reset hang count
                hangCount = 0;

            } catch (Exception e) {
                Logger.severe("Emergency async failed: " + e.getMessage());
            }
        });
    }

    // Lightweight metric getters

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
        stats.paperWatchdogSupported = paperWatchdogSupported;

        synchronized (recentHangs) {
            stats.recentHangs = new ArrayList<>(recentHangs);
        }

        stats.hangCauses = new HashMap<>(hangCauses);

        if (totalHangs > 0) {
            stats.averageHangDuration = totalHangTime / totalHangs;
        }

        return stats;
    }

    // Configuration setters for runtime changes
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
        Logger.info("Hang counter reset");
    }

    // Minimal inner classes

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
        public boolean paperWatchdogSupported;
    }
}