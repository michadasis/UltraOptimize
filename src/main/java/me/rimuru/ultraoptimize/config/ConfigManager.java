package me.rimuru.ultraoptimize.config;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.*;

public class ConfigManager {

    private final UltraOptimize plugin;
    private FileConfiguration config;

    // Auto-optimize settings
    private boolean autoOptimizeEnabled;
    private int autoOptimizeInterval;
    private int tpsThreshold;

    // Entity settings
    private int maxEntitiesPerChunk;
    private int maxItemsPerChunk;
    private int entityClearRadius;
    private boolean removeDrops;
    private boolean clearLagging;
    private boolean limitSpawns;
    private boolean autoMergeItems;
    private double itemMergeRadius;
    private Set<EntityType> exemptEntities;

    // Chunk settings
    private boolean unloadEmpty;
    private boolean forceUpgrade;
    private int maxLoadedChunks;
    private int unloadRadius;
    private boolean aggressiveUnload;
    private int unloadInterval;

    // Chunk preloading settings
    private boolean chunkPreloadingEnabled;
    private int preloadRadius;
    private int preloadChunksPerTick;
    private boolean spiralPattern;
    private boolean generateChunks;
    private boolean notifyPreloading;

    // Performance settings
    private boolean optimizeRedstone;
    private boolean optimizeHoppers;
    private int maxHoppersPerChunk;
    private int hopperTickRate;
    private boolean optimizeLighting;
    private boolean reduceParticles;
    private int particleReduction;

    // Advanced settings
    private boolean asyncChunks;
    private boolean optimizeAI;
    private int pathfindingLimit;
    private boolean optimizeViewDistance;
    private boolean autoViewDistance;
    private int minViewDistance;
    private int maxViewDistance;
    private boolean optimizeEntityTicking;
    private int entityTickRate;

    // Notification settings
    private boolean notifyAdmins;
    private boolean broadcastOptimization;
    private boolean showStatistics;
    private boolean notifyHangs;
    private boolean notifyEmergency;

    // World settings
    private List<String> enabledWorlds;
    private List<String> excludedWorlds;

    // Paper-specific settings
    private boolean paperEnabled;
    private String paperCpuProfile;

    // Paper chunk system
    private boolean paperTicketSpawnChunks;
    private int paperSpawnTicketRadius;
    private boolean paperUseUrgentLoading;

    // Paper watchdog
    private boolean paperWatchdogEnabled;
    private long paperHangThreshold;
    private long paperCriticalThreshold;
    private int paperEmergencyTriggerCount;
    private boolean paperAutoEmergencyOptimization;

    // Paper region files
    private boolean paperRegionFilesEnabled;
    private boolean paperIncrementalSaving;
    private int paperSaveInterval;
    private boolean paperRemoveEmptyRegions;

    public ConfigManager(UltraOptimize plugin) {
        this.plugin = plugin;
        this.exemptEntities = new HashSet<>();
    }

    public void loadConfiguration() {
        plugin.saveDefaultConfig();
        config = plugin.getConfig();

        try {
            setDefaults();
            loadValues();
            validateValues();
            loadExemptEntities();

            plugin.saveConfig();

        } catch (Exception e) {
            Logger.severe("Failed to load configuration: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void setDefaults() {
        // Auto-optimize
        config.addDefault("auto-optimize.enabled", true);
        config.addDefault("auto-optimize.interval", 300);
        config.addDefault("auto-optimize.tps-threshold", 18);

        // Entities
        config.addDefault("entities.max-per-chunk", 50);
        config.addDefault("entities.max-items-per-chunk", 100);
        config.addDefault("entities.clear-radius", 0);
        config.addDefault("entities.remove-drops", false);
        config.addDefault("entities.clear-lagging", true);
        config.addDefault("entities.limit-spawns", true);
        config.addDefault("entities.auto-merge-items", true);
        config.addDefault("entities.item-merge-radius", 1.5);
        config.addDefault("entities.exempt-types", Arrays.asList(
                "VILLAGER", "PLAYER", "ARMOR_STAND", "ITEM_FRAME",
                "PAINTING", "MINECART", "BOAT", "WITHER", "ENDER_DRAGON"
        ));

        // Chunks
        config.addDefault("chunks.unload-empty", true);
        config.addDefault("chunks.force-upgrade", false);
        config.addDefault("chunks.max-loaded", 0);
        config.addDefault("chunks.unload-radius", 8);
        config.addDefault("chunks.aggressive-unload", false);
        config.addDefault("chunks.unload-interval", 300);

        // Chunk preloading
        config.addDefault("chunk-preloading.enabled", false);
        config.addDefault("chunk-preloading.preload-radius", 5);
        config.addDefault("chunk-preloading.chunks-per-tick", 3);
        config.addDefault("chunk-preloading.spiral-pattern", true);
        config.addDefault("chunk-preloading.generate-chunks", false);
        config.addDefault("chunk-preloading.notify-preloading", true);

        // Performance
        config.addDefault("performance.optimize-redstone", false);
        config.addDefault("performance.optimize-hoppers", false);
        config.addDefault("performance.max-hoppers-per-chunk", 10);
        config.addDefault("performance.hopper-tick-rate", 8);
        config.addDefault("performance.optimize-lighting", true);
        config.addDefault("performance.reduce-particles", false);
        config.addDefault("performance.particle-reduction", 50);

        // Advanced
        config.addDefault("advanced.async-chunks", true);
        config.addDefault("advanced.optimize-ai", true);
        config.addDefault("advanced.pathfinding-limit", 32);
        config.addDefault("advanced.optimize-view-distance", false);
        config.addDefault("advanced.auto-view-distance", false);
        config.addDefault("advanced.min-view-distance", 3);
        config.addDefault("advanced.max-view-distance", 10);
        config.addDefault("advanced.optimize-entity-ticking", true);
        config.addDefault("advanced.entity-tick-rate", 1);

        // Notifications
        config.addDefault("notifications.notify-admins", true);
        config.addDefault("notifications.broadcast-optimization", true);
        config.addDefault("notifications.show-statistics", true);
        config.addDefault("notifications.notify-hangs", true);
        config.addDefault("notifications.notify-emergency", true);

        // Worlds
        config.addDefault("worlds.enabled-worlds", new ArrayList<>());
        config.addDefault("worlds.excluded-worlds", new ArrayList<>());

        // Paper-specific settings
        config.addDefault("paper.enabled", true);
        config.addDefault("paper.cpu-profile", "LOW");

        // Paper chunk system
        config.addDefault("paper.chunk-system.ticket-spawn-chunks", false);
        config.addDefault("paper.chunk-system.spawn-ticket-radius", 3);
        config.addDefault("paper.chunk-system.use-urgent-loading", true);

        // Paper watchdog
        config.addDefault("paper.watchdog.enabled", true);
        config.addDefault("paper.watchdog.hang-threshold", 10000);
        config.addDefault("paper.watchdog.critical-threshold", 30000);
        config.addDefault("paper.watchdog.emergency-trigger-count", 3);
        config.addDefault("paper.watchdog.auto-emergency-optimization", false);

        // Paper region files
        config.addDefault("paper.region-files.enabled", true);
        config.addDefault("paper.region-files.incremental-saving", false);
        config.addDefault("paper.region-files.save-interval", 900);
        config.addDefault("paper.region-files.remove-empty-regions", false);

        config.options().copyDefaults(true);
    }

    private void loadValues() {
        // Auto-optimize
        autoOptimizeEnabled = config.getBoolean("auto-optimize.enabled");
        autoOptimizeInterval = config.getInt("auto-optimize.interval");
        tpsThreshold = config.getInt("auto-optimize.tps-threshold");

        // Entities
        maxEntitiesPerChunk = config.getInt("entities.max-per-chunk");
        maxItemsPerChunk = config.getInt("entities.max-items-per-chunk");
        entityClearRadius = config.getInt("entities.clear-radius");
        removeDrops = config.getBoolean("entities.remove-drops");
        clearLagging = config.getBoolean("entities.clear-lagging");
        limitSpawns = config.getBoolean("entities.limit-spawns");
        autoMergeItems = config.getBoolean("entities.auto-merge-items", true);
        itemMergeRadius = config.getDouble("entities.item-merge-radius", 1.5);

        // Chunks
        unloadEmpty = config.getBoolean("chunks.unload-empty");
        forceUpgrade = config.getBoolean("chunks.force-upgrade");
        maxLoadedChunks = config.getInt("chunks.max-loaded");
        unloadRadius = config.getInt("chunks.unload-radius");
        aggressiveUnload = config.getBoolean("chunks.aggressive-unload", false);
        unloadInterval = config.getInt("chunks.unload-interval");

        // Chunk preloading
        chunkPreloadingEnabled = config.getBoolean("chunk-preloading.enabled", false);
        preloadRadius = config.getInt("chunk-preloading.preload-radius", 5);
        preloadChunksPerTick = config.getInt("chunk-preloading.chunks-per-tick", 3);
        spiralPattern = config.getBoolean("chunk-preloading.spiral-pattern", true);
        generateChunks = config.getBoolean("chunk-preloading.generate-chunks", false);
        notifyPreloading = config.getBoolean("chunk-preloading.notify-preloading", true);

        // Performance
        optimizeRedstone = config.getBoolean("performance.optimize-redstone", false);
        optimizeHoppers = config.getBoolean("performance.optimize-hoppers", false);
        maxHoppersPerChunk = config.getInt("performance.max-hoppers-per-chunk");
        hopperTickRate = config.getInt("performance.hopper-tick-rate");
        optimizeLighting = config.getBoolean("performance.optimize-lighting");
        reduceParticles = config.getBoolean("performance.reduce-particles");
        particleReduction = config.getInt("performance.particle-reduction");

        // Advanced
        asyncChunks = config.getBoolean("advanced.async-chunks");
        optimizeAI = config.getBoolean("advanced.optimize-ai");
        pathfindingLimit = config.getInt("advanced.pathfinding-limit");
        optimizeViewDistance = config.getBoolean("advanced.optimize-view-distance");
        autoViewDistance = config.getBoolean("advanced.auto-view-distance");
        minViewDistance = config.getInt("advanced.min-view-distance");
        maxViewDistance = config.getInt("advanced.max-view-distance");
        optimizeEntityTicking = config.getBoolean("advanced.optimize-entity-ticking", true);
        entityTickRate = config.getInt("advanced.entity-tick-rate", 1);

        // Notifications
        notifyAdmins = config.getBoolean("notifications.notify-admins");
        broadcastOptimization = config.getBoolean("notifications.broadcast-optimization");
        showStatistics = config.getBoolean("notifications.show-statistics");
        notifyHangs = config.getBoolean("notifications.notify-hangs", true);
        notifyEmergency = config.getBoolean("notifications.notify-emergency", true);

        // Worlds
        enabledWorlds = config.getStringList("worlds.enabled-worlds");
        excludedWorlds = config.getStringList("worlds.excluded-worlds");

        // Paper-specific settings
        paperEnabled = config.getBoolean("paper.enabled", true);
        paperCpuProfile = config.getString("paper.cpu-profile", "LOW");

        // Paper chunk system
        paperTicketSpawnChunks = config.getBoolean("paper.chunk-system.ticket-spawn-chunks", false);
        paperSpawnTicketRadius = config.getInt("paper.chunk-system.spawn-ticket-radius", 3);
        paperUseUrgentLoading = config.getBoolean("paper.chunk-system.use-urgent-loading", true);

        // Paper watchdog
        paperWatchdogEnabled = config.getBoolean("paper.watchdog.enabled", true);
        paperHangThreshold = config.getLong("paper.watchdog.hang-threshold", 10000);
        paperCriticalThreshold = config.getLong("paper.watchdog.critical-threshold", 30000);
        paperEmergencyTriggerCount = config.getInt("paper.watchdog.emergency-trigger-count", 3);
        paperAutoEmergencyOptimization = config.getBoolean("paper.watchdog.auto-emergency-optimization", false);

        // Paper region files
        paperRegionFilesEnabled = config.getBoolean("paper.region-files.enabled", true);
        paperIncrementalSaving = config.getBoolean("paper.region-files.incremental-saving", false);
        paperSaveInterval = config.getInt("paper.region-files.save-interval", 900);
        paperRemoveEmptyRegions = config.getBoolean("paper.region-files.remove-empty-regions", false);
    }

    private void validateValues() {
        boolean hasWarnings = false;

        // Validate auto-optimize settings
        if (autoOptimizeInterval < 30) {
            Logger.warning("auto-optimize.interval too low (" + autoOptimizeInterval + "), adjusted to 30 seconds");
            autoOptimizeInterval = 30;
            hasWarnings = true;
        }
        if (autoOptimizeInterval > 3600) {
            Logger.warning("auto-optimize.interval very high (" + autoOptimizeInterval + "), adjusted to 3600 seconds");
            autoOptimizeInterval = 3600;
            hasWarnings = true;
        }
        if (tpsThreshold < 1 || tpsThreshold > 20) {
            Logger.warning("auto-optimize.tps-threshold invalid (" + tpsThreshold + "), adjusted to 18");
            tpsThreshold = Math.max(1, Math.min(20, tpsThreshold));
            hasWarnings = true;
        }

        // Validate entity settings
        if (maxEntitiesPerChunk < 1) {
            Logger.warning("entities.max-per-chunk too low (" + maxEntitiesPerChunk + "), adjusted to 10");
            maxEntitiesPerChunk = 10;
            hasWarnings = true;
        }
        if (maxItemsPerChunk < 1) {
            Logger.warning("entities.max-items-per-chunk too low (" + maxItemsPerChunk + "), adjusted to 10");
            maxItemsPerChunk = 10;
            hasWarnings = true;
        }
        if (itemMergeRadius < 0.5 || itemMergeRadius > 10) {
            Logger.warning("entities.item-merge-radius invalid (" + itemMergeRadius + "), adjusted to 1.5");
            itemMergeRadius = Math.max(0.5, Math.min(10, itemMergeRadius));
            hasWarnings = true;
        }

        // Validate chunk preloading settings
        if (preloadRadius < 1 || preloadRadius > 32) {
            Logger.warning("chunk-preloading.preload-radius invalid (" + preloadRadius + "), adjusted to 5");
            preloadRadius = Math.max(1, Math.min(32, preloadRadius));
            hasWarnings = true;
        }
        if (preloadChunksPerTick < 1 || preloadChunksPerTick > 10) {
            Logger.warning("chunk-preloading.chunks-per-tick invalid (" + preloadChunksPerTick + "), adjusted to 3");
            preloadChunksPerTick = Math.max(1, Math.min(10, preloadChunksPerTick));
            hasWarnings = true;
        }

        // Validate chunk settings
        if (unloadRadius < 1) {
            Logger.warning("chunks.unload-radius too low (" + unloadRadius + "), adjusted to 3");
            unloadRadius = 3;
            hasWarnings = true;
        }
        if (unloadInterval < 60) {
            Logger.warning("chunks.unload-interval too low (" + unloadInterval + "), adjusted to 60 seconds");
            unloadInterval = 60;
            hasWarnings = true;
        }

        // Validate advanced settings
        if (pathfindingLimit < 8 || pathfindingLimit > 128) {
            Logger.warning("advanced.pathfinding-limit invalid (" + pathfindingLimit + "), adjusted to 32");
            pathfindingLimit = Math.max(8, Math.min(128, pathfindingLimit));
            hasWarnings = true;
        }
        if (minViewDistance < 2 || minViewDistance > 32) {
            Logger.warning("advanced.min-view-distance invalid (" + minViewDistance + "), adjusted to 3");
            minViewDistance = Math.max(2, Math.min(32, minViewDistance));
            hasWarnings = true;
        }
        if (maxViewDistance < minViewDistance || maxViewDistance > 32) {
            Logger.warning("advanced.max-view-distance invalid (" + maxViewDistance + "), adjusted to 10");
            maxViewDistance = Math.max(minViewDistance, Math.min(32, maxViewDistance));
            hasWarnings = true;
        }

        // Validate Paper settings
        if (paperWatchdogEnabled) {
            if (paperHangThreshold < 1000 || paperHangThreshold > 60000) {
                Logger.warning("paper.watchdog.hang-threshold invalid (" + paperHangThreshold + "ms), adjusted to 10000ms");
                paperHangThreshold = Math.max(1000, Math.min(60000, paperHangThreshold));
                hasWarnings = true;
            }
            if (paperCriticalThreshold < paperHangThreshold || paperCriticalThreshold > 120000) {
                Logger.warning("paper.watchdog.critical-threshold invalid (" + paperCriticalThreshold + "ms), adjusted to 30000ms");
                paperCriticalThreshold = Math.max(paperHangThreshold, Math.min(120000, paperCriticalThreshold));
                hasWarnings = true;
            }
            if (paperEmergencyTriggerCount < 1 || paperEmergencyTriggerCount > 10) {
                Logger.warning("paper.watchdog.emergency-trigger-count invalid (" + paperEmergencyTriggerCount + "), adjusted to 3");
                paperEmergencyTriggerCount = Math.max(1, Math.min(10, paperEmergencyTriggerCount));
                hasWarnings = true;
            }
        }

        if (paperRegionFilesEnabled && paperIncrementalSaving) {
            // Floor raised from 10s to 300s. This triggers a full synchronous
            // world save, so a 30s interval - the old default - meant a
            // main-thread stall twice a minute for the life of the server.
            if (paperSaveInterval < 300 || paperSaveInterval > 3600) {
                Logger.warning("paper.region-files.save-interval invalid (" + paperSaveInterval +
                        "s), adjusted to 900s - this option performs a full synchronous world save");
                paperSaveInterval = Math.max(300, Math.min(3600, paperSaveInterval));
                hasWarnings = true;
            }
        }

        if (paperSpawnTicketRadius < 1 || paperSpawnTicketRadius > 10) {
            Logger.warning("paper.chunk-system.spawn-ticket-radius invalid (" + paperSpawnTicketRadius + "), adjusted to 3");
            paperSpawnTicketRadius = Math.max(1, Math.min(10, paperSpawnTicketRadius));
            hasWarnings = true;
        }

        // Validate CPU profile
        if (!paperCpuProfile.equalsIgnoreCase("LOW") &&
                !paperCpuProfile.equalsIgnoreCase("MEDIUM") &&
                !paperCpuProfile.equalsIgnoreCase("HIGH")) {
            Logger.warning("paper.cpu-profile invalid (" + paperCpuProfile + "), adjusted to LOW");
            paperCpuProfile = "LOW";
            hasWarnings = true;
        }

        if (hasWarnings) {
            Logger.warning("Some configuration values were adjusted. Check the warnings above.");
        }
    }

    private void loadExemptEntities() {
        exemptEntities.clear();
        for (String typeName : config.getStringList("entities.exempt-types")) {
            try {
                exemptEntities.add(EntityType.valueOf(typeName.toUpperCase()));
            } catch (IllegalArgumentException e) {
                Logger.warning("Invalid entity type in exempt list: " + typeName);
            }
        }
    }

    // Basic getters
    public boolean isAutoOptimizeEnabled() { return autoOptimizeEnabled; }
    public int getAutoOptimizeInterval() { return autoOptimizeInterval; }
    public int getTpsThreshold() { return tpsThreshold; }
    public int getMaxEntitiesPerChunk() { return maxEntitiesPerChunk; }
    public int getMaxItemsPerChunk() { return maxItemsPerChunk; }
    public int getEntityClearRadius() { return entityClearRadius; }
    public boolean isRemoveDrops() { return removeDrops; }
    public boolean isClearLagging() { return clearLagging; }
    public boolean isLimitSpawns() { return limitSpawns; }
    public boolean isAutoMergeItems() { return autoMergeItems; }
    public double getItemMergeRadius() { return itemMergeRadius; }
    public Set<EntityType> getExemptEntities() { return exemptEntities; }
    public boolean isUnloadEmpty() { return unloadEmpty; }
    public boolean isForceUpgrade() { return forceUpgrade; }
    public int getMaxLoadedChunks() { return maxLoadedChunks; }
    public int getUnloadRadius() { return unloadRadius; }
    public boolean isAggressiveUnload() { return aggressiveUnload; }
    public int getUnloadInterval() { return unloadInterval; }

    // Chunk preloading getters
    public boolean isChunkPreloadingEnabled() { return chunkPreloadingEnabled; }
    public int getPreloadRadius() { return preloadRadius; }
    public int getPreloadChunksPerTick() { return preloadChunksPerTick; }
    public boolean isSpiralPattern() { return spiralPattern; }
    public boolean isGenerateChunks() { return generateChunks; }
    public boolean isNotifyPreloading() { return notifyPreloading; }

    // Performance getters
    public boolean isOptimizeRedstone() { return optimizeRedstone; }
    public boolean isOptimizeHoppers() { return optimizeHoppers; }
    public int getMaxHoppersPerChunk() { return maxHoppersPerChunk; }
    public int getHopperTickRate() { return hopperTickRate; }
    public boolean isOptimizeLighting() { return optimizeLighting; }
    public boolean isReduceParticles() { return reduceParticles; }
    public int getParticleReduction() { return particleReduction; }

    // Advanced getters
    public boolean isAsyncChunks() { return asyncChunks; }
    public boolean isOptimizeAI() { return optimizeAI; }
    public int getPathfindingLimit() { return pathfindingLimit; }
    public boolean isOptimizeViewDistance() { return optimizeViewDistance; }
    public boolean isAutoViewDistance() { return autoViewDistance; }
    public int getMinViewDistance() { return minViewDistance; }
    public int getMaxViewDistance() { return maxViewDistance; }
    public boolean isOptimizeEntityTicking() { return optimizeEntityTicking; }
    public int getEntityTickRate() { return entityTickRate; }

    // Notification getters
    public boolean isNotifyAdmins() { return notifyAdmins; }
    public boolean isBroadcastOptimization() { return broadcastOptimization; }
    public boolean isShowStatistics() { return showStatistics; }
    public boolean isNotifyHangs() { return notifyHangs; }
    public boolean isNotifyEmergency() { return notifyEmergency; }

    // World getters
    public List<String> getEnabledWorlds() { return enabledWorlds; }
    public List<String> getExcludedWorlds() { return excludedWorlds; }

    // Paper getters
    public boolean isPaperEnabled() { return paperEnabled; }
    public String getPaperCpuProfile() { return paperCpuProfile; }

    // Paper chunk system getters
    public boolean isPaperTicketSpawnChunks() { return paperTicketSpawnChunks; }
    public int getPaperSpawnTicketRadius() { return paperSpawnTicketRadius; }
    public boolean isPaperUseUrgentLoading() { return paperUseUrgentLoading; }

    // Paper watchdog getters
    public boolean isPaperWatchdogEnabled() { return paperWatchdogEnabled; }
    public long getPaperHangThreshold() { return paperHangThreshold; }
    public long getPaperCriticalThreshold() { return paperCriticalThreshold; }
    public int getPaperEmergencyTriggerCount() { return paperEmergencyTriggerCount; }
    public boolean isPaperAutoEmergencyOptimization() { return paperAutoEmergencyOptimization; }

    // Paper region files getters
    public boolean isPaperRegionFilesEnabled() { return paperRegionFilesEnabled; }
    public boolean isPaperIncrementalSaving() { return paperIncrementalSaving; }
    public int getPaperSaveInterval() { return paperSaveInterval; }
    public boolean isPaperRemoveEmptyRegions() { return paperRemoveEmptyRegions; }

    public FileConfiguration getConfig() {
        return config;
    }
}