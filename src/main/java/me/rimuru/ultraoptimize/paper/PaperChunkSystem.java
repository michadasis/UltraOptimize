package me.rimuru.ultraoptimize.paper;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Advanced chunk system using Paper APIs
 * Supports intelligent chunk loading, tickets, and async operations
 */
public class PaperChunkSystem {

    // chunkLoadTimes only ever grows via loadChunkUrgently()/loadChunkAsync() -
    // there's no natural per-entry removal point (unlike activeTickets, which
    // is removed via removeChunkTicket()), so on a long-lived server every
    // distinct chunk ever async/urgently loaded would otherwise sit in this
    // map forever. Sweep out anything older than this on a timer instead.
    private static final long STALE_ENTRY_MILLIS = 600_000L; // 10 minutes
    private static final long CLEANUP_INTERVAL_TICKS = 20L * 300; // 5 minutes

    private final UltraOptimize plugin;
    private final Map<String, ChunkTicket> activeTickets;
    private final Map<String, Long> chunkLoadTimes;
    private final Set<String> priorityChunks;

    // Reflection cache for Paper methods
    private Method isChunkGeneratedMethod;
    private Method getChunkAtAsyncUrgentlyMethod;
    private Method addPluginChunkTicketMethod;
    private Method removePluginChunkTicketMethod;
    private boolean paperAPIsSupported;

    private BukkitTask cleanupTask;

    public PaperChunkSystem(UltraOptimize plugin) {
        this.plugin = plugin;
        this.activeTickets = new ConcurrentHashMap<>();
        this.chunkLoadTimes = new ConcurrentHashMap<>();
        this.priorityChunks = ConcurrentHashMap.newKeySet();

        initializePaperAPIs();
        startCleanupTask();
    }

    private void startCleanupTask() {
        cleanupTask = new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                chunkLoadTimes.entrySet().removeIf(entry -> now - entry.getValue() > STALE_ENTRY_MILLIS);
            }
        }.runTaskTimerAsynchronously(plugin, CLEANUP_INTERVAL_TICKS, CLEANUP_INTERVAL_TICKS);
    }

    private void initializePaperAPIs() {
        try {
            // Check for Paper chunk APIs
            Class<?> worldClass = World.class;

            // isChunkGenerated (Paper)
            try {
                isChunkGeneratedMethod = worldClass.getMethod("isChunkGenerated", int.class, int.class);
                Logger.info("Paper isChunkGenerated API detected");
            } catch (NoSuchMethodException e) {
                Logger.info("isChunkGenerated not available (requires Paper)");
            }

            // getChunkAtAsyncUrgently (Paper 1.14+)
            try {
                getChunkAtAsyncUrgentlyMethod = worldClass.getMethod("getChunkAtAsyncUrgently", int.class, int.class);
                Logger.info("Paper urgent chunk loading API detected");
            } catch (NoSuchMethodException e) {
                Logger.info("Urgent chunk loading not available");
            }

            // Plugin chunk tickets (Paper 1.13.2+)
            try {
                addPluginChunkTicketMethod = worldClass.getMethod("addPluginChunkTicket",
                        int.class, int.class, Plugin.class);
                removePluginChunkTicketMethod = worldClass.getMethod("removePluginChunkTicket",
                        int.class, int.class, Plugin.class);
                Logger.info("Paper chunk ticket API detected");
            } catch (NoSuchMethodException e) {
                Logger.info("Chunk ticket API not available");
            }

            paperAPIsSupported = (isChunkGeneratedMethod != null ||
                    getChunkAtAsyncUrgentlyMethod != null ||
                    addPluginChunkTicketMethod != null);

            if (paperAPIsSupported) {
                Logger.info("Paper advanced chunk system initialized successfully");
            } else {
                Logger.warning("Paper APIs not detected - using standard Spigot methods");
            }

        } catch (Exception e) {
            Logger.warning("Failed to initialize Paper chunk APIs: " + e.getMessage());
            paperAPIsSupported = false;
        }
    }

    /**
     * Check if a chunk is generated without loading it
     */
    public boolean isChunkGenerated(World world, int chunkX, int chunkZ) {
        if (isChunkGeneratedMethod != null) {
            try {
                return (boolean) isChunkGeneratedMethod.invoke(world, chunkX, chunkZ);
            } catch (Exception e) {
                Logger.warning("Error checking chunk generation: " + e.getMessage());
            }
        }

        // Fallback: assume generated if loaded
        return world.isChunkLoaded(chunkX, chunkZ);
    }

    /**
     * Load chunk with high priority (urgent loading)
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Chunk> loadChunkUrgently(World world, int chunkX, int chunkZ) {
        String key = getChunkKey(world, chunkX, chunkZ);
        priorityChunks.add(key);

        if (getChunkAtAsyncUrgentlyMethod != null) {
            try {
                CompletableFuture<Chunk> future = (CompletableFuture<Chunk>)
                        getChunkAtAsyncUrgentlyMethod.invoke(world, chunkX, chunkZ);

                // Paper completes this future on an unspecified (often
                // non-main) thread. Hop back to the main thread before
                // touching the map/set below or handing the chunk to
                // whatever the caller chains next, since Bukkit API use
                // requires the main thread. (No current caller relies on
                // this, but the future returned here must be safe by
                // construction for whoever eventually does.)
                CompletableFuture<Chunk> result = new CompletableFuture<>();
                future.whenComplete((chunk, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    priorityChunks.remove(key);
                    if (error != null) {
                        result.completeExceptionally(error);
                        return;
                    }
                    chunkLoadTimes.put(key, System.currentTimeMillis());
                    Logger.debug("Urgently loaded chunk: " + key);
                    result.complete(chunk);
                }));
                return result;

            } catch (Exception e) {
                Logger.warning("Error with urgent chunk loading: " + e.getMessage());
                // The reflective invoke failed before any future existed to
                // remove this key on completion - without this, every chunk
                // that hits this path leaks its key in priorityChunks forever.
                priorityChunks.remove(key);
            }
        }

        // Fallback to standard async loading
        return loadChunkAsync(world, chunkX, chunkZ);
    }

    /**
     * Standard async chunk loading
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Chunk> loadChunkAsync(World world, int chunkX, int chunkZ) {
        try {
            Method getChunkAtAsync = World.class.getMethod("getChunkAtAsync", int.class, int.class);
            CompletableFuture<Chunk> future = (CompletableFuture<Chunk>)
                    getChunkAtAsync.invoke(world, chunkX, chunkZ);

            String key = getChunkKey(world, chunkX, chunkZ);
            // See loadChunkUrgently: hop to the main thread before completing,
            // since Paper completes these futures on an unspecified thread.
            CompletableFuture<Chunk> result = new CompletableFuture<>();
            future.whenComplete((chunk, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    result.completeExceptionally(error);
                    return;
                }
                chunkLoadTimes.put(key, System.currentTimeMillis());
                result.complete(chunk);
            }));
            return result;

        } catch (Exception e) {
            Logger.warning("Async chunk loading failed: " + e.getMessage());

            // Sync fallback
            return CompletableFuture.completedFuture(world.getChunkAt(chunkX, chunkZ));
        }
    }

    /**
     * Add a chunk ticket to keep chunk loaded
     */
    public boolean addChunkTicket(World world, int chunkX, int chunkZ, TicketType type, int duration) {
        if (addPluginChunkTicketMethod == null) {
            Logger.debug("Chunk tickets not supported - chunk may unload naturally");
            return false;
        }

        try {
            String key = getChunkKey(world, chunkX, chunkZ);

            // Add the ticket
            boolean success = (boolean) addPluginChunkTicketMethod.invoke(
                    world, chunkX, chunkZ, plugin);

            if (success) {
                ChunkTicket ticket = new ChunkTicket(world.getName(), chunkX, chunkZ, type, duration);
                activeTickets.put(key, ticket);
                Logger.debug("Added chunk ticket: " + key + " (" + type + ")");

                // Schedule removal if duration is set
                if (duration > 0) {
                    scheduleTicketRemoval(world, chunkX, chunkZ, duration);
                }
            }

            return success;

        } catch (Exception e) {
            Logger.warning("Failed to add chunk ticket: " + e.getMessage());
            return false;
        }
    }

    /**
     * Remove a chunk ticket
     */
    public boolean removeChunkTicket(World world, int chunkX, int chunkZ) {
        if (removePluginChunkTicketMethod == null) {
            return false;
        }

        try {
            String key = getChunkKey(world, chunkX, chunkZ);

            boolean success = (boolean) removePluginChunkTicketMethod.invoke(
                    world, chunkX, chunkZ, plugin);

            if (success) {
                activeTickets.remove(key);
                Logger.debug("Removed chunk ticket: " + key);
            }

            return success;

        } catch (Exception e) {
            Logger.warning("Failed to remove chunk ticket: " + e.getMessage());
            return false;
        }
    }

    /**
     * Schedule automatic ticket removal
     */
    private void scheduleTicketRemoval(World world, int chunkX, int chunkZ, int durationTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            removeChunkTicket(world, chunkX, chunkZ);
        }, durationTicks);
    }

    /**
     * Keep spawn chunks loaded with tickets
     */
    public void ticketSpawnChunks(World world, int radius) {
        if (!isPaperSupported()) {
            Logger.debug("Spawn chunk tickets not available on this server");
            return;
        }

        int spawnX = world.getSpawnLocation().getBlockX() >> 4;
        int spawnZ = world.getSpawnLocation().getBlockZ() >> 4;

        int ticketed = 0;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (addChunkTicket(world, spawnX + x, spawnZ + z, TicketType.SPAWN, -1)) {
                    ticketed++;
                }
            }
        }

        Logger.info("Added " + ticketed + " spawn chunk tickets for " + world.getName());
    }

    /**
     * Remove all chunk tickets for a world
     */
    public void clearWorldTickets(World world) {
        List<String> toRemove = new ArrayList<>();

        for (Map.Entry<String, ChunkTicket> entry : activeTickets.entrySet()) {
            if (entry.getValue().worldName.equals(world.getName())) {
                toRemove.add(entry.getKey());
            }
        }

        for (String key : toRemove) {
            ChunkTicket ticket = activeTickets.get(key);
            removeChunkTicket(world, ticket.chunkX, ticket.chunkZ);
        }

        Logger.info("Cleared " + toRemove.size() + " chunk tickets for " + world.getName());
    }

    /**
     * Clear all active tickets
     */
    public void clearAllTickets() {
        for (ChunkTicket ticket : activeTickets.values()) {
            World world = Bukkit.getWorld(ticket.worldName);
            if (world != null) {
                removeChunkTicket(world, ticket.chunkX, ticket.chunkZ);
            }
        }
        activeTickets.clear();
        Logger.info("Cleared all chunk tickets");
    }

    /**
     * Get chunk loading statistics
     */
    public ChunkSystemStats getStatistics() {
        ChunkSystemStats stats = new ChunkSystemStats();
        stats.activeTickets = activeTickets.size();
        stats.trackedChunks = chunkLoadTimes.size();
        stats.priorityChunks = priorityChunks.size();
        stats.paperSupported = paperAPIsSupported;

        // Count tickets by type
        for (ChunkTicket ticket : activeTickets.values()) {
            stats.ticketsByType.merge(ticket.type, 1, Integer::sum);
        }

        return stats;
    }

    /**
     * Batch load chunks async
     */
    public CompletableFuture<List<Chunk>> loadChunksBatch(World world, List<ChunkCoord> coords, boolean urgent) {
        List<CompletableFuture<Chunk>> futures = new ArrayList<>();

        for (ChunkCoord coord : coords) {
            if (urgent) {
                futures.add(loadChunkUrgently(world, coord.x, coord.z));
            } else {
                futures.add(loadChunkAsync(world, coord.x, coord.z));
            }
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<Chunk> chunks = new ArrayList<>();
                    for (CompletableFuture<Chunk> future : futures) {
                        try {
                            chunks.add(future.get());
                        } catch (Exception e) {
                            Logger.warning("Failed to get chunk from future: " + e.getMessage());
                        }
                    }
                    return chunks;
                });
    }

    public boolean isPaperSupported() {
        return paperAPIsSupported;
    }

    private String getChunkKey(World world, int chunkX, int chunkZ) {
        return world.getName() + "_" + chunkX + "_" + chunkZ;
    }

    public void shutdown() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        clearAllTickets();
        chunkLoadTimes.clear();
        priorityChunks.clear();
    }

    // Inner classes
    public static class ChunkTicket {
        final String worldName;
        final int chunkX;
        final int chunkZ;
        final TicketType type;
        final int duration;
        final long created;

        ChunkTicket(String worldName, int chunkX, int chunkZ, TicketType type, int duration) {
            this.worldName = worldName;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.type = type;
            this.duration = duration;
            this.created = System.currentTimeMillis();
        }
    }

    public enum TicketType {
        SPAWN,          // Spawn area chunks
        PRELOAD,        // Pre-loaded chunks
        PERSISTENT,     // Chunks that should stay loaded
        TEMPORARY       // Temporary loading
    }

    public static class ChunkCoord {
        public final int x;
        public final int z;

        public ChunkCoord(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }

    public static class ChunkSystemStats {
        public int activeTickets;
        public int trackedChunks;
        public int priorityChunks;
        public boolean paperSupported;
        public Map<TicketType, Integer> ticketsByType = new HashMap<>();
    }
}