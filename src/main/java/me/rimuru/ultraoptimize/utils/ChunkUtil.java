package me.rimuru.ultraoptimize.utils;

import org.bukkit.Chunk;
import org.bukkit.block.BlockState;

import java.lang.reflect.Method;

/**
 * Chunk inspection helpers that avoid the snapshot cost of the plain
 * {@code Chunk#getTileEntities()}.
 *
 * <p>The no-argument {@code getTileEntities()} is {@code getTileEntities(true)}:
 * it constructs a snapshot {@link BlockState} copy of every chest, hopper, sign,
 * furnace, spawner and banner in the chunk. Calling it once per loaded chunk on
 * a 5-second timer, as this plugin used to, is by a wide margin its largest
 * allocation source. When all we want is a count, ask for the live view instead.
 */
public final class ChunkUtil {

    private static Method getTileEntitiesNoSnapshot;
    private static boolean resolved;

    private ChunkUtil() {
    }

    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            getTileEntitiesNoSnapshot = Chunk.class.getMethod("getTileEntities", boolean.class);
        } catch (NoSuchMethodException e) {
            getTileEntitiesNoSnapshot = null;
            Logger.info("Chunk#getTileEntities(boolean) unavailable - falling back to snapshot reads");
        }
    }

    /**
     * Counts tile entities in a chunk without building snapshots where the
     * server supports it.
     */
    @SuppressWarnings("unchecked")
    public static int countTileEntities(Chunk chunk) {
        if (chunk == null) return 0;

        resolve();

        if (getTileEntitiesNoSnapshot != null) {
            try {
                Object result = getTileEntitiesNoSnapshot.invoke(chunk, false);
                if (result instanceof java.util.Collection) {
                    return ((java.util.Collection<BlockState>) result).size();
                }
                if (result instanceof BlockState[]) {
                    return ((BlockState[]) result).length;
                }
            } catch (Exception ignored) {
                // fall through to the snapshot path
            }
        }

        BlockState[] states = chunk.getTileEntities();
        return states == null ? 0 : states.length;
    }
}
