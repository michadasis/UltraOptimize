package me.rimuru.ultraoptimize.managers;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.config.ConfigManager;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

public class OptimizationManager {

    // System.gc() forces a full stop-the-world collection. Gating it on
    // isMemoryHigh() (>75% heap used) meant it fired on almost every
    // auto-optimize cycle on a lot of real servers, since G1 routinely lets
    // used heap sit in that range between collections as normal steady-state
    // behavior - so instead of relieving pressure, it was adding a full GC
    // pause on top of whatever TPS drop triggered auto-optimize in the first
    // place. Require genuine critical usage (isMemoryCritical(), >90%) and
    // space forced collections out so a sustained critical state can't
    // trigger back-to-back full GCs every auto-optimize interval either.
    private static final long MIN_FORCED_GC_INTERVAL_MILLIS = 5 * 60 * 1000L; // 5 minutes

    private final UltraOptimize plugin;
    private final ConfigManager config;

    private BukkitTask autoOptimizeTask;
    private long lastOptimizationTime;
    private long lastForcedGCTime;

    public OptimizationManager(UltraOptimize plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.lastOptimizationTime = 0;
    }

    public void start() {
        if (config.isAutoOptimizeEnabled()) {
            startAutoOptimization();
        }
        Logger.info("OptimizationManager started successfully");
    }

    public void shutdown() {
        if (autoOptimizeTask != null) {
            autoOptimizeTask.cancel();
        }
        Logger.info("OptimizationManager shut down");
    }

    public void restart() {
        shutdown();
        start();
    }

    private void startAutoOptimization() {
        int interval = config.getAutoOptimizeInterval();

        autoOptimizeTask = new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    double tps = plugin.getPerformanceMonitor().getCurrentTPS();

                    if (tps < config.getTpsThreshold()) {
                        performAutoOptimization();
                    }
                } catch (Exception e) {
                    Logger.severe("Error in auto-optimization: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }.runTaskTimer(plugin, 20L * interval, 20L * interval);

        Logger.info("Auto-optimization started (interval: " + interval + "s, threshold: " + config.getTpsThreshold() + " TPS)");
    }

    public OptimizationResult performAutoOptimization() {
        lastOptimizationTime = System.currentTimeMillis();

        OptimizationResult result = new OptimizationResult();

        try {
            Logger.info("Starting automatic optimization...");

            // Optimize each enabled world
            for (World world : Bukkit.getWorlds()) {
                if (isWorldEnabled(world.getName())) {
                    int removed = plugin.getEntityManager().optimizeWorld(world);
                    result.entitiesRemoved += removed;
                }
            }

            // Clear redstone event tracking
            // This would be handled by RedstoneListener

            // Perform garbage collection only under genuine memory pressure,
            // and no more than once per MIN_FORCED_GC_INTERVAL_MILLIS.
            long now = System.currentTimeMillis();
            if (plugin.getPerformanceMonitor().isMemoryCritical() &&
                    now - lastForcedGCTime >= MIN_FORCED_GC_INTERVAL_MILLIS) {
                plugin.getPerformanceMonitor().performGarbageCollection();
                lastForcedGCTime = now;
                result.gcPerformed = true;
            }

            // Update statistics
            plugin.getStatisticsManager().incrementOptimizations();
            plugin.getStatisticsManager().incrementEntitiesRemoved(result.entitiesRemoved);

            // Broadcast results
            if (config.isBroadcastOptimization()) {
                broadcastOptimizationResult(result);
            }

            Logger.info("Optimization complete. Removed " + result.entitiesRemoved + " entities");

        } catch (Exception e) {
            Logger.severe("Error during optimization: " + e.getMessage());
            e.printStackTrace();
            result.error = e.getMessage();
        }

        return result;
    }

    public OptimizationResult performManualOptimization() {
        Logger.info("Manual optimization triggered");
        return performAutoOptimization();
    }

    private void broadcastOptimizationResult(OptimizationResult result) {
        String message = "§a[UltraOptimize] §7Auto-optimization complete! Removed " +
                result.entitiesRemoved + " entities.";

        if (result.gcPerformed) {
            message += " §7(GC performed)";
        }

        if (config.isNotifyAdmins()) {
            notifyAdmins(message);
        } else {
            Bukkit.broadcastMessage(message);
        }
    }

    private void notifyAdmins(String message) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("ultraoptimize.notify")) {
                player.sendMessage(message);
            }
        }
    }

    private boolean isWorldEnabled(String worldName) {
        List<String> enabled = config.getEnabledWorlds();
        List<String> excluded = config.getExcludedWorlds();

        if (!excluded.isEmpty() && excluded.contains(worldName)) {
            return false;
        }

        if (!enabled.isEmpty() && !enabled.contains(worldName)) {
            return false;
        }

        return true;
    }

    public long getTimeSinceLastOptimization() {
        if (lastOptimizationTime == 0) {
            return 0;
        }
        return System.currentTimeMillis() - lastOptimizationTime;
    }

    public boolean isAutoOptimizeEnabled() {
        return autoOptimizeTask != null && !autoOptimizeTask.isCancelled();
    }

    public void toggleAutoOptimize() {
        if (isAutoOptimizeEnabled()) {
            shutdown();
            Logger.info("Auto-optimization disabled");
        } else {
            startAutoOptimization();
            Logger.info("Auto-optimization enabled");
        }
    }

    // Inner class
    public static class OptimizationResult {
        public int entitiesRemoved;
        public boolean gcPerformed;
        public String error;

        public OptimizationResult() {
            this.entitiesRemoved = 0;
            this.gcPerformed = false;
            this.error = null;
        }
    }
}