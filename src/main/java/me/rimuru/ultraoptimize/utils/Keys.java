package me.rimuru.ultraoptimize.utils;

import org.bukkit.Chunk;
import org.bukkit.Location;

/**
 * Packed primitive keys for maps on hot event paths.
 *
 * <p>The previous code built a {@code String} key per event - e.g.
 * {@code "block_" + world.getName() + "_" + x + "_" + y + "_" + z}, or worse,
 * {@code location.toString()}. On events that fire thousands of times per
 * second (hopper transfers, redstone updates, item spawns) that is the single
 * largest source of garbage the plugin produces, which is exactly what a 1GB
 * heap cannot absorb.
 *
 * <p>These keys are per-world: callers hold a map keyed by world UID whose
 * values are keyed by these packed longs. Packing the world in as well is not
 * possible without giving up coordinate range.
 */
public final class Keys {

    private Keys() {
    }

    /**
     * Packs block coordinates into a single long: 26 bits of X, 26 bits of Z,
     * 12 bits of Y. That covers +/-33.5 million blocks horizontally (the world
     * border maxes out at 30 million) and -2048..2047 vertically.
     */
    public static long block(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38)
                | ((long) (z & 0x3FFFFFF) << 12)
                | ((y + 2048) & 0xFFF);
    }

    public static long block(Location location) {
        return block(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    /** Packs chunk coordinates into a single long. */
    public static long chunk(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static long chunk(Chunk chunk) {
        return chunk(chunk.getX(), chunk.getZ());
    }
}
