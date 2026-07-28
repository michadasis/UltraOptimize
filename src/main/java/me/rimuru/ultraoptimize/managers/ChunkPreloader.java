package me.rimuru.ultraoptimize.managers;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.config.ConfigManager;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

public class ChunkPreloader {

    private final UltraOptimize plugin;
    private final ConfigManager config;

    private int chunksPreloaded;
    private boolean isPreloading;
    private int preloadedChunksCount;
    private final Queue<ChunkLocation> preloadQueue;
    private static Boolean supportsAsync = null; // Cache async support check

    // The in-progress preload sweep, if any. Without tracking this, shutdown()/
    // restart() (called on every /uo reload) had no way to stop a preload pass
    // that was still mid-flight: the runnable only ever cancelled itself once
    // it finished walking every world, so reloading while spawn chunks were
    // still loading left the old sweep running forever alongside the new one
    // start() schedules, each reload stacking another duplicate pass.
    private BukkitTask preloadTask;

    public ChunkPreloader(UltraOptimize plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.chunksPreloaded = 0;
        this.isPreloading = false;
        this.preloadedChunksCount = 0;
        this.preloadQueue = new LinkedList<>();

        // Check async support on initialization
        checkAsyncSupport();
    }

    private void checkAsyncSupport() {
        if (supportsAsync == null) {
            try {
                // Check for the basic async method (Paper/Spigot 1.13+)
                World.class.getMethod("getChunkAtAsync", int.class, int.class);
                supportsAsync = true;
                Logger.info("Async chunk loading is supported");
            } catch (NoSuchMethodException e) {
                supportsAsync = false;
                Logger.info("Async chunk loading is not supported, using sync mode");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<org.bukkit.Chunk> loadChunkAsync(World world, int chunkX, int chunkZ, boolean generate) {
        try {
            // Try Paper's method first (has generate parameter)
            try {
                java.lang.reflect.Method method = World.class.getMethod("getChunkAtAsync", int.class, int.class, boolean.class);
                return (CompletableFuture<org.bukkit.Chunk>) method.invoke(world, chunkX, chunkZ, generate);
            } catch (NoSuchMethodException e) {
                // Fall back to basic Spigot method (no generate parameter)
                java.lang.reflect.Method method = World.class.getMethod("getChunkAtAsync", int.class, int.class);
                return (CompletableFuture<org.bukkit.Chunk>) method.invoke(world, chunkX, chunkZ);
            }
        } catch (Exception e) {
            Logger.warning("Failed to invoke async chunk loading: " + e.getMessage());
            return null;
        }
    }

    public void start() {
        if (!config.isChunkPreloadingEnabled()) {
            Logger.info("Chunk preloading is disabled");
            return;
        }

        Logger.info("ChunkPreloader starting...");
        Logger.info("Preload radius: " + config.getPreloadRadius() + " chunks");

        // Start preloading after a delay
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            preloadSpawnChunks();
        }, 60L); // Wait 3 seconds after startup
    }

    public void shutdown() {
        if (preloadTask != null) {
            preloadTask.cancel();
            preloadTask = null;
        }
        isPreloading = false;
        preloadQueue.clear();
        Logger.info("ChunkPreloader shut down");
        Logger.info("Total chunks preloaded: " + chunksPreloaded);
    }

    public void restart() {
        shutdown();
        chunksPreloaded = 0;
        preloadedChunksCount = 0;
        start();
    }

    private void preloadSpawnChunks() {
        if (isPreloading) {
            Logger.warning("Chunk preloading is already in progress!");
            return;
        }

        isPreloading = true;
        chunksPreloaded = 0;
        preloadedChunksCount = 0;

        Logger.info("Starting spawn chunk preloading...");

        preloadTask = new BukkitRunnable() {
            int worldIndex = 0;
            int currentChunkIndex = 0;
            int totalChunks = 0;
            Location spawnLocation;
            int spawnChunkX, spawnChunkZ;
            int radius = config.getPreloadRadius();

            @Override
            public void run() {
                try {
                    // Get world list
                    World[] worlds = Bukkit.getWorlds().toArray(new World[0]);

                    // Check if we're done with all worlds
                    if (worldIndex >= worlds.length) {
                        finishPreloading();
                        this.cancel();
                        preloadTask = null;
                        return;
                    }

                    World world = worlds[worldIndex];

                    // Skip if world is not enabled
                    if (!isWorldEnabled(world.getName())) {
                        worldIndex++;
                        currentChunkIndex = 0;
                        return;
                    }

                    // Initialize for new world
                    if (currentChunkIndex == 0) {
                        spawnLocation = world.getSpawnLocation();
                        spawnChunkX = spawnLocation.getBlockX() >> 4;
                        spawnChunkZ = spawnLocation.getBlockZ() >> 4;
                        totalChunks = (radius * 2 + 1) * (radius * 2 + 1);
                        Logger.info("Preloading chunks for world: " + world.getName());
                    }

                    // Load chunks in batches
                    int chunksPerTick = config.getPreloadChunksPerTick();
                    int loaded = 0;

                    while (loaded < chunksPerTick && currentChunkIndex < totalChunks) {
                        // Calculate chunk coordinates in spiral or square pattern
                        int[] coords = getChunkCoordinates(currentChunkIndex, radius);
                        int chunkX = spawnChunkX + coords[0];
                        int chunkZ = spawnChunkZ + coords[1];

                        // Load the chunk
                        if (loadChunk(world, chunkX, chunkZ)) {
                            chunksPreloaded++;
                            preloadedChunksCount++;
                            loaded++;
                        }

                        currentChunkIndex++;
                    }

                    // Check if done with current world
                    if (currentChunkIndex >= totalChunks) {
                        Logger.info("Completed preloading for " + world.getName() +
                                " (" + currentChunkIndex + " chunks)");
                        worldIndex++;
                        currentChunkIndex = 0;
                    }

                } catch (Exception e) {
                    Logger.severe("Error during chunk preloading: " + e.getMessage());
                    e.printStackTrace();
                    this.cancel();
                    preloadTask = null;
                    isPreloading = false;
                }
            }
        }.runTaskTimer(plugin, 0L, 1L); // Run every tick
    }

    private int[] getChunkCoordinates(int index, int radius) {
        if (config.isSpiralPattern()) {
            return getSpiralCoordinates(index, radius);
        } else {
            return getSquareCoordinates(index, radius);
        }
    }

    private int[] getSpiralCoordinates(int index, int radius) {
        int x = 0, z = 0;
        int dx = 0, dz = -1;
        int maxSteps = (radius * 2 + 1) * (radius * 2 + 1);

        for (int i = 0; i <= index && i < maxSteps; i++) {
            if (i == index) {
                return new int[]{x, z};
            }

            if ((x == z) || ((x < 0) && (x == -z)) || ((x > 0) && (x == 1 - z))) {
                int temp = dx;
                dx = -dz;
                dz = temp;
            }

            x += dx;
            z += dz;
        }

        return new int[]{0, 0};
    }

    private int[] getSquareCoordinates(int index, int radius) {
        int size = radius * 2 + 1;
        int x = (index % size) - radius;
        int z = (index / size) - radius;
        return new int[]{x, z};
    }

    private boolean loadChunk(World world, int chunkX, int chunkZ) {
        try {
            // Check if chunk is already loaded
            if (world.isChunkLoaded(chunkX, chunkZ)) {
                return false;
            }

            // Load chunk based on configuration and server support
            if (config.isAsyncChunks() && supportsAsync) {
                // Use async loading with reflection for compatibility
                CompletableFuture<org.bukkit.Chunk> future = loadChunkAsync(world, chunkX, chunkZ, config.isGenerateChunks());

                if (future != null) {
                    future.thenAccept(chunk -> {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            plugin.getStatisticsManager().incrementChunksPreloaded(1);
                        });
                    });
                } else {
                    // Fallback to sync if async failed
                    if (config.isGenerateChunks()) {
                        world.getChunkAt(chunkX, chunkZ);
                    } else {
                        world.loadChunk(chunkX, chunkZ, false);
                    }
                    plugin.getStatisticsManager().incrementChunksPreloaded(1);
                }
            } else {
                // Use synchronous loading
                if (config.isGenerateChunks()) {
                    world.getChunkAt(chunkX, chunkZ);
                } else {
                    world.loadChunk(chunkX, chunkZ, false);
                }
                plugin.getStatisticsManager().incrementChunksPreloaded(1);
            }

            return true;

        } catch (Exception e) {
            Logger.warning("Failed to load chunk at " + chunkX + ", " + chunkZ + ": " + e.getMessage());
            return false;
        }
    }

    private void finishPreloading() {
        isPreloading = false;
        Logger.info("=================================");
        Logger.info("Chunk preloading completed!");
        Logger.info("Total chunks preloaded: " + chunksPreloaded);
        Logger.info("=================================");

        // Broadcast to admins if enabled
        if (config.isNotifyPreloading()) {
            String message = "§a[UltraOptimize] §7Chunk preloading completed! Loaded " +
                    chunksPreloaded + " chunks.";
            Bukkit.getOnlinePlayers().forEach(player -> {
                if (player.hasPermission("ultraoptimize.notify")) {
                    player.sendMessage(message);
                }
            });
        }
    }

    private boolean isWorldEnabled(String worldName) {
        java.util.List<String> enabled = config.getEnabledWorlds();
        java.util.List<String> excluded = config.getExcludedWorlds();

        if (!excluded.isEmpty() && excluded.contains(worldName)) {
            return false;
        }

        if (!enabled.isEmpty() && !enabled.contains(worldName)) {
            return false;
        }

        return true;
    }

    // Methods for player-based preloading (currently unused but referenced)
    public void onPlayerMove(Player player) {
        // Placeholder for future implementation
    }

    public void queuePreloadForPlayer(Player player) {
        // Placeholder for future implementation
    }

    public PreloadStatistics getStatistics() {
        PreloadStatistics stats = new PreloadStatistics();
        stats.chunksPreloaded = chunksPreloaded;
        stats.isPreloading = isPreloading;
        stats.preloadedChunksCount = preloadedChunksCount;
        stats.queueSize = preloadQueue.size();
        return stats;
    }

    public boolean isPreloading() {
        return isPreloading;
    }

    // Inner classes
    public static class PreloadStatistics {
        public int chunksPreloaded;
        public boolean isPreloading;
        public int preloadedChunksCount;
        public int queueSize;
    }

    private static class ChunkLocation {
        final String worldName;
        final int x;
        final int z;

        ChunkLocation(String worldName, int x, int z) {
            this.worldName = worldName;
            this.x = x;
            this.z = z;
        }
    }
}