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
    private EntityAIManager entityAIManager;
    private CommandManager commandManager;

    // Paper-specific manager
    private PaperOptimizationManager paperManager;

    private RedstoneListener redstoneListener;
    private HopperListener hopperListener;

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
            entityAIManager = new EntityAIManager(this);

            // Initialize Paper-specific features
            paperManager = new PaperOptimizationManager(this);
            paperManager.initialize();

            // Register listeners
            registerListeners();

            // Initialize commands
            commandManager = new CommandManager(this);

            // Start managers
            startManagers();

            Logger.info("=================================");
            Logger.info("UltraOptimize v" + getDescription().getVersion() + " ENABLED");
            Logger.info("Supporting MC 1.13-26.2+");
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
            if (entityAIManager != null) {
                entityAIManager.shutdown();
            }
            if (entityManager != null) {
                entityManager.shutdown();
            }
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
                statisticsManager.shutdown();
                statisticsManager.saveStatistics();
            }
            if (redstoneListener != null) {
                redstoneListener.shutdown();
            }
            if (hopperListener != null) {
                hopperListener.shutdown();
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

            redstoneListener = new RedstoneListener(this);
            getServer().getPluginManager().registerEvents(redstoneListener, this);
            redstoneListener.start();

            hopperListener = new HopperListener(this);
            getServer().getPluginManager().registerEvents(hopperListener, this);
            hopperListener.start();

            // PlayerMoveListener has been removed. It called
            // ChunkPreloader#onPlayerMove/#queuePreloadForPlayer, both of which
            // were empty placeholders - but to decide whether to call them it
            // ran event.getFrom().getChunk() and event.getTo().getChunk() on
            // every move packet from every player. Location#getChunk() goes
            // through World#getChunkAt, which loads the chunk if it is not
            // already loaded. It was pure cost for no behaviour.

            Logger.info("Event listeners registered successfully");
        } catch (Exception e) {
            Logger.severe("Error registering listeners: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void startManagers() {
        try {
            // Start managers in correct order
            statisticsManager.start();
            performanceMonitor.start();
            chunkManager.start();
            entityManager.start();
            chunkPreloader.start(); // Will preload spawn chunks on startup
            optimizationManager.start();
            entityAIManager.start();

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
            entityAIManager.restart();

            // Always tear down Paper managers first so toggling paper.enabled
            // to false and reloading actually stops them - only gating the
            // re-init on isPaperEnabled() left the old listener/tasks running
            // forever whenever the feature was disabled via reload instead of
            // a full server restart.
            if (paperManager != null && paperManager.isPaperDetected()) {
                Logger.info("Restarting Paper optimization systems...");
                paperManager.shutdown();
                if (configManager.isPaperEnabled()) {
                    paperManager.initialize();
                    paperManager.start();
                }
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

    public EntityAIManager getEntityAIManager() {
        return entityAIManager;
    }

    public CommandManager getCommandManager() {
        return commandManager;
    }

    public PaperOptimizationManager getPaperManager() {
        return paperManager;
    }
}