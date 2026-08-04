package me.rimuru.ultraoptimize.managers;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.config.ConfigManager;
import me.rimuru.ultraoptimize.utils.ChunkUtil;
import me.rimuru.ultraoptimize.utils.Keys;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkManager {

    // Was 100 ticks (5 seconds). Every pass walks every loaded chunk in every
    // world, so on a server with a few hundred loaded chunks this was running
    // thousands of chunk inspections a minute to populate a diagnostic map.
    private static final long MONITOR_INTERVAL_TICKS = 1200L; // 60 seconds
    private static final long CHUNK_DATA_TTL_MILLIS = 300_000L; // 5 minutes

    private final UltraOptimize plugin;
    private final ConfigManager config;

    // Keyed by world UID, then packed chunk coordinate. The old
    // "world_x_z" String keys allocated on every chunk load and every
    // monitor pass. chunkLoadTimes has been dropped entirely - it was written
    // on every ChunkLoadEvent and never read by anything.
    private final Map<UUID, Map<Long, ChunkData>> chunkDataMap;
    private final Map<UUID, Set<Long>> problematicChunks;

    private BukkitTask unloadTask;
    private BukkitTask monitorTask;

    public ChunkManager(UltraOptimize plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.chunkDataMap = new ConcurrentHashMap<>();
        this.problematicChunks = new ConcurrentHashMap<>();
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
            unloadTask = null;
        }
        if (monitorTask != null) {
            monitorTask.cancel();
            monitorTask = null;
        }
        chunkDataMap.clear();
        problematicChunks.clear();
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
        }.runTaskTimer(plugin, MONITOR_INTERVAL_TICKS, MONITOR_INTERVAL_TICKS);
    }

    private void performAggressiveUnload() {
        int totalUnloaded = 0;
        int totalLoaded = 0;

        for (World world : Bukkit.getWorlds()) {
            if (!isWorldEnabled(world.getName())) continue;

            // Player chunk coordinates are gathered once per world instead of
            // per candidate chunk - isNearPlayer() used to allocate a Location
            // and a Chunk lookup for every player for every loaded chunk.
            long[] playerChunks = collectPlayerChunkCoords(world);

            Chunk[] loadedChunks = world.getLoadedChunks();
            totalLoaded += loadedChunks.length;

            int unloaded = 0;
            for (Chunk chunk : loadedChunks) {
                if (shouldUnloadChunk(chunk, world, playerChunks)) {
                    try {
                        if (chunk.unload(true)) {
                            unloaded++;
                            forgetChunk(world, chunk.getX(), chunk.getZ());
                        }
                    } catch (Exception e) {
                        Logger.warning("Failed to unload chunk at " +
                                chunk.getX() + "," + chunk.getZ() +
                                " in " + world.getName());
                    }
                }
            }

            totalUnloaded += unloaded;
        }

        if (config.getMaxLoadedChunks() > 0 && totalLoaded > config.getMaxLoadedChunks()) {
            Logger.warning("Total loaded chunks (" + totalLoaded + ") exceeds limit (" +
                    config.getMaxLoadedChunks() + ")");
        }

        if (totalUnloaded > 0) {
            plugin.getStatisticsManager().incrementChunksUnloaded(totalUnloaded);
            Logger.info("Unloaded " + totalUnloaded + " chunks (total loaded: " + totalLoaded + ")");
        }
    }

    private long[] collectPlayerChunkCoords(World world) {
        List<Player> players = world.getPlayers();
        long[] coords = new long[players.size()];

        for (int i = 0; i < players.size(); i++) {
            Location loc = players.get(i).getLocation();
            coords[i] = Keys.chunk(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
        }

        return coords;
    }

    private void monitorChunks() {
        long now = System.currentTimeMillis();

        for (World world : Bukkit.getWorlds()) {
            if (!isWorldEnabled(world.getName())) continue;

            UUID worldId = world.getUID();
            Map<Long, ChunkData> worldData =
                    chunkDataMap.computeIfAbsent(worldId, k -> new ConcurrentHashMap<>());
            Set<Long> worldProblems =
                    problematicChunks.computeIfAbsent(worldId, k -> ConcurrentHashMap.newKeySet());

            for (Chunk chunk : world.getLoadedChunks()) {
                long key = Keys.chunk(chunk);

                ChunkData data = worldData.computeIfAbsent(key, k -> new ChunkData());
                data.entityCount = chunk.getEntities().length;
                // Non-snapshot count - see ChunkUtil.
                data.tileEntityCount = ChunkUtil.countTileEntities(chunk);
                data.lastCheck = now;

                if (data.entityCount > config.getMaxEntitiesPerChunk() * 2) {
                    if (worldProblems.add(key)) {
                        Logger.warning("Problematic chunk detected at " + chunk.getX() +
                                "," + chunk.getZ() + " in " + world.getName() +
                                " with " + data.entityCount + " entities");
                    }
                } else {
                    worldProblems.remove(key);
                }
            }
        }

        for (Map<Long, ChunkData> worldData : chunkDataMap.values()) {
            worldData.entrySet().removeIf(entry -> now - entry.getValue().lastCheck > CHUNK_DATA_TTL_MILLIS);
        }
    }

    public boolean shouldUnloadChunk(Chunk chunk, World world, long[] playerChunks) {
        try {
            if (isSpawnChunk(chunk, world)) {
                return false;
            }

            if (isNearPlayer(chunk, playerChunks)) {
                return false;
            }

            if (config.isUnloadEmpty()) {
                int entities = chunk.getEntities().length;
                if (entities >= 3) return false;

                return ChunkUtil.countTileEntities(chunk) < 2;
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

    private boolean isNearPlayer(Chunk chunk, long[] playerChunks) {
        int unloadRadius = config.getUnloadRadius();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();

        for (long packed : playerChunks) {
            int px = (int) (packed >> 32);
            int pz = (int) packed;

            if (Math.abs(px - chunkX) <= unloadRadius && Math.abs(pz - chunkZ) <= unloadRadius) {
                return true;
            }
        }

        return false;
    }

    public void onChunkUnload(Chunk chunk, World world) {
        try {
            forgetChunk(world, chunk.getX(), chunk.getZ());
        } catch (Exception e) {
            Logger.warning("Error processing chunk unload: " + e.getMessage());
        }
    }

    private void forgetChunk(World world, int chunkX, int chunkZ) {
        UUID worldId = world.getUID();
        long key = Keys.chunk(chunkX, chunkZ);

        Map<Long, ChunkData> worldData = chunkDataMap.get(worldId);
        if (worldData != null) worldData.remove(key);

        Set<Long> worldProblems = problematicChunks.get(worldId);
        if (worldProblems != null) worldProblems.remove(key);
    }

    public int unloadEmptyChunks() {
        int unloaded = 0;

        for (World world : Bukkit.getWorlds()) {
            if (!isWorldEnabled(world.getName())) continue;

            long[] playerChunks = collectPlayerChunkCoords(world);

            for (Chunk chunk : world.getLoadedChunks()) {
                if (shouldUnloadChunk(chunk, world, playerChunks)) {
                    try {
                        if (chunk.unload(true)) {
                            unloaded++;
                            forgetChunk(world, chunk.getX(), chunk.getZ());
                        }
                    } catch (Exception e) {
                        Logger.warning("Failed to unload chunk: " + e.getMessage());
                    }
                }
            }
        }

        if (unloaded > 0) {
            plugin.getStatisticsManager().incrementChunksUnloaded(unloaded);
        }

        return unloaded;
    }

    public ChunkStatistics getStatistics() {
        ChunkStatistics stats = new ChunkStatistics();

        for (World world : Bukkit.getWorlds()) {
            stats.totalChunks += world.getLoadedChunks().length;
        }

        for (Map<Long, ChunkData> worldData : chunkDataMap.values()) {
            stats.trackedChunks += worldData.size();
        }
        for (Set<Long> worldProblems : problematicChunks.values()) {
            stats.problematicChunks += worldProblems.size();
        }

        return stats;
    }

    private boolean isWorldEnabled(String worldName) {
        List<String> enabled = config.getEnabledWorlds();
        List<String> excluded = config.getExcludedWorlds();

        if (!excluded.isEmpty() && excluded.contains(worldName)) {
            return false;
        }

        return enabled.isEmpty() || enabled.contains(worldName);
    }

    public Set<String> getProblematicChunks() {
        Set<String> result = new HashSet<>();

        for (Map.Entry<UUID, Set<Long>> entry : problematicChunks.entrySet()) {
            World world = Bukkit.getWorld(entry.getKey());
            String worldName = world != null ? world.getName() : entry.getKey().toString();

            for (long key : entry.getValue()) {
                result.add(worldName + "_" + (int) (key >> 32) + "_" + (int) key);
            }
        }

        return result;
    }

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
