package me.rimuru.ultraoptimize.managers;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.config.ConfigManager;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkManager {

    private final UltraOptimize plugin;
    private final ConfigManager config;

    private final Map<String, Long> chunkLoadTimes;
    private final Map<String, ChunkData> chunkDataMap;
    private final Set<String> problematicChunks;

    private BukkitTask unloadTask;
    private BukkitTask monitorTask;

    public ChunkManager(UltraOptimize plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.chunkLoadTimes = new ConcurrentHashMap<>();
        this.chunkDataMap = new ConcurrentHashMap<>();
        this.problematicChunks = ConcurrentHashMap.newKeySet();
    }

    public void start() {
        if (config.isAggressiveUnload()) {
            startAggressiveUnload();
        }
        startChunkMonitor();
        Logger.info("ChunkManager started successfully");
    }

    public void shutdown() {
        if (unloadTask != null) {
            unloadTask.cancel();
        }
        if (monitorTask != null) {
            monitorTask.cancel();
        }
        chunkLoadTimes.clear();
        chunkDataMap.clear();
        Logger.info("ChunkManager shut down");
    }

    public void restart() {
        shutdown();
        start();
    }

    private void startAggressiveUnload() {
        int interval = config.getUnloadInterval();

        unloadTask = new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    performAggressiveUnload();
                } catch (Exception e) {
                    Logger.severe("Error during aggressive chunk unload: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }.runTaskTimer(plugin, 20L * interval, 20L * interval);
    }

    private void startChunkMonitor() {
        monitorTask = new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    monitorChunks();
                } catch (Exception e) {
                    Logger.severe("Error monitoring chunks: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }.runTaskTimer(plugin, 100L, 100L);
    }

    private void performAggressiveUnload() {
        int totalUnloaded = 0;
        int totalLoaded = 0;

        for (World world : Bukkit.getWorlds()) {
            if (!isWorldEnabled(world.getName())) continue;

            Chunk[] loadedChunks = world.getLoadedChunks();
            totalLoaded += loadedChunks.length;

            int unloaded = 0;
            for (Chunk chunk : loadedChunks) {
                if (shouldUnloadChunk(chunk, world)) {
                    try {
                        chunk.unload(true);
                        unloaded++;

                        String key = getChunkKey(chunk, world);
                        chunkLoadTimes.remove(key);
                        chunkDataMap.remove(key);

                    } catch (Exception e) {
                        Logger.warning("Failed to unload chunk at " +
                                chunk.getX() + "," + chunk.getZ() +
                                " in " + world.getName());
                    }
                }
            }

            totalUnloaded += unloaded;

            if (unloaded > 0) {
                Logger.info("Unloaded " + unloaded + " chunks in " + world.getName());
            }
        }

        // Check max loaded chunks limit
        if (config.getMaxLoadedChunks() > 0 && totalLoaded > config.getMaxLoadedChunks()) {
            Logger.warning("Total loaded chunks (" + totalLoaded + ") exceeds limit (" +
                    config.getMaxLoadedChunks() + ")");
        }

        if (totalUnloaded > 0) {
            Logger.info("Total chunks unloaded: " + totalUnloaded + " (Total loaded: " + totalLoaded + ")");
        }
    }

    private void monitorChunks() {
        for (World world : Bukkit.getWorlds()) {
            if (!isWorldEnabled(world.getName())) continue;

            for (Chunk chunk : world.getLoadedChunks()) {
                String key = getChunkKey(chunk, world);

                ChunkData data = chunkDataMap.computeIfAbsent(key, k -> new ChunkData());
                data.entityCount = chunk.getEntities().length;
                data.tileEntityCount = chunk.getTileEntities().length;
                data.lastCheck = System.currentTimeMillis();

                // Mark problematic chunks
                if (data.entityCount > config.getMaxEntitiesPerChunk() * 2) {
                    if (!problematicChunks.contains(key)) {
                        problematicChunks.add(key);
                        Logger.warning("Problematic chunk detected at " + chunk.getX() +
                                "," + chunk.getZ() + " in " + world.getName() +
                                " with " + data.entityCount + " entities");
                    }
                } else {
                    problematicChunks.remove(key);
                }
            }
        }

        // Clean up old data
        long now = System.currentTimeMillis();
        chunkDataMap.entrySet().removeIf(entry ->
                now - entry.getValue().lastCheck > 60000
        );
    }

    public boolean shouldUnloadChunk(Chunk chunk, World world) {
        try {
            // Don't unload spawn chunks
            if (isSpawnChunk(chunk, world)) {
                return false;
            }

            // Don't unload chunks near players
            if (isNearPlayer(chunk, world)) {
                return false;
            }

            // Check if chunk is empty
            if (config.isUnloadEmpty()) {
                int entities = chunk.getEntities().length;
                int tileEntities = chunk.getTileEntities().length;

                return entities < 3 && tileEntities < 2;
            }

            return false;

        } catch (Exception e) {
            Logger.warning("Error checking chunk unload status: " + e.getMessage());
            return false;
        }
    }

    private boolean isSpawnChunk(Chunk chunk, World world) {
        try {
            Location spawn = world.getSpawnLocation();
            int spawnChunkX = spawn.getBlockX() >> 4;
            int spawnChunkZ = spawn.getBlockZ() >> 4;

            return Math.abs(chunk.getX() - spawnChunkX) <= 2 &&
                    Math.abs(chunk.getZ() - spawnChunkZ) <= 2;

        } catch (Exception e) {
            return true; // Safe default
        }
    }

    private boolean isNearPlayer(Chunk chunk, World world) {
        try {
            int unloadRadius = config.getUnloadRadius();

            for (Player player : world.getPlayers()) {
                Chunk playerChunk = player.getLocation().getChunk();
                int dx = Math.abs(playerChunk.getX() - chunk.getX());
                int dz = Math.abs(playerChunk.getZ() - chunk.getZ());

                if (dx <= unloadRadius && dz <= unloadRadius) {
                    return true;
                }
            }

            return false;

        } catch (Exception e) {
            return true; // Safe default
        }
    }

    public void onChunkLoad(Chunk chunk, World world) {
        try {
            String key = getChunkKey(chunk, world);
            chunkLoadTimes.put(key, System.currentTimeMillis());

            ChunkData data = new ChunkData();
            data.entityCount = chunk.getEntities().length;
            data.tileEntityCount = chunk.getTileEntities().length;
            data.lastCheck = System.currentTimeMillis();
            chunkDataMap.put(key, data);

        } catch (Exception e) {
            Logger.warning("Error processing chunk load: " + e.getMessage());
        }
    }

    public void onChunkUnload(Chunk chunk, World world) {
        try {
            String key = getChunkKey(chunk, world);
            chunkLoadTimes.remove(key);
            chunkDataMap.remove(key);
            problematicChunks.remove(key);

        } catch (Exception e) {
            Logger.warning("Error processing chunk unload: " + e.getMessage());
        }
    }

    public int unloadEmptyChunks() {
        int unloaded = 0;

        for (World world : Bukkit.getWorlds()) {
            if (!isWorldEnabled(world.getName())) continue;

            for (Chunk chunk : world.getLoadedChunks()) {
                if (shouldUnloadChunk(chunk, world)) {
                    try {
                        chunk.unload(true);
                        unloaded++;
                    } catch (Exception e) {
                        Logger.warning("Failed to unload chunk: " + e.getMessage());
                    }
                }
            }
        }

        return unloaded;
    }

    public ChunkStatistics getStatistics() {
        ChunkStatistics stats = new ChunkStatistics();

        for (World world : Bukkit.getWorlds()) {
            stats.totalChunks += world.getLoadedChunks().length;
        }

        stats.trackedChunks = chunkDataMap.size();
        stats.problematicChunks = problematicChunks.size();

        return stats;
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

    private String getChunkKey(Chunk chunk, World world) {
        return world.getName() + "_" + chunk.getX() + "_" + chunk.getZ();
    }

    public Set<String> getProblematicChunks() {
        return new HashSet<>(problematicChunks);
    }

    // Inner classes
    private static class ChunkData {
        int entityCount;
        int tileEntityCount;
        long lastCheck;
    }

    public static class ChunkStatistics {
        public int totalChunks;
        public int trackedChunks;
        public int problematicChunks;
    }
}