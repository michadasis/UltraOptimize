package me.rimuru.ultraoptimize.managers;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.config.ConfigManager;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;

public class PerformanceMonitor {

    // The sampler used to run every tick (20 times a second) to maintain a
    // 60-entry history that the comments described as "1 minute" but which
    // actually covered three seconds. Sampling once a second makes the history
    // mean what it says and removes 19 out of every 20 scheduler wakeups,
    // boxed Doubles, and O(n) ArrayList.remove(0) shifts.
    private static final long SAMPLE_INTERVAL_TICKS = 20L;
    private static final int HISTORY_SIZE = 60; // 60 samples = 1 minute

    // Changing a world's view distance forces a burst of chunk loads and
    // unloads around every player. With a short cooldown and adjacent
    // thresholds, a server hovering near the trigger point flaps up and down
    // indefinitely, turning "help a struggling server" into constant chunk
    // churn. Widened deadband plus a much longer cooldown.
    private static final long VIEW_DISTANCE_ADJUST_COOLDOWN_MILLIS = 30_000L;
    private static final double VIEW_DISTANCE_LOWER_TPS = 15.0;
    private static final double VIEW_DISTANCE_RAISE_TPS = 19.5;

    private static final long FORCED_GC_COOLDOWN_MILLIS = 5 * 60 * 1000L;

    private final UltraOptimize plugin;
    private final ConfigManager config;

    // Fixed-size ring buffers of primitives. The old code appended a boxed
    // Double to an ArrayList and called remove(0) - an O(n) shift - twenty
    // times a second, and indexed tickTimes with an int counter that would
    // wrap negative after roughly three and a half years of uptime and start
    // throwing ArrayIndexOutOfBoundsException.
    private final double[] tpsHistory = new double[HISTORY_SIZE];
    private int historyIndex;
    private int historyCount;

    private volatile double currentTPS = 20.0;
    private long lastSampleNanos;
    private long lastViewDistanceAdjustTime;
    private long lastForcedGCTime;

    private BukkitTask monitorTask;

    // Reflection cache
    private Method serverGetTpsMethod;
    private Method getViewDistanceMethod;
    private Method setViewDistanceMethod;
    private boolean viewDistanceSupported;

    public PerformanceMonitor(UltraOptimize plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.lastSampleNanos = System.nanoTime();

        initializeServerTps();
        initializeViewDistanceMethods();
    }

    /**
     * Spigot and Paper both expose Server#getTPS(), which reports the server's
     * own rolling averages. Where it exists it is strictly better than timing
     * our own scheduled task, which measures scheduler latency as much as tick
     * rate.
     */
    private void initializeServerTps() {
        try {
            serverGetTpsMethod = Bukkit.getServer().getClass().getMethod("getTPS");
            Logger.info("Using the server's own TPS reporting");
        } catch (NoSuchMethodException e) {
            serverGetTpsMethod = null;
            Logger.info("Server#getTPS() unavailable - sampling tick times directly");
        }
    }

    private void initializeViewDistanceMethods() {
        try {
            getViewDistanceMethod = World.class.getMethod("getViewDistance");
            setViewDistanceMethod = World.class.getMethod("setViewDistance", int.class);
            viewDistanceSupported = true;
            Logger.info("View distance management is supported on this server version");
        } catch (NoSuchMethodException e) {
            viewDistanceSupported = false;
            Logger.info("View distance management is not supported (World#setViewDistance requires " +
                    "Paper or a Paper-based fork; it is not part of the Spigot API)");
        }
    }

    public void start() {
        monitorTask = new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    sample();
                } catch (Exception e) {
                    Logger.severe("Error in performance monitor: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }.runTaskTimer(plugin, SAMPLE_INTERVAL_TICKS, SAMPLE_INTERVAL_TICKS);

        Logger.info("PerformanceMonitor started successfully");
    }

    public void shutdown() {
        if (monitorTask != null) {
            monitorTask.cancel();
            monitorTask = null;
        }
        Logger.info("PerformanceMonitor shut down");
    }

    private void sample() {
        long now = System.nanoTime();
        double tps = measureTPS(now);
        lastSampleNanos = now;

        currentTPS = tps;

        tpsHistory[historyIndex] = tps;
        historyIndex = (historyIndex + 1) % HISTORY_SIZE;
        if (historyCount < HISTORY_SIZE) historyCount++;

        long nowMillis = System.currentTimeMillis();

        if (config.isOptimizeViewDistance() && config.isAutoViewDistance() && viewDistanceSupported
                && nowMillis - lastViewDistanceAdjustTime >= VIEW_DISTANCE_ADJUST_COOLDOWN_MILLIS) {
            lastViewDistanceAdjustTime = nowMillis;
            adjustViewDistance(tps);
        }
    }

    private double measureTPS(long nowNanos) {
        if (serverGetTpsMethod != null) {
            try {
                double[] tps = (double[]) serverGetTpsMethod.invoke(Bukkit.getServer());
                if (tps != null && tps.length > 0 && tps[0] > 0) {
                    return Math.min(tps[0], 20.0);
                }
            } catch (Exception ignored) {
                // fall through to direct measurement
            }
        }

        // SAMPLE_INTERVAL_TICKS ticks should take exactly
        // SAMPLE_INTERVAL_TICKS * 50ms; the ratio gives the achieved rate.
        long elapsedNanos = nowNanos - lastSampleNanos;
        if (elapsedNanos <= 0) return 20.0;

        double elapsedMillis = elapsedNanos / 1_000_000.0;
        double tps = (SAMPLE_INTERVAL_TICKS * 1000.0) / elapsedMillis;

        return Math.min(tps, 20.0);
    }

    public double getCurrentTPS() {
        return currentTPS;
    }

    public double getAverageTPS() {
        if (historyCount == 0) return 20.0;

        double total = 0;
        for (int i = 0; i < historyCount; i++) {
            total += tpsHistory[i];
        }
        return total / historyCount;
    }

    public double getMinTPS() {
        if (historyCount == 0) return 20.0;

        double min = Double.MAX_VALUE;
        for (int i = 0; i < historyCount; i++) {
            if (tpsHistory[i] < min) min = tpsHistory[i];
        }
        return min;
    }

    public double getMaxTPS() {
        if (historyCount == 0) return 20.0;

        double max = 0;
        for (int i = 0; i < historyCount; i++) {
            if (tpsHistory[i] > max) max = tpsHistory[i];
        }
        return max;
    }

    private void adjustViewDistance(double tps) {
        if (!viewDistanceSupported) return;

        int minView = config.getMinViewDistance();
        int maxView = config.getMaxViewDistance();

        try {
            for (World world : Bukkit.getWorlds()) {
                int currentView = getWorldViewDistance(world);
                int newView = currentView;

                if (tps < VIEW_DISTANCE_LOWER_TPS && currentView > minView) {
                    newView = Math.max(minView, currentView - 1);
                } else if (tps > VIEW_DISTANCE_RAISE_TPS && currentView < maxView) {
                    newView = Math.min(maxView, currentView + 1);
                }

                if (newView != currentView) {
                    setWorldViewDistance(world, newView);
                    Logger.info("Adjusted view distance for " + world.getName() +
                            " from " + currentView + " to " + newView +
                            " (TPS: " + String.format("%.2f", tps) + ")");
                }
            }
        } catch (Exception e) {
            Logger.warning("Error adjusting view distance: " + e.getMessage());
        }
    }

    public int getWorldViewDistance(World world) {
        if (!viewDistanceSupported) {
            return getServerViewDistance();
        }

        try {
            return (Integer) getViewDistanceMethod.invoke(world);
        } catch (Exception e) {
            return getServerViewDistance();
        }
    }

    public void setWorldViewDistance(World world, int distance) {
        if (!viewDistanceSupported) {
            Logger.warning("Cannot set view distance - World#setViewDistance is only exposed by " +
                    "Paper's Bukkit API (or Paper-based forks); it does not exist on plain Spigot");
            return;
        }

        try {
            setViewDistanceMethod.invoke(world, distance);
        } catch (Exception e) {
            Logger.warning("Unable to set view distance for world " + world.getName() +
                    ": " + e.getMessage());
        }
    }

    private int getServerViewDistance() {
        try {
            return Bukkit.getServer().getViewDistance();
        } catch (Exception e) {
            return 10;
        }
    }

    public MemoryInfo getMemoryInfo() {
        Runtime runtime = Runtime.getRuntime();

        MemoryInfo info = new MemoryInfo();
        info.usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
        info.totalMemory = runtime.totalMemory() / 1024 / 1024;
        info.maxMemory = runtime.maxMemory() / 1024 / 1024;
        info.freeMemory = runtime.freeMemory() / 1024 / 1024;
        info.usagePercent = info.maxMemory > 0 ? (info.usedMemory * 100.0) / info.maxMemory : 0;

        return info;
    }

    public boolean isMemoryCritical() {
        return getMemoryInfo().usagePercent > 90;
    }

    public boolean isMemoryHigh() {
        return getMemoryInfo().usagePercent > 75;
    }

    public long getMillisUntilGarbageCollectionAllowed() {
        long elapsed = System.currentTimeMillis() - lastForcedGCTime;
        return Math.max(0, FORCED_GC_COOLDOWN_MILLIS - elapsed);
    }

    /**
     * Forces a full stop-the-world collection.
     *
     * <p>On a 1GB heap this is a visible multi-second freeze, so it is rate
     * limited unless explicitly forced. Every caller should prefer
     * {@link #requestGarbageCollection(boolean)} over calling this blindly -
     * the manual /uo gc command used to bypass the guards that
     * OptimizationManager correctly applies.
     *
     * @return megabytes reclaimed, or -1 if the request was declined
     */
    public long requestGarbageCollection(boolean force) {
        if (!force && getMillisUntilGarbageCollectionAllowed() > 0) {
            return -1;
        }

        Logger.info("Running garbage collection...");
        lastForcedGCTime = System.currentTimeMillis();

        Runtime runtime = Runtime.getRuntime();
        long usedBefore = runtime.totalMemory() - runtime.freeMemory();
        System.gc();
        long usedAfter = runtime.totalMemory() - runtime.freeMemory();

        // Measured on used memory, not free memory: the old calculation broke
        // whenever the collector also handed pages back and shrank the heap.
        long freed = (usedBefore - usedAfter) / 1024 / 1024;
        Logger.info("Reclaimed " + Math.max(0, freed) + "MB of memory");

        return Math.max(0, freed);
    }

    public PerformanceReport generateReport() {
        PerformanceReport report = new PerformanceReport();

        report.currentTPS = getCurrentTPS();
        report.averageTPS = getAverageTPS();
        report.minTPS = getMinTPS();
        report.maxTPS = getMaxTPS();
        report.memoryInfo = getMemoryInfo();

        report.totalEntities = plugin.getEntityManager().getTotalEntities();
        report.totalChunks = getTotalChunks();
        report.entitiesPerChunk = report.totalChunks > 0 ?
                (double) report.totalEntities / report.totalChunks : 0;

        if (report.currentTPS < 15) {
            report.status = PerformanceStatus.CRITICAL;
        } else if (report.currentTPS < 18) {
            report.status = PerformanceStatus.WARNING;
        } else {
            report.status = PerformanceStatus.GOOD;
        }

        if (report.memoryInfo.usagePercent > 90) {
            report.memoryStatus = PerformanceStatus.CRITICAL;
        } else if (report.memoryInfo.usagePercent > 75) {
            report.memoryStatus = PerformanceStatus.WARNING;
        } else {
            report.memoryStatus = PerformanceStatus.GOOD;
        }

        return report;
    }

    private int getTotalChunks() {
        int total = 0;
        for (World world : Bukkit.getWorlds()) {
            total += world.getLoadedChunks().length;
        }
        return total;
    }

    public boolean isViewDistanceSupported() {
        return viewDistanceSupported;
    }

    public static class MemoryInfo {
        public long usedMemory;
        public long totalMemory;
        public long maxMemory;
        public long freeMemory;
        public double usagePercent;
    }

    public static class PerformanceReport {
        public double currentTPS;
        public double averageTPS;
        public double minTPS;
        public double maxTPS;
        public MemoryInfo memoryInfo;
        public int totalEntities;
        public int totalChunks;
        public double entitiesPerChunk;
        public PerformanceStatus status;
        public PerformanceStatus memoryStatus;
    }

    public enum PerformanceStatus {
        GOOD, WARNING, CRITICAL
    }
}
