package me.rimuru.ultraoptimize.paper;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Advanced chunk system using Paper APIs
 * Supports intelligent chunk loading, tickets, and async operations
 */
public class PaperChunkSystem {

    private final UltraOptimize plugin;
    private final Map<String, ChunkTicket> activeTickets;

    // Reflection cache for Paper methods
    private Method isChunkGeneratedMethod;
    private Method addPluginChunkTicketMethod;
    private Method removePluginChunkTicketMethod;
    private boolean paperAPIsSupported;

    public PaperChunkSystem(UltraOptimize plugin) {
        this.plugin = plugin;
        this.activeTickets = new ConcurrentHashMap<>();

        initializePaperAPIs();
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
    /**
     * Pins the chunks around a world's spawn with plugin chunk tickets.
     *
     * <p>These are added with duration -1, i.e. permanently: the chunks can
     * never unload for as long as the plugin is enabled, and they tick. That is
     * a real memory commitment - roughly (2r+1)^2 chunks per world - so it is
     * off by default. It also fights ChunkManager's aggressive unloader, which
     * will keep trying and failing to unload them on every pass.
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
            // Null-checked: another thread may have removed the entry between
            // building the key list above and getting here.
            if (ticket == null) continue;
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
        stats.paperSupported = paperAPIsSupported;

        // Count tickets by type
        for (ChunkTicket ticket : activeTickets.values()) {
            stats.ticketsByType.merge(ticket.type, 1, Integer::sum);
        }

        return stats;
    }

    public boolean isPaperSupported() {
        return paperAPIsSupported;
    }

    private String getChunkKey(World world, int chunkX, int chunkZ) {
        return world.getName() + "_" + chunkX + "_" + chunkZ;
    }

    public void shutdown() {
        clearAllTickets();
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

    public static class ChunkSystemStats {
        public int activeTickets;
        public boolean paperSupported;
        public Map<TicketType, Integer> ticketsByType = new HashMap<>();
    }
}