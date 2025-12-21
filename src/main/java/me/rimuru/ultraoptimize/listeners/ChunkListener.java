package me.rimuru.ultraoptimize.listeners;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.config.ConfigManager;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Hopper;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

public class ChunkListener implements Listener {

    private final UltraOptimize plugin;
    private final ConfigManager config;

    public ChunkListener(UltraOptimize plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        try {
            Chunk chunk = event.getChunk();
            World world = event.getWorld();

            if (chunk == null || world == null) return;

            // Track chunk load
            plugin.getChunkManager().onChunkLoad(chunk, world);

            // Check for excessive hoppers
            if (config.isOptimizeHoppers()) {
                checkHopperCount(chunk, world);
            }

        } catch (Exception e) {
            Logger.warning("Error handling chunk load: " + e.getMessage());
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        try {
            Chunk chunk = event.getChunk();
            World world = event.getWorld();

            if (chunk == null || world == null) return;

            // Track chunk unload
            plugin.getChunkManager().onChunkUnload(chunk, world);

        } catch (Exception e) {
            Logger.warning("Error handling chunk unload: " + e.getMessage());
        }
    }

    private void checkHopperCount(Chunk chunk, World world) {
        try {
            int hoppers = 0;
            BlockState[] tileEntities = chunk.getTileEntities();

            if (tileEntities == null) return;

            for (BlockState state : tileEntities) {
                if (state instanceof Hopper) {
                    hoppers++;
                }
            }

            int max = config.getMaxHoppersPerChunk();
            if (hoppers > max) {
                Logger.warning("Chunk at " + chunk.getX() + "," + chunk.getZ() +
                        " in " + world.getName() + " has " + hoppers +
                        " hoppers (max: " + max + ")");
            }
        } catch (Exception e) {
            Logger.warning("Error checking hopper count: " + e.getMessage());
        }
    }
}