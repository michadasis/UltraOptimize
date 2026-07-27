package me.rimuru.ultraoptimize.paper;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * Main manager for all Paper-specific optimizations
 * Coordinates advanced chunk system, region optimization, and watchdog monitoring
 */
public class PaperOptimizationManager {

    private final UltraOptimize plugin;

    // Paper optimization modules
    private PaperChunkSystem chunkSystem;
    private PaperChunkLoader chunkLoader;
    private WatchdogMonitor watchdogMonitor;
    private RegionFileOptimizer regionOptimizer;

    private boolean paperDetected;
    private String serverVersion;

    public PaperOptimizationManager(UltraOptimize plugin) {
        this.plugin = plugin;
        detectPaper();
    }

    /**
     * Detect if running on Paper and initialize Paper-specific features
     */
    private void detectPaper() {
        try {
            Class.forName("com.destroystokyo.paper.PaperConfig");
            paperDetected = true;
            serverVersion = Bukkit.getVersion();
            Logger.info("Paper server detected: " + serverVersion);
            Logger.info("Paper-specific optimizations available");
        } catch (ClassNotFoundException e) {
            paperDetected = false;
            Logger.info("Running on Spigot/Bukkit - Paper features disabled");
        }
    }

    /**
     * Initialize all Paper optimization modules
     */
    public void initialize() {
        if (!paperDetected) {
            Logger.info("Skipping Paper optimization initialization");
            return;
        }

        if (!plugin.getConfigManager().isPaperEnabled()) {
            Logger.info("Paper optimizations disabled in config");
            return;
        }

        try {
            // Initialize advanced chunk system
            chunkSystem = new PaperChunkSystem(plugin);
            Logger.info("Paper chunk system initialized");

            // Initialize chunk loader
            chunkLoader = new PaperChunkLoader(plugin);
            // FIXED: Register PaperChunkLoader as event listener
            plugin.getServer().getPluginManager().registerEvents(chunkLoader, plugin);
            Logger.info("Paper chunk loader initialized and registered");

            // Initialize watchdog monitor (only if enabled in config)
            if (plugin.getConfigManager().isPaperWatchdogEnabled()) {
                watchdogMonitor = new WatchdogMonitor(plugin);
                Logger.info("Watchdog monitor initialized");
            } else {
                Logger.info("Watchdog monitor disabled in config");
            }

            // Initialize region file optimizer (only if enabled in config)
            if (plugin.getConfigManager().isPaperRegionFilesEnabled()) {
                regionOptimizer = new RegionFileOptimizer(plugin);
                Logger.info("Region file optimizer initialized");
            } else {
                Logger.info("Region file optimizer disabled in config");
            }

            Logger.info("Paper optimization manager fully initialized");

        } catch (Exception e) {
            Logger.severe("Failed to initialize Paper optimizations: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Start all Paper optimization systems
     */
    public void start() {
        if (!paperDetected) {
            return;
        }

        if (!plugin.getConfigManager().isPaperEnabled()) {
            Logger.info("Paper optimizations disabled - skipping start");
            return;
        }

        try {
            if (watchdogMonitor != null) {
                watchdogMonitor.start();
            }

            if (regionOptimizer != null) {
                regionOptimizer.start();
            }

            // Add spawn chunk tickets if configured
            if (plugin.getConfigManager().isChunkPreloadingEnabled() &&
                    plugin.getConfigManager().isPaperTicketSpawnChunks() &&
                    chunkSystem != null) {
                for (World world : Bukkit.getWorlds()) {
                    int radius = plugin.getConfigManager().getPaperSpawnTicketRadius();
                    chunkSystem.ticketSpawnChunks(world, radius);
                }
            }

            Logger.info("Paper optimization systems started");

        } catch (Exception e) {
            Logger.severe("Error starting Paper optimizations: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Shutdown all Paper optimization systems
     */
    public void shutdown() {
        if (!paperDetected) {
            return;
        }

        try {
            if (watchdogMonitor != null) {
                watchdogMonitor.shutdown();
                watchdogMonitor = null;
            }

            if (regionOptimizer != null) {
                regionOptimizer.shutdown();
                regionOptimizer = null;
            }

            if (chunkLoader != null) {
                chunkLoader.shutdown();
                chunkLoader = null;
            }

            if (chunkSystem != null) {
                chunkSystem.shutdown();
                chunkSystem = null;
            }

            Logger.info("Paper optimization systems stopped");

        } catch (Exception e) {
            Logger.severe("Error shutting down Paper optimizations: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Perform comprehensive Paper optimization
     */
    public PaperOptimizationResult performOptimization() {
        PaperOptimizationResult result = new PaperOptimizationResult();
        result.startTime = System.currentTimeMillis();

        if (!paperDetected) {
            result.error = "Not running on Paper";
            return result;
        }

        if (!plugin.getConfigManager().isPaperEnabled()) {
            result.error = "Paper optimizations disabled in config";
            return result;
        }

        try {
            Logger.info("Starting Paper optimization...");

            // Region file optimization
            if (regionOptimizer != null && plugin.getConfigManager().isPaperRegionFilesEnabled()) {
                for (World world : Bukkit.getWorlds()) {
                    RegionFileOptimizer.OptimizationResult regionResult =
                            regionOptimizer.optimizeRegionFiles(world);
                    result.regionsOptimized += regionResult.filesOptimized;
                    result.bytesFreed += regionResult.bytesFreed;
                }
            }

            // Remove empty regions if configured
            if (regionOptimizer != null && plugin.getConfigManager().isPaperRemoveEmptyRegions()) {
                for (World world : Bukkit.getWorlds()) {
                    result.emptyRegionsRemoved += regionOptimizer.removeEmptyRegions(world);
                }
            }

            // Flush region cache
            if (regionOptimizer != null) {
                regionOptimizer.flushRegionCache();
            }

            // Clear unused chunk tickets
            if (chunkSystem != null) {
                // Keep spawn tickets, but this could be expanded
                Logger.debug("Chunk ticket management skipped (spawn tickets active)");
            }

            result.endTime = System.currentTimeMillis();
            result.duration = result.endTime - result.startTime;
            result.success = true;

            Logger.info("Paper optimization complete:");
            Logger.info("  Regions optimized: " + result.regionsOptimized);
            Logger.info("  Empty regions removed: " + result.emptyRegionsRemoved);
            Logger.info("  Bytes freed: " + formatBytes(result.bytesFreed));
            Logger.info("  Duration: " + result.duration + "ms");

        } catch (Exception e) {
            result.error = e.getMessage();
            result.success = false;
            Logger.severe("Paper optimization failed: " + e.getMessage());
            e.printStackTrace();
        }

        return result;
    }

    /**
     * Get comprehensive statistics from all Paper systems
     */
    public PaperStats getStatistics() {
        PaperStats stats = new PaperStats();
        stats.paperDetected = paperDetected;
        stats.serverVersion = serverVersion;

        if (!paperDetected) {
            return stats;
        }

        if (chunkSystem != null) {
            stats.chunkSystemStats = chunkSystem.getStatistics();
        }

        if (watchdogMonitor != null) {
            stats.watchdogStats = watchdogMonitor.getStatistics();
        }

        if (regionOptimizer != null) {
            stats.regionStats = regionOptimizer.getStatistics();
        }

        return stats;
    }

    // Getters for individual systems
    public PaperChunkSystem getChunkSystem() {
        return chunkSystem;
    }

    public PaperChunkLoader getChunkLoader() {
        return chunkLoader;
    }

    public WatchdogMonitor getWatchdogMonitor() {
        return watchdogMonitor;
    }

    public RegionFileOptimizer getRegionOptimizer() {
        return regionOptimizer;
    }

    public boolean isPaperDetected() {
        return paperDetected;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // Result and statistics classes
    public static class PaperOptimizationResult {
        public long startTime;
        public long endTime;
        public long duration;
        public int regionsOptimized;
        public int emptyRegionsRemoved;
        public long bytesFreed;
        public boolean success;
        public String error;
    }

    public static class PaperStats {
        public boolean paperDetected;
        public String serverVersion;
        public PaperChunkSystem.ChunkSystemStats chunkSystemStats;
        public WatchdogMonitor.WatchdogStats watchdogStats;
        public RegionFileOptimizer.RegionStats regionStats;
    }
}