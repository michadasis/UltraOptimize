package me.rimuru.ultraoptimize.managers;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.config.ConfigManager;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class PerformanceMonitor {

    // adjustViewDistance() changes a world's loaded radius, which forces a
    // burst of chunk loads/unloads around every player. tick() runs every
    // single tick (20x/sec); without a cooldown, a server whose TPS hovers
    // right around the adjustment thresholds would flap the view distance up
    // and down every tick, turning "help a struggling server" into a
    // constant chunk load/unload churn that makes it worse.
    private static final long VIEW_DISTANCE_ADJUST_COOLDOWN_MILLIS = 3000L;

    private final UltraOptimize plugin;
    private final ConfigManager config;

    private final long[] tickTimes;
    private int tickIndex;
    private long lastTick;
    private final List<Double> tpsHistory;
    private long lastViewDistanceAdjustTime;

    private BukkitTask monitorTask;

    // Reflection cache for view distance methods (1.14+ only)
    private Method getViewDistanceMethod;
    private Method setViewDistanceMethod;
    private boolean viewDistanceSupported;

    public PerformanceMonitor(UltraOptimize plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.tickTimes = new long[20];
        this.tickIndex = 0;
        this.lastTick = System.currentTimeMillis();
        this.tpsHistory = new ArrayList<>();

        // Initialize reflection methods for view distance
        initializeViewDistanceMethods();
    }

    private void initializeViewDistanceMethods() {
        try {
            // Try to get the view distance methods (available in 1.14+)
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
                    tick();
                } catch (Exception e) {
                    Logger.severe("Error in performance monitor: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);

        Logger.info("PerformanceMonitor started successfully");
    }

    public void shutdown() {
        if (monitorTask != null) {
            monitorTask.cancel();
        }
        Logger.info("PerformanceMonitor shut down");
    }

    private void tick() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastTick;
        lastTick = now;

        tickTimes[tickIndex++ % 20] = elapsed;

        double tps = calculateRealTimeTPS();
        tpsHistory.add(tps);

        // Keep only last 60 TPS readings (1 minute at 1 tick per second)
        if (tpsHistory.size() > 60) {
            tpsHistory.remove(0);
        }

        // Auto view distance adjustment (master switch + sub-toggle, only if supported)
        if (config.isOptimizeViewDistance() && config.isAutoViewDistance() && viewDistanceSupported
                && now - lastViewDistanceAdjustTime >= VIEW_DISTANCE_ADJUST_COOLDOWN_MILLIS) {
            lastViewDistanceAdjustTime = now;
            adjustViewDistance(tps);
        }
    }

    private double calculateRealTimeTPS() {
        long totalTime = 0;
        int validTicks = 0;

        for (long time : tickTimes) {
            if (time > 0) {
                totalTime += time;
                validTicks++;
            }
        }

        if (validTicks == 0 || totalTime == 0) {
            return 20.0;
        }

        double avgTickTime = (double) totalTime / validTicks;
        double tps = 1000.0 / avgTickTime;

        return Math.min(tps, 20.0);
    }

    public double getCurrentTPS() {
        return calculateRealTimeTPS();
    }

    public double getAverageTPS() {
        if (tpsHistory.isEmpty()) {
            return 20.0;
        }

        return tpsHistory.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(20.0);
    }

    public double getMinTPS() {
        if (tpsHistory.isEmpty()) {
            return 20.0;
        }

        return tpsHistory.stream()
                .mapToDouble(Double::doubleValue)
                .min()
                .orElse(20.0);
    }

    public double getMaxTPS() {
        if (tpsHistory.isEmpty()) {
            return 20.0;
        }

        return tpsHistory.stream()
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(20.0);
    }

    private void adjustViewDistance(double tps) {
        if (!viewDistanceSupported) {
            return;
        }

        int minView = config.getMinViewDistance();
        int maxView = config.getMaxViewDistance();

        try {
            for (World world : Bukkit.getWorlds()) {
                int currentView = getWorldViewDistance(world);
                int newView = currentView;

                if (tps < 15 && currentView > minView) {
                    newView = Math.max(minView, currentView - 1);
                } else if (tps > 19 && currentView < maxView) {
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
            Object result = getViewDistanceMethod.invoke(world);
            return (Integer) result;
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
            // Try spigot.yml first (most reliable across versions)
            return Bukkit.getServer().getViewDistance();
        } catch (Exception e) {
            // Fallback to default
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
        info.usagePercent = (info.usedMemory * 100.0) / info.maxMemory;

        return info;
    }

    public boolean isMemoryCritical() {
        MemoryInfo info = getMemoryInfo();
        return info.usagePercent > 90;
    }

    public boolean isMemoryHigh() {
        MemoryInfo info = getMemoryInfo();
        return info.usagePercent > 75;
    }

    public void performGarbageCollection() {
        Logger.info("Running garbage collection...");

        long before = Runtime.getRuntime().freeMemory();
        System.gc();
        long after = Runtime.getRuntime().freeMemory();

        long freed = (after - before) / 1024 / 1024;
        Logger.info("Freed " + freed + "MB of memory");
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

        // Performance status
        if (report.currentTPS < 15) {
            report.status = PerformanceStatus.CRITICAL;
        } else if (report.currentTPS < 18) {
            report.status = PerformanceStatus.WARNING;
        } else {
            report.status = PerformanceStatus.GOOD;
        }

        // Memory status
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

    // Inner classes
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