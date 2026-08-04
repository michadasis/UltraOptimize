package me.rimuru.ultraoptimize.paper;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Region file reporting and cleanup.
 *
 * <p><b>What "incremental saving" actually was.</b> The old code looked up
 * {@code World#save(boolean)} by reflection and, on the strength of finding it,
 * called itself Paper-accelerated. No such overload exists in the Bukkit or
 * Paper API - only the no-argument {@code World#save()} - so the lookup always
 * failed and every run fell through to a full, synchronous, main-thread save of
 * every loaded chunk in every world. On a 30 second timer. On a 1GB host with a
 * shared disk that is a multi-hundred-millisecond freeze twice a minute,
 * forever.
 *
 * <p>There is no API for an off-thread or genuinely incremental world save, so
 * the honest fix is: default the feature off, be explicit in the log about what
 * it does when it is on, and hold the minimum interval well away from 30s.
 */
public class RegionFileOptimizer {

    private static final long RESCAN_INTERVAL_TICKS = 20L * 600; // 10 minutes

    private final UltraOptimize plugin;

    // Rebuilt wholesale by the periodic async rescan. The previous code paired
    // this with a lastAccessTimes map that nothing ever wrote to, so the
    // "cache cleanup" task ran every 60 seconds and removed exactly nothing
    // while this map grew at startup and was never pruned.
    private final Map<String, RegionFileInfo> regionFiles;
    private final AtomicLong totalRegionSize;
    private final AtomicInteger totalRegions;

    private BukkitTask saveTask;
    private BukkitTask rescanTask;

    private boolean incrementalSaving;
    private int saveInterval;
    private boolean autoRemoveEmptyRegions;

    public RegionFileOptimizer(UltraOptimize plugin) {
        this.plugin = plugin;
        this.regionFiles = new ConcurrentHashMap<>();
        this.totalRegionSize = new AtomicLong(0);
        this.totalRegions = new AtomicInteger(0);

        loadConfiguration();
    }

    private void loadConfiguration() {
        this.incrementalSaving = plugin.getConfigManager().isPaperIncrementalSaving();
        this.saveInterval = plugin.getConfigManager().getPaperSaveInterval();
        this.autoRemoveEmptyRegions = plugin.getConfigManager().isPaperRemoveEmptyRegions();

        Logger.info("Region file optimizer configuration loaded:");
        Logger.info("  Periodic world save: " + (incrementalSaving ? "ENABLED" : "DISABLED"));
        Logger.info("  Save interval: " + saveInterval + "s");
        Logger.info("  Auto remove empty: " + (autoRemoveEmptyRegions ? "ENABLED" : "DISABLED"));
    }

    public void start() {
        if (!plugin.getConfigManager().isPaperRegionFilesEnabled()) {
            Logger.info("Region file optimization disabled in config");
            return;
        }

        // Region scanning is pure disk I/O with no Bukkit API involved, so it
        // belongs off the main thread. Doing it in the constructor stat'd every
        // .mca file in every world before the server finished enabling, and
        // repeated it on every /uo reload.
        scheduleRescan();

        if (incrementalSaving) {
            startPeriodicSave();
        }

        Logger.info("Region file optimizer started");
    }

    public void shutdown() {
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (rescanTask != null) {
            rescanTask.cancel();
            rescanTask = null;
        }

        // No save on shutdown. This ran on every /uo reload, and the server
        // saves its own worlds when it stops.
        Logger.info("Region file optimizer stopped");
    }

    private void startPeriodicSave() {
        Logger.warning("paper.region-files.incremental-saving is ON: this performs a FULL, " +
                "SYNCHRONOUS world save every " + saveInterval + "s on the main thread. " +
                "There is no API for an incremental or async save. Expect a pause each time; " +
                "on low-memory or slow-disk hosts, turn this off.");

        saveTask = new BukkitRunnable() {
            @Override
            public void run() {
                performWorldSave();
            }
        }.runTaskTimer(plugin, 20L * saveInterval, 20L * saveInterval);
    }

    private void scheduleRescan() {
        rescanTask = new BukkitRunnable() {
            @Override
            public void run() {
                scanWorldRegions();
            }
        }.runTaskTimerAsynchronously(plugin, 100L, RESCAN_INTERVAL_TICKS);
    }

    private void performWorldSave() {
        try {
            for (World world : Bukkit.getWorlds()) {
                world.save();
            }
        } catch (Exception e) {
            Logger.warning("Error during world save: " + e.getMessage());
        }
    }

    /**
     * Scans region files on disk. Safe to call from an async task - it touches
     * only java.io and World#getWorldFolder(), which is an immutable path.
     */
    private void scanWorldRegions() {
        try {
            Map<String, RegionFileInfo> scanned = new HashMap<>();
            long size = 0;
            int count = 0;

            for (World world : new ArrayList<>(Bukkit.getWorlds())) {
                File regionDir = getRegionDirectory(world);
                if (regionDir == null || !regionDir.exists()) continue;

                File[] files = regionDir.listFiles((dir, name) ->
                        name.endsWith(".mca") || name.endsWith(".mcr"));

                if (files == null) continue;

                for (File file : files) {
                    RegionFileInfo info = new RegionFileInfo();
                    info.worldName = world.getName();
                    info.fileName = file.getName();
                    info.size = file.length();
                    info.lastModified = file.lastModified();

                    scanned.put(world.getName() + "/" + file.getName(), info);
                    size += info.size;
                    count++;
                }
            }

            regionFiles.clear();
            regionFiles.putAll(scanned);
            totalRegionSize.set(size);
            totalRegions.set(count);

            Logger.debug("Scanned " + count + " region files (" + formatBytes(size) + ")");

        } catch (Exception e) {
            Logger.warning("Error scanning region files: " + e.getMessage());
        }
    }

    /**
     * Finds and removes region files that contain no chunks.
     *
     * <p>This deletes world data. It is gated behind
     * paper.region-files.remove-empty-regions, which is off by default.
     */
    public int removeEmptyRegions(World world) {
        File regionDir = getRegionDirectory(world);
        if (regionDir == null || !regionDir.exists()) {
            return 0;
        }

        int removed = 0;
        File[] files = regionDir.listFiles((dir, name) -> name.endsWith(".mca"));

        if (files == null) return 0;

        for (File file : files) {
            try {
                if (isRegionEmpty(file)) {
                    if (file.delete()) {
                        removed++;
                        Logger.debug("Removed empty region file: " + file.getName());
                    }
                }
            } catch (Exception e) {
                Logger.warning("Error checking region file " + file.getName() + ": " + e.getMessage());
            }
        }

        if (removed > 0) {
            Logger.info("Removed " + removed + " empty region files from " + world.getName());
        }

        return removed;
    }

    /**
     * Reads a region file's chunk location table to decide whether it holds any
     * chunks.
     *
     * <p>The old version called {@code raf.read(header)} and ignored the return
     * value. A short read leaves the rest of the buffer zeroed, every offset
     * then reads as 0, the region is declared empty, and the file is deleted -
     * permanent world data loss. readFully() plus a length guard closes that.
     */
    private boolean isRegionEmpty(File file) throws IOException {
        // A region file with any chunk in it is at least a 4KB location table
        // plus a 4KB timestamp table.
        if (file.length() < 8192) {
            return true;
        }

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] header = new byte[4096];
            raf.readFully(header);

            for (int i = 0; i < 1024; i++) {
                int offset = (header[i * 4] & 0xFF) << 16 |
                        (header[i * 4 + 1] & 0xFF) << 8 |
                        (header[i * 4 + 2] & 0xFF);

                if (offset > 0) {
                    return false; // Found a chunk
                }
            }

            return true;
        }
    }

    private File getRegionDirectory(World world) {
        File worldFolder = world.getWorldFolder();

        File regionDir = new File(worldFolder, "region");
        if (regionDir.exists()) return regionDir;

        regionDir = new File(worldFolder, "DIM-1/region"); // Nether
        if (regionDir.exists()) return regionDir;

        regionDir = new File(worldFolder, "DIM1/region"); // End
        if (regionDir.exists()) return regionDir;

        return null;
    }

    public RegionStats getStatistics() {
        RegionStats stats = new RegionStats();
        stats.totalRegions = totalRegions.get();
        stats.totalSize = totalRegionSize.get();
        stats.incrementalSaving = incrementalSaving;

        for (RegionFileInfo info : regionFiles.values()) {
            stats.sizeByWorld.merge(info.worldName, info.size, Long::sum);
            stats.countByWorld.merge(info.worldName, 1, Integer::sum);
        }

        return stats;
    }

    /**
     * Full synchronous save of every world. Only called before empty-region
     * deletion, so that what is on disk reflects the live world.
     */
    public void flushRegionCache() {
        Logger.info("Saving all worlds before region cleanup (this will pause the server briefly)...");
        performWorldSave();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static class RegionFileInfo {
        String worldName;
        String fileName;
        long size;
        long lastModified;
    }

    public static class RegionStats {
        public int totalRegions;
        public long totalSize;
        public boolean incrementalSaving;
        public Map<String, Long> sizeByWorld = new HashMap<>();
        public Map<String, Integer> countByWorld = new HashMap<>();
    }
}
