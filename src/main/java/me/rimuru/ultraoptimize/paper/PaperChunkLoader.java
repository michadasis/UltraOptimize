package me.rimuru.ultraoptimize.paper;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * CPU-optimized chunk loading for Paper
 * Lightweight monitoring with minimal overhead
 */
public class PaperChunkLoader implements Listener {

    private final UltraOptimize plugin;

    // Lightweight tracking - only keep recent data
    private final Map<String, Long> recentLoads;
    private final Set<String> currentlyLoading;

    // Paper-specific detection
    private Method isNewChunkMethod;
    private boolean paperEventsSupported;

    // Minimal statistics - aggregated only
    private volatile int totalLoads;
    private volatile int asyncLoads;
    private volatile int syncLoads;
    private volatile int generatedChunks;
    private volatile long totalLoadTime;

    // Sampling for performance - only track 1 in N loads
    private static final int SAMPLE_RATE = 10;
    private int loadCounter = 0;

    private BukkitTask cleanupTask;

    public PaperChunkLoader(UltraOptimize plugin) {
        this.plugin = plugin;
        this.recentLoads = new ConcurrentHashMap<>(256); // Small fixed capacity
        this.currentlyLoading = ConcurrentHashMap.newKeySet();

        initializePaperEvents();
        startLightweightCleanup();
    }

    private void initializePaperEvents() {
        try {
            isNewChunkMethod = ChunkLoadEvent.class.getMethod("isNewChunk");
            paperEventsSupported = true;
            Logger.info("Paper chunk events: ENABLED");
        } catch (NoSuchMethodException e) {
            paperEventsSupported = false;
            Logger.info("Paper chunk events: DISABLED");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        // Quick increment without heavy processing
        totalLoads++;

        // Sample-based tracking to reduce CPU load
        if (++loadCounter % SAMPLE_RATE != 0) {
            return; // Skip most events
        }

        try {
            Chunk chunk = event.getChunk();
            World world = event.getWorld();
            String key = getChunkKey(world.getName(), chunk.getX(), chunk.getZ());

            // Check if newly generated (Paper only)
            if (paperEventsSupported && isNewChunkMethod != null) {
                try {
                    if ((boolean) isNewChunkMethod.invoke(event)) {
                        generatedChunks++;
                    }
                } catch (Exception ignored) {
                    // Silently fail to avoid spam
                }
            }

            // Track if async
            if (currentlyLoading.remove(key)) {
                asyncLoads++;

                // Calculate duration only for async loads
                Long startTime = recentLoads.remove(key);
                if (startTime != null) {
                    long duration = System.currentTimeMillis() - startTime;
                    totalLoadTime += duration;

                    // Only log extremely slow loads
                    if (duration > 5000) { // 5 seconds
                        Logger.warning("Very slow chunk load: " + duration + "ms at " +
                                chunk.getX() + "," + chunk.getZ() + " in " + world.getName());
                    }
                }
            } else {
                syncLoads++;
            }

        } catch (Exception e) {
            // Minimal error logging
            Logger.debug("Chunk load tracking error: " + e.getMessage());
        }
    }

    /**
     * Lightweight async chunk loading
     */
    public void loadChunkAsync(World world, int chunkX, int chunkZ, Consumer<Chunk> callback) {
        // Check if already loaded - fastest path
        if (world.isChunkLoaded(chunkX, chunkZ)) {
            if (callback != null) {
                callback.accept(world.getChunkAt(chunkX, chunkZ));
            }
            return;
        }

        String key = getChunkKey(world.getName(), chunkX, chunkZ);

        // Prevent duplicate loads
        if (currentlyLoading.contains(key)) {
            return;
        }

        currentlyLoading.add(key);
        recentLoads.put(key, System.currentTimeMillis());

        // Async load with minimal overhead
        loadChunkAsyncInternal(world, chunkX, chunkZ, callback);
    }

    @SuppressWarnings("unchecked")
    private void loadChunkAsyncInternal(World world, int chunkX, int chunkZ, Consumer<Chunk> callback) {
        try {
            Method getChunkAtAsync = World.class.getMethod("getChunkAtAsync", int.class, int.class);
            CompletableFuture<Chunk> future = (CompletableFuture<Chunk>)
                    getChunkAtAsync.invoke(world, chunkX, chunkZ);

            // Only add callback if provided
            if (callback != null) {
                future.thenAccept(chunk -> {
                    // Execute callback on main thread
                    Bukkit.getScheduler().runTask(plugin, () -> callback.accept(chunk));
                });
            }

        } catch (NoSuchMethodException e) {
            // Fallback to sync on main thread
            String key = getChunkKey(world.getName(), chunkX, chunkZ);
            currentlyLoading.remove(key);

            Bukkit.getScheduler().runTask(plugin, () -> {
                Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                if (callback != null) {
                    callback.accept(chunk);
                }
            });

        } catch (Exception e) {
            Logger.warning("Chunk async load failed: " + e.getMessage());
            String key = getChunkKey(world.getName(), chunkX, chunkZ);
            currentlyLoading.remove(key);
            recentLoads.remove(key);
        }
    }

    /**
     * Batch load with throttling to prevent CPU spike
     */
    public void loadChunksBatch(World world, List<ChunkCoord> coords, Consumer<List<Chunk>> callback) {
        if (coords.isEmpty()) {
            if (callback != null) {
                callback.accept(new ArrayList<>());
            }
            return;
        }

        List<Chunk> loadedChunks = Collections.synchronizedList(new ArrayList<>());
        int totalChunks = coords.size();
        int[] loadedCount = {0};

        // Throttle batch loading - load in small groups
        final int BATCH_SIZE = 5;

        new BukkitRunnable() {
            int index = 0;

            @Override
            public void run() {
                int endIndex = Math.min(index + BATCH_SIZE, coords.size());

                for (int i = index; i < endIndex; i++) {
                    ChunkCoord coord = coords.get(i);
                    loadChunkAsync(world, coord.x, coord.z, chunk -> {
                        loadedChunks.add(chunk);
                        loadedCount[0]++;

                        // Check if all done
                        if (loadedCount[0] >= totalChunks && callback != null) {
                            callback.accept(loadedChunks);
                        }
                    });
                }

                index = endIndex;

                // Cancel when done
                if (index >= coords.size()) {
                    this.cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 2L); // Small delay between batches
    }

    /**
     * Lightweight statistics
     */
    public LoaderStats getStatistics() {
        LoaderStats stats = new LoaderStats();
        stats.totalLoads = totalLoads;
        stats.asyncLoads = asyncLoads;
        stats.syncLoads = syncLoads;
        stats.generatedChunks = generatedChunks;
        stats.currentlyLoading = currentlyLoading.size();
        stats.paperSupported = paperEventsSupported;

        // Calculate average if we have data
        int trackedAsyncLoads = asyncLoads > 0 ? asyncLoads : 1;
        stats.averageLoadTime = totalLoadTime / trackedAsyncLoads;

        // Note: Due to sampling, these are estimates
        stats.estimatedValues = true;

        return stats;
    }

    /**
     * Check if chunk is loading
     */
    public boolean isChunkLoading(World world, int chunkX, int chunkZ) {
        return currentlyLoading.contains(getChunkKey(world.getName(), chunkX, chunkZ));
    }

    /**
     * Lightweight cleanup - runs infrequently
     */
    private void startLightweightCleanup() {
        cleanupTask = new BukkitRunnable() {
            @Override
            public void run() {
                cleanupStaleData();
            }
        }.runTaskTimerAsynchronously(plugin, 12000L, 12000L); // Every 10 minutes
    }

    /**
     * Remove stale tracking data
     */
    private void cleanupStaleData() {
        try {
            long now = System.currentTimeMillis();
            long staleThreshold = 600000; // 10 minutes

            // Clean up stuck loads
            recentLoads.entrySet().removeIf(entry ->
                    now - entry.getValue() > staleThreshold);

            // Clean up stuck loading markers
            if (currentlyLoading.size() > 100) {
                Logger.debug("Clearing stuck loading markers: " + currentlyLoading.size());
                currentlyLoading.clear();
            }

        } catch (Exception e) {
            Logger.debug("Cleanup error: " + e.getMessage());
        }
    }

    /**
     * Reset statistics
     */
    public void resetStatistics() {
        totalLoads = 0;
        asyncLoads = 0;
        syncLoads = 0;
        generatedChunks = 0;
        totalLoadTime = 0;
        loadCounter = 0;
        Logger.info("Chunk loader stats reset");
    }

    /**
     * Shutdown cleanly
     */
    public void shutdown() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }

        currentlyLoading.clear();
        recentLoads.clear();

        Logger.info("Paper chunk loader stopped");
    }

    // Optimized helper
    private String getChunkKey(String worldName, int x, int z) {
        return worldName + "_" + x + "_" + z;
    }

    // Minimal inner classes

    public static class ChunkCoord {
        public final int x;
        public final int z;

        public ChunkCoord(int x, int z) {
            this.x = x;
            this.z = z;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ChunkCoord)) return false;
            ChunkCoord that = (ChunkCoord) o;
            return x == that.x && z == that.z;
        }

        @Override
        public int hashCode() {
            return 31 * x + z; // Faster than Objects.hash
        }
    }

    public static class LoaderStats {
        public int totalLoads;
        public int asyncLoads;
        public int syncLoads;
        public int generatedChunks;
        public int currentlyLoading;
        public long averageLoadTime;
        public boolean paperSupported;
        public boolean estimatedValues; // Due to sampling

        @Override
        public String toString() {
            return String.format("ChunkLoader[total=%d, async=%d, sync=%d, gen=%d, loading=%d, avgTime=%dms%s]",
                    totalLoads, asyncLoads, syncLoads, generatedChunks, currentlyLoading, averageLoadTime,
                    estimatedValues ? " (estimated)" : "");
        }
    }
}