package me.rimuru.ultraoptimize.paper;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

import java.lang.reflect.Method;

/**
 * Passive chunk-load counters for Paper, surfaced in {@code /uo paper stats}.
 *
 * <p>This used to also expose {@code loadChunkAsync}/{@code loadChunksBatch}
 * helpers with their own tracking maps and a dedicated 10-minute cleanup task.
 * Nothing in the plugin ever called them - {@link ChunkPreloader} has always
 * done its own async loading directly - so those maps sat permanently empty
 * and the cleanup task swept nothing, forever, for zero benefit. Removed
 * along with the async/sync split in {@link #onChunkLoad}, which only meant
 * something in relation to loads that helper kicked off.
 */
public class PaperChunkLoader implements Listener {

    // Only 1 in SAMPLE_RATE loads does the isNewChunk reflection call below;
    // the rest just increment a counter. ChunkLoadEvent fires on essentially
    // every player movement across a chunk boundary, so this is one of the
    // highest-frequency events on the server.
    private static final int SAMPLE_RATE = 10;

    // Paper-specific detection
    private Method isNewChunkMethod;
    private boolean paperEventsSupported;

    private volatile int totalLoads;
    private volatile int generatedChunks;
    private int loadCounter = 0;

    public PaperChunkLoader(UltraOptimize plugin) {
        initializePaperEvents();
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
        totalLoads++;

        if (++loadCounter % SAMPLE_RATE != 0) {
            return; // Skip most events
        }

        if (paperEventsSupported && isNewChunkMethod != null) {
            try {
                if ((boolean) isNewChunkMethod.invoke(event)) {
                    generatedChunks++;
                }
            } catch (Exception ignored) {
                // Silently fail to avoid spam
            }
        }
    }

    /**
     * Chunk load statistics. total/generated are sampled at 1-in-{@value
     * #SAMPLE_RATE} for generatedChunks specifically (totalLoads counts every
     * load; only the generated-chunk check is sampled), so treat generatedChunks
     * as an estimate.
     */
    public LoaderStats getStatistics() {
        LoaderStats stats = new LoaderStats();
        stats.totalLoads = totalLoads;
        stats.generatedChunks = generatedChunks;
        stats.paperSupported = paperEventsSupported;
        stats.estimatedValues = true;
        return stats;
    }

    /**
     * Reset statistics
     */
    public void resetStatistics() {
        totalLoads = 0;
        generatedChunks = 0;
        loadCounter = 0;
        Logger.info("Chunk loader stats reset");
    }

    /**
     * Shutdown cleanly
     */
    public void shutdown() {
        // Unregister this instance from Bukkit's event system. Without this,
        // reinitializing on /uo reload (which constructs and registers a new
        // PaperChunkLoader) leaves this one permanently subscribed to
        // ChunkLoadEvent, leaking the instance and duplicating event handling.
        HandlerList.unregisterAll(this);

        Logger.info("Paper chunk loader stopped");
    }

    public static class LoaderStats {
        public int totalLoads;
        public int generatedChunks;
        public boolean paperSupported;
        public boolean estimatedValues; // Due to sampling

        @Override
        public String toString() {
            return String.format("ChunkLoader[total=%d, gen=%d%s]",
                    totalLoads, generatedChunks, estimatedValues ? " (estimated)" : "");
        }
    }
}
