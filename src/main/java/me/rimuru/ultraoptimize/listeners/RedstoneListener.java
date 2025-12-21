package me.rimuru.ultraoptimize.listeners;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.config.ConfigManager;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RedstoneListener implements Listener {

    private final UltraOptimize plugin;
    private final ConfigManager config;
    private final Map<String, Integer> redstoneEvents;

    public RedstoneListener(UltraOptimize plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.redstoneEvents = new ConcurrentHashMap<>();
    }

    @EventHandler
    public void onRedstone(BlockRedstoneEvent event) {
        if (!config.isOptimizeRedstone()) return;

        try {
            if (event.getBlock() == null || event.getBlock().getLocation() == null) return;

            String key = event.getBlock().getLocation().toString();
            int events = redstoneEvents.getOrDefault(key, 0);

            // Prevent excessive redstone updates (100 per location)
            if (events > 100) {
                event.setNewCurrent(event.getOldCurrent());
                return;
            }

            redstoneEvents.put(key, events + 1);

        } catch (Exception e) {
            Logger.warning("Error handling redstone event: " + e.getMessage());
        }
    }

    public void clearEvents() {
        redstoneEvents.clear();
    }
}