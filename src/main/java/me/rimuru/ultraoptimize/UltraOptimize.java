package me.rimuru.ultraoptimize;

import me.rimuru.ultraoptimize.commands.CommandManager;
import me.rimuru.ultraoptimize.config.ConfigManager;
import me.rimuru.ultraoptimize.listeners.*;
import me.rimuru.ultraoptimize.managers.*;
import me.rimuru.ultraoptimize.paper.PaperOptimizationManager;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class UltraOptimize extends JavaPlugin {

    private static UltraOptimize instance;

    // Managers
    private ConfigManager configManager;
    private ChunkManager chunkManager;
    private EntityManager entityManager;
    private ChunkPreloader chunkPreloader;
    private OptimizationManager optimizationManager;
    private PerformanceMonitor performanceMonitor;
    private StatisticsManager statisticsManager;
    private CommandManager commandManager;

    // Paper-specific manager
    private PaperOptimizationManager paperManager;

    @Override
    public void onEnable() {
        instance = this;

        try {
            // Initialize configuration first
            configManager = new ConfigManager(this);
            configManager.loadConfiguration();

            // Initialize managers in correct order
            statisticsManager = new StatisticsManager(this);
            performanceMonitor = new PerformanceMonitor(this);
            chunkManager = new ChunkManager(this);
            entityManager = new EntityManager(this);
            chunkPreloader = new ChunkPreloader(this);
            optimizationManager = new OptimizationManager(this);

            // Initialize Paper-specific features
            paperManager = new PaperOptimizationManager(this);
            paperManager.initialize();

            // Register listeners (FIXED - now includes PlayerMoveListener)
            registerListeners();

            // Initialize commands
            commandManager = new CommandManager(this);

            // Start managers
            startManagers();

            Logger.info("=================================");
            Logger.info("UltraOptimize v" + getDescription().getVersion() + " ENABLED");
            Logger.info("Supporting MC 1.13-1.21+");
            Logger.info("Java " + System.getProperty("java.version"));
            Logger.info("Server: " + Bukkit.getVersion());
            Logger.info("Paper Mode: " + (paperManager.isPaperDetected() ? "ENABLED" : "DISABLED"));
            Logger.info("Auto-Optimize: " + (configManager.isAutoOptimizeEnabled() ? "ENABLED" : "DISABLED"));
            Logger.info("Spawn Chunk Preloading: " + (configManager.isChunkPreloadingEnabled() ? "ENABLED" : "DISABLED"));
            Logger.info("TPS Threshold: " + configManager.getTpsThreshold());
            if (configManager.isChunkPreloadingEnabled()) {
                Logger.info("Preload Radius: " + configManager.getPreloadRadius() + " chunks");
            }
            if (paperManager.isPaperDetected()) {
                if (configManager.isPaperEnabled()) {
                    Logger.info("Paper Optimizations: ENABLED");
                    if (configManager.isPaperWatchdogEnabled()) {
                        Logger.info("Watchdog Monitor: ACTIVE");
                    }
                    if (configManager.isPaperRegionFilesEnabled()) {
                        Logger.info("Region Optimization: ACTIVE");
                    }
                    if (configManager.isPaperUseChunkTickets()) {
                        Logger.info("Advanced Chunk System: ACTIVE");
                    }
                } else {
                    Logger.info("Paper Optimizations: DISABLED (in config)");
                }
            }
            Logger.info("=================================");

        } catch (Exception e) {
            Logger.severe("Failed to initialize plugin: " + e.getMessage());
            e.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        try {
            Logger.info("Shutting down UltraOptimize...");

            // Stop Paper managers first
            if (paperManager != null) {
                paperManager.shutdown();
            }

            // Stop all managers in reverse order
            if (optimizationManager != null) {
                optimizationManager.shutdown();
            }
            if (chunkPreloader != null) {
                chunkPreloader.shutdown();
            }
            if (chunkManager != null) {
                chunkManager.shutdown();
            }
            if (performanceMonitor != null) {
                performanceMonitor.shutdown();
            }
            if (statisticsManager != null) {
                statisticsManager.saveStatistics();
            }

            Logger.info("UltraOptimize disabled successfully!");

        } catch (Exception e) {
            Logger.severe("Error during plugin shutdown: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void registerListeners() {
        try {
            getServer().getPluginManager().registerEvents(new ChunkListener(this), this);
            getServer().getPluginManager().registerEvents(new EntityListener(this), this);
            getServer().getPluginManager().registerEvents(new RedstoneListener(this), this);

            // FIXED: Register PlayerMoveListener for chunk preloading
            getServer().getPluginManager().registerEvents(new PlayerMoveListener(this), this);

            Logger.info("Event listeners registered successfully");
        } catch (Exception e) {
            Logger.severe("Error registering listeners: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void startManagers() {
        try {
            // Start managers in correct order
            performanceMonitor.start();
            chunkManager.start();
            chunkPreloader.start(); // Will preload spawn chunks on startup
            optimizationManager.start();

            // Start Paper-specific systems (only if Paper is detected AND enabled in config)
            if (paperManager != null && paperManager.isPaperDetected() && configManager.isPaperEnabled()) {
                paperManager.start();
            }

            Logger.info("All managers started successfully");
        } catch (Exception e) {
            Logger.severe("Error starting managers: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void reload() {
        try {
            Logger.info("Reloading UltraOptimize configuration...");

            // Reload config file
            reloadConfig();
            configManager.loadConfiguration();

            // Restart managers with new config
            optimizationManager.restart();
            chunkManager.restart();
            chunkPreloader.restart();

            // Restart Paper managers if enabled
            if (paperManager != null && paperManager.isPaperDetected() && configManager.isPaperEnabled()) {
                Logger.info("Restarting Paper optimization systems...");
                paperManager.shutdown();
                paperManager.initialize();
                paperManager.start();
            }

            Logger.info("Configuration reloaded successfully!");
            Logger.info("Auto-Optimize: " + (configManager.isAutoOptimizeEnabled() ? "ENABLED" : "DISABLED"));
            Logger.info("Spawn Chunk Preloading: " + (configManager.isChunkPreloadingEnabled() ? "ENABLED" : "DISABLED"));
            Logger.info("Paper Optimizations: " + (configManager.isPaperEnabled() ? "ENABLED" : "DISABLED"));

        } catch (Exception e) {
            Logger.severe("Failed to reload configuration: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Getters
    public static UltraOptimize getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public ChunkManager getChunkManager() {
        return chunkManager;
    }

    public EntityManager getEntityManager() {
        return entityManager;
    }

    public ChunkPreloader getChunkPreloader() {
        return chunkPreloader;
    }

    public OptimizationManager getOptimizationManager() {
        return optimizationManager;
    }

    public PerformanceMonitor getPerformanceMonitor() {
        return performanceMonitor;
    }

    public StatisticsManager getStatisticsManager() {
        return statisticsManager;
    }

    public CommandManager getCommandManager() {
        return commandManager;
    }

    public PaperOptimizationManager getPaperManager() {
        return paperManager;
    }
}