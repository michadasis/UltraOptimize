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
        // The old check was Class.forName("com.destroystokyo.paper.PaperConfig").
        // That is a server-internal class, and Paper moved its configuration
        // system to io.papermc.paper.configuration in 1.19 - so on any current
        // build the lookup threw, paperDetected stayed false, and every Paper
        // feature silently disabled itself while the console cheerfully
        // announced "Running on Spigot/Bukkit".
        serverVersion = Bukkit.getVersion();

        String name = Bukkit.getName();
        if (name != null && (name.contains("Paper") || name.contains("Purpur") || name.contains("Folia"))) {
            paperDetected = true;
        } else if (Bukkit.getVersion() != null && Bukkit.getVersion().contains("Paper")) {
            paperDetected = true;
        } else {
            paperDetected = classExists("io.papermc.paper.configuration.GlobalConfiguration")
                    || classExists("com.destroystokyo.paper.PaperConfig");
        }

        if (paperDetected) {
            Logger.info("Paper server detected: " + serverVersion);
            Logger.info("Paper-specific optimizations available");
        } else {
            Logger.info("Running on Spigot/Bukkit - Paper features disabled");
        }
    }

    private boolean classExists(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
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

            // Flush region cache (saves all worlds) BEFORE removing any empty
            // region files below. Empty-region removal deletes .mca files
            // directly with raw file I/O; running that against a world with
            // unsaved dirty chunks means those chunks' data isn't reflected
            // on disk yet. Flushing first ensures the file we check reflects
            // the latest state.
            if (regionOptimizer != null && plugin.getConfigManager().isPaperRemoveEmptyRegions()) {
                // The save only happens when we are actually about to delete
                // .mca files, so that what is on disk reflects the live world.
                // It used to run unconditionally, meaning /uo paper optimize
                // forced a full synchronous world save even when it had nothing
                // to do afterwards.
                regionOptimizer.flushRegionCache();

                for (World world : Bukkit.getWorlds()) {
                    result.emptyRegionsRemoved += regionOptimizer.removeEmptyRegions(world);
                }
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
            Logger.info("  Empty regions removed: " + result.emptyRegionsRemoved);
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

    // Result and statistics classes
    public static class PaperOptimizationResult {
        public long startTime;
        public long endTime;
        public long duration;
        public int emptyRegionsRemoved;
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