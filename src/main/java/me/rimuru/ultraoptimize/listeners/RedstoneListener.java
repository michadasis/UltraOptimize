package me.rimuru.ultraoptimize.listeners;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.config.ConfigManager;
import me.rimuru.ultraoptimize.utils.Keys;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Suppresses redstone updates for a block that exceeds MAX_EVENTS_PER_WINDOW
 * within a rolling window.
 *
 * <p><b>Note this does not make redstone cheaper.</b> The block still ticks;
 * setNewCurrent() only makes the resulting update a no-op. What it does do is
 * stop runaway circuits from cascading - at the cost of silently stalling
 * clocks and jamming piston doors once a build crosses the threshold. It is off
 * by default for that reason.
 */
public class RedstoneListener implements Listener {

    private static final long WINDOW_MILLIS = 1000L;
    private static final int MAX_EVENTS_PER_WINDOW = 100;
    private static final long STALE_ENTRY_MILLIS = 60_000L;
    private static final long CLEANUP_INTERVAL_TICKS = 20L * 60;

    private final UltraOptimize plugin;
    private final ConfigManager config;

    // Keyed by world UID, then by packed block coordinate. The old key was
    // Location#toString() - a ~90 character String allocated for every single
    // redstone event, which one observer clock produces thousands of per second.
    private final Map<UUID, Map<Long, RedstoneActivity>> redstoneEvents;

    private BukkitTask cleanupTask;

    public RedstoneListener(UltraOptimize plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.redstoneEvents = new ConcurrentHashMap<>();
    }

    public void start() {
        cleanupTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::cleanupStaleEntries, CLEANUP_INTERVAL_TICKS, CLEANUP_INTERVAL_TICKS);
    }

    public void shutdown() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        redstoneEvents.clear();
    }

    @EventHandler
    public void onRedstone(BlockRedstoneEvent event) {
        if (!config.isOptimizeRedstone()) return;

        try {
            Block block = event.getBlock();
            if (block == null) return;

            World world = block.getWorld();
            if (world == null) return;

            Map<Long, RedstoneActivity> worldMap = redstoneEvents.computeIfAbsent(
                    world.getUID(), k -> new ConcurrentHashMap<>());

            long key = Keys.block(block.getX(), block.getY(), block.getZ());
            long now = System.currentTimeMillis();

            RedstoneActivity activity = worldMap.computeIfAbsent(key, k -> new RedstoneActivity());

            synchronized (activity) {
                if (now - activity.windowStart > WINDOW_MILLIS) {
                    activity.windowStart = now;
                    activity.count = 0;
                }

                activity.lastSeen = now;

                if (activity.count >= MAX_EVENTS_PER_WINDOW) {
                    event.setNewCurrent(event.getOldCurrent());
                    return;
                }

                activity.count++;
            }

        } catch (Exception e) {
            Logger.warning("Error handling redstone event: " + e.getMessage());
        }
    }

    private void cleanupStaleEntries() {
        long now = System.currentTimeMillis();

        for (Map<Long, RedstoneActivity> worldMap : redstoneEvents.values()) {
            worldMap.entrySet().removeIf(entry -> now - entry.getValue().lastSeen > STALE_ENTRY_MILLIS);
        }
        redstoneEvents.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    public void clearEvents() {
        redstoneEvents.clear();
    }

    private static class RedstoneActivity {
        long windowStart = System.currentTimeMillis();
        long lastSeen = System.currentTimeMillis();
        int count = 0;
    }
}
