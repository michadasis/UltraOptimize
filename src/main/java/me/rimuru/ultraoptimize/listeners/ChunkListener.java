package me.rimuru.ultraoptimize.listeners;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;

public class ChunkListener implements Listener {

    private final UltraOptimize plugin;

    public ChunkListener(UltraOptimize plugin) {
        this.plugin = plugin;
    }

    // The hopper-count check that used to live here called
    // Chunk#getTileEntities() on every single chunk load - building a snapshot
    // BlockState for every container in the chunk - purely to print a console
    // warning. Chunk loads are one of the highest-frequency things a moving
    // player causes, so it has been removed outright. ChunkManager's periodic
    // sweep reports the same information without paying that cost per load.

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        try {
            Chunk chunk = event.getChunk();
            World world = event.getWorld();

            if (chunk == null || world == null) return;

            plugin.getChunkManager().onChunkUnload(chunk, world);

        } catch (Exception e) {
            Logger.warning("Error handling chunk unload: " + e.getMessage());
        }
    }
}
