package me.rimuru.ultraoptimize.managers;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.config.ConfigManager;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Preloads chunks around each world's spawn once at startup.
 *
 * <p>Worth understanding before enabling it on a small heap: loaded chunks are
 * the single largest consumer of memory on a Minecraft server, and this loads
 * (2r+1)^2 of them per world whether or not anyone is near spawn. With
 * chunks.aggressive-unload also on, most of them get unloaded again on the next
 * unload pass, so you pay the disk read, the memory spike, and the save-on-
 * unload for nothing. Off by default for that reason.
 */
public class ChunkPreloader {

    private final UltraOptimize plugin;
    private final ConfigManager config;

    private int chunksPreloaded;
    private boolean isPreloading;
    private static Boolean supportsAsync = null;

    private BukkitTask preloadTask;

    public ChunkPreloader(UltraOptimize plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.chunksPreloaded = 0;
        this.isPreloading = false;

        checkAsyncSupport();
    }

    private void checkAsyncSupport() {
        if (supportsAsync == null) {
            try {
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
    private CompletableFuture<Chunk> loadChunkAsync(World world, int chunkX, int chunkZ, boolean generate) {
        try {
            try {
                Method method = World.class.getMethod("getChunkAtAsync", int.class, int.class, boolean.class);
                return (CompletableFuture<Chunk>) method.invoke(world, chunkX, chunkZ, generate);
            } catch (NoSuchMethodException e) {
                Method method = World.class.getMethod("getChunkAtAsync", int.class, int.class);
                return (CompletableFuture<Chunk>) method.invoke(world, chunkX, chunkZ);
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

        Bukkit.getScheduler().runTaskLater(plugin, this::preloadSpawnChunks, 60L);
    }

    public void shutdown() {
        if (preloadTask != null) {
            preloadTask.cancel();
            preloadTask = null;
        }
        isPreloading = false;
        Logger.info("ChunkPreloader shut down (total chunks preloaded: " + chunksPreloaded + ")");
    }

    public void restart() {
        shutdown();
        chunksPreloaded = 0;
        start();
    }

    private void preloadSpawnChunks() {
        if (isPreloading) {
            Logger.warning("Chunk preloading is already in progress!");
            return;
        }

        isPreloading = true;
        chunksPreloaded = 0;

        Logger.info("Starting spawn chunk preloading...");

        preloadTask = new BukkitRunnable() {
            int worldIndex = 0;
            int currentChunkIndex = 0;
            int totalChunks = 0;
            int spawnChunkX, spawnChunkZ;
            final int radius = config.getPreloadRadius();

            // Incremental spiral state. getSpiralCoordinates() used to replay
            // the whole spiral from the origin on every single call, making the
            // pass O(n^2) in the number of chunks for no reason.
            int spiralX, spiralZ, spiralDX, spiralDZ;

            @Override
            public void run() {
                try {
                    List<World> worlds = Bukkit.getWorlds();

                    if (worldIndex >= worlds.size()) {
                        finishPreloading();
                        this.cancel();
                        preloadTask = null;
                        return;
                    }

                    World world = worlds.get(worldIndex);

                    if (!isWorldEnabled(world.getName())) {
                        nextWorld();
                        return;
                    }

                    if (currentChunkIndex == 0) {
                        Location spawn = world.getSpawnLocation();
                        spawnChunkX = spawn.getBlockX() >> 4;
                        spawnChunkZ = spawn.getBlockZ() >> 4;
                        totalChunks = (radius * 2 + 1) * (radius * 2 + 1);
                        resetSpiral();
                        Logger.info("Preloading chunks for world: " + world.getName());
                    }

                    int chunksPerTick = config.getPreloadChunksPerTick();
                    int attempted = 0;

                    while (attempted < chunksPerTick && currentChunkIndex < totalChunks) {
                        int chunkX, chunkZ;

                        if (config.isSpiralPattern()) {
                            chunkX = spawnChunkX + spiralX;
                            chunkZ = spawnChunkZ + spiralZ;
                            advanceSpiral();
                        } else {
                            int size = radius * 2 + 1;
                            chunkX = spawnChunkX + (currentChunkIndex % size) - radius;
                            chunkZ = spawnChunkZ + (currentChunkIndex / size) - radius;
                        }

                        loadChunk(world, chunkX, chunkZ);
                        currentChunkIndex++;
                        attempted++;
                    }

                    if (currentChunkIndex >= totalChunks) {
                        Logger.info("Completed preloading for " + world.getName() +
                                " (" + currentChunkIndex + " chunks)");
                        nextWorld();
                    }

                } catch (Exception e) {
                    Logger.severe("Error during chunk preloading: " + e.getMessage());
                    e.printStackTrace();
                    this.cancel();
                    preloadTask = null;
                    isPreloading = false;
                }
            }

            private void nextWorld() {
                worldIndex++;
                currentChunkIndex = 0;
            }

            private void resetSpiral() {
                spiralX = 0;
                spiralZ = 0;
                spiralDX = 0;
                spiralDZ = -1;
            }

            private void advanceSpiral() {
                if ((spiralX == spiralZ)
                        || ((spiralX < 0) && (spiralX == -spiralZ))
                        || ((spiralX > 0) && (spiralX == 1 - spiralZ))) {
                    int temp = spiralDX;
                    spiralDX = -spiralDZ;
                    spiralDZ = temp;
                }
                spiralX += spiralDX;
                spiralZ += spiralDZ;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /**
     * Loads one chunk. Counters are only incremented once the chunk is actually
     * resident - the old version counted an async request as a completed load
     * immediately and then counted it a second time in the callback.
     */
    private void loadChunk(World world, int chunkX, int chunkZ) {
        try {
            if (world.isChunkLoaded(chunkX, chunkZ)) {
                return;
            }

            if (config.isAsyncChunks() && Boolean.TRUE.equals(supportsAsync)) {
                CompletableFuture<Chunk> future =
                        loadChunkAsync(world, chunkX, chunkZ, config.isGenerateChunks());

                if (future != null) {
                    future.whenComplete((chunk, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (error == null && chunk != null) {
                            chunksPreloaded++;
                            plugin.getStatisticsManager().incrementChunksPreloaded(1);
                        }
                    }));
                    return;
                }
            }

            if (config.isGenerateChunks()) {
                world.getChunkAt(chunkX, chunkZ);
            } else {
                world.loadChunk(chunkX, chunkZ, false);
            }

            chunksPreloaded++;
            plugin.getStatisticsManager().incrementChunksPreloaded(1);

        } catch (Exception e) {
            Logger.warning("Failed to load chunk at " + chunkX + ", " + chunkZ + ": " + e.getMessage());
        }
    }

    private void finishPreloading() {
        isPreloading = false;
        Logger.info("Chunk preloading completed! Total chunks preloaded: " + chunksPreloaded);

        if (config.isNotifyPreloading()) {
            String message = "§a[UltraOptimize] §7Chunk preloading completed! Loaded " +
                    chunksPreloaded + " chunks.";
            for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("ultraoptimize.notify")) {
                    player.sendMessage(message);
                }
            }
        }
    }

    private boolean isWorldEnabled(String worldName) {
        List<String> enabled = config.getEnabledWorlds();
        List<String> excluded = config.getExcludedWorlds();

        if (!excluded.isEmpty() && excluded.contains(worldName)) {
            return false;
        }

        return enabled.isEmpty() || enabled.contains(worldName);
    }

    public PreloadStatistics getStatistics() {
        PreloadStatistics stats = new PreloadStatistics();
        stats.chunksPreloaded = chunksPreloaded;
        stats.isPreloading = isPreloading;
        return stats;
    }

    public boolean isPreloading() {
        return isPreloading;
    }

    public static class PreloadStatistics {
        public int chunksPreloaded;
        public boolean isPreloading;
    }
}
