package me.rimuru.ultraoptimize.listeners;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.config.ConfigManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerMoveListener implements Listener {

    private final UltraOptimize plugin;
    private final ConfigManager config;

    public PlayerMoveListener(UltraOptimize plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!config.isChunkPreloadingEnabled()) return;
        if (event.getPlayer() == null) return;

        // Only check if player changed chunks
        if (event.getFrom().getChunk() != event.getTo().getChunk()) {
            plugin.getChunkPreloader().onPlayerMove(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!config.isChunkPreloadingEnabled()) return;
        if (event.getPlayer() == null) return;

        // Preload chunks around new player
        plugin.getChunkPreloader().queuePreloadForPlayer(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Cleanup happens automatically in ChunkPreloader
    }
}