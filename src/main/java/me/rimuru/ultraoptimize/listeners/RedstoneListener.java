package me.rimuru.ultraoptimize.listeners;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.config.ConfigManager;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RedstoneListener implements Listener {

    // Events are counted within this rolling window, then the count resets.
    // Without a window, a block toggled >100 times over a long uptime would
    // be suppressed forever instead of only during an actual burst.
    private static final long WINDOW_MILLIS = 1000L;
    private static final int MAX_EVENTS_PER_WINDOW = 100;
    private static final long STALE_ENTRY_MILLIS = 60_000L;

    private final UltraOptimize plugin;
    private final ConfigManager config;
    private final Map<String, RedstoneActivity> redstoneEvents;
    private BukkitTask cleanupTask;

    public RedstoneListener(UltraOptimize plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.redstoneEvents = new ConcurrentHashMap<>();
    }

    public void start() {
        cleanupTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::cleanupStaleEntries, 20L * 60, 20L * 60);
    }

    public void shutdown() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }
        redstoneEvents.clear();
    }

    @EventHandler
    public void onRedstone(BlockRedstoneEvent event) {
        if (!config.isOptimizeRedstone()) return;

        try {
            if (event.getBlock() == null || event.getBlock().getLocation() == null) return;

            String key = event.getBlock().getLocation().toString();
            long now = System.currentTimeMillis();

            RedstoneActivity activity = redstoneEvents.computeIfAbsent(key, k -> new RedstoneActivity());

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
        redstoneEvents.entrySet().removeIf(entry -> now - entry.getValue().lastSeen > STALE_ENTRY_MILLIS);
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