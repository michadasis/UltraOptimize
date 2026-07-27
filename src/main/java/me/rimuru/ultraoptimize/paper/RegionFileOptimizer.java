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
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Region file optimization for Paper servers
 * Manages region file cache, incremental saving, and file cleanup
 */
public class RegionFileOptimizer {

    private final UltraOptimize plugin;
    private final Map<String, RegionFileInfo> regionFiles;
    private final Map<String, Long> lastAccessTimes;
    private final AtomicLong totalRegionSize;

    private BukkitTask optimizationTask;
    private BukkitTask cleanupTask;

    // Paper API methods
    private Method saveIncrementallyMethod;
    private boolean paperRegionSupported;
    private boolean defragRiskWarned;

    // Configuration - loaded from config instead of hardcoded
    private boolean incrementalSaving;
    private int saveInterval; // in seconds
    private int cacheCleanupInterval; // in seconds
    private long regionCacheTimeout;
    private boolean autoDefragment;
    private boolean autoRemoveEmptyRegions;

    // Statistics
    private int totalRegions;
    private int optimizedRegions;
    private long bytesFreed;
    private long lastOptimizationTime;

    public RegionFileOptimizer(UltraOptimize plugin) {
        this.plugin = plugin;
        this.regionFiles = new ConcurrentHashMap<>();
        this.lastAccessTimes = new ConcurrentHashMap<>();
        this.totalRegionSize = new AtomicLong(0);

        // Load configuration
        loadConfiguration();

        initializePaperRegionAPI();
        scanWorldRegions();
    }

    private void loadConfiguration() {
        // Load from config instead of hardcoding
        this.incrementalSaving = plugin.getConfigManager().isPaperIncrementalSaving();
        this.saveInterval = plugin.getConfigManager().getPaperSaveInterval();
        this.cacheCleanupInterval = plugin.getConfigManager().getPaperCacheCleanupInterval();
        this.regionCacheTimeout = plugin.getConfigManager().getPaperCacheTimeout();
        this.autoDefragment = plugin.getConfigManager().isPaperAutoDefragment();
        this.autoRemoveEmptyRegions = plugin.getConfigManager().isPaperRemoveEmptyRegions();

        Logger.info("Region file optimizer configuration loaded:");
        Logger.info("  Incremental saving: " + (incrementalSaving ? "ENABLED" : "DISABLED"));
        Logger.info("  Save interval: " + saveInterval + "s");
        Logger.info("  Cache cleanup interval: " + cacheCleanupInterval + "s");
        Logger.info("  Cache timeout: " + (regionCacheTimeout / 1000) + "s");
        Logger.info("  Auto defragment: " + (autoDefragment ? "ENABLED" : "DISABLED"));
        Logger.info("  Auto remove empty: " + (autoRemoveEmptyRegions ? "ENABLED" : "DISABLED"));
    }

    private void initializePaperRegionAPI() {
        try {
            // Check for Paper's incremental saving API
            Class<?> worldClass = World.class;

            try {
                saveIncrementallyMethod = worldClass.getMethod("save", boolean.class);
                Logger.info("Paper incremental save API detected");
            } catch (NoSuchMethodException e) {
                Logger.debug("Incremental save API not available");
            }

            paperRegionSupported = (saveIncrementallyMethod != null);

            if (paperRegionSupported) {
                Logger.info("Paper region file optimization initialized");
            } else {
                Logger.info("Using standard region file handling");
            }

        } catch (Exception e) {
            Logger.warning("Failed to initialize Paper region API: " + e.getMessage());
            paperRegionSupported = false;
        }
    }

    public void start() {
        if (!plugin.getConfigManager().isPaperRegionFilesEnabled()) {
            Logger.info("Region file optimization disabled in config");
            return;
        }

        // Start incremental save task
        if (incrementalSaving) {
            startIncrementalSaving();
        }

        // Start cache cleanup task
        startCacheCleanup();

        Logger.info("Region file optimizer started");
    }

    public void shutdown() {
        if (optimizationTask != null) {
            optimizationTask.cancel();
        }
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }

        // Final save
        saveAllWorlds(false);

        Logger.info("Region file optimizer stopped");
    }

    private void startIncrementalSaving() {
        optimizationTask = new BukkitRunnable() {
            @Override
            public void run() {
                performIncrementalSave();
            }
        }.runTaskTimer(plugin, 20L * saveInterval, 20L * saveInterval);

        Logger.info("Incremental saving enabled (interval: " + saveInterval + "s)");
    }

    private void startCacheCleanup() {
        cleanupTask = new BukkitRunnable() {
            @Override
            public void run() {
                cleanupRegionCache();
            }
        }.runTaskTimer(plugin, 20L * cacheCleanupInterval, 20L * cacheCleanupInterval);

        Logger.info("Region cache cleanup enabled (interval: " + cacheCleanupInterval + "s)");
    }

    private void performIncrementalSave() {
        lastOptimizationTime = System.currentTimeMillis();

        try {
            for (World world : Bukkit.getWorlds()) {
                if (paperRegionSupported && saveIncrementallyMethod != null) {
                    // Use Paper's incremental save
                    saveIncrementallyMethod.invoke(world, true);
                    Logger.debug("Incremental save completed for " + world.getName());
                } else {
                    // Fallback to standard save
                    world.save();
                }
            }

        } catch (Exception e) {
            Logger.warning("Error during incremental save: " + e.getMessage());
        }
    }

    private void saveAllWorlds(boolean async) {
        for (World world : Bukkit.getWorlds()) {
            try {
                if (async && saveIncrementallyMethod != null) {
                    saveIncrementallyMethod.invoke(world, false);
                } else {
                    world.save();
                }
            } catch (Exception e) {
                Logger.warning("Error saving world " + world.getName() + ": " + e.getMessage());
            }
        }
    }

    private void cleanupRegionCache() {
        long now = System.currentTimeMillis();
        List<String> toRemove = new ArrayList<>();

        // Find stale cache entries
        for (Map.Entry<String, Long> entry : lastAccessTimes.entrySet()) {
            if (now - entry.getValue() > regionCacheTimeout) {
                toRemove.add(entry.getKey());
            }
        }

        // Remove stale entries
        for (String key : toRemove) {
            lastAccessTimes.remove(key);
            regionFiles.remove(key);
        }

        if (!toRemove.isEmpty()) {
            Logger.debug("Cleaned up " + toRemove.size() + " stale region cache entries");
        }
    }

    /**
     * Scan all world region files
     */
    private void scanWorldRegions() {
        totalRegions = 0;
        totalRegionSize.set(0);

        for (World world : Bukkit.getWorlds()) {
            File regionDir = getRegionDirectory(world);
            if (regionDir != null && regionDir.exists()) {
                scanRegionDirectory(world.getName(), regionDir);
            }
        }

        Logger.info("Scanned " + totalRegions + " region files (" +
                formatBytes(totalRegionSize.get()) + ")");
    }

    private void scanRegionDirectory(String worldName, File regionDir) {
        File[] files = regionDir.listFiles((dir, name) ->
                name.endsWith(".mca") || name.endsWith(".mcr"));

        if (files == null) return;

        for (File file : files) {
            try {
                RegionFileInfo info = new RegionFileInfo();
                info.worldName = worldName;
                info.fileName = file.getName();
                info.path = file.getAbsolutePath();
                info.size = file.length();
                info.lastModified = file.lastModified();

                String key = worldName + "/" + file.getName();
                regionFiles.put(key, info);
                totalRegionSize.addAndGet(file.length());
                totalRegions++;

            } catch (Exception e) {
                Logger.warning("Error scanning region file " + file.getName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Optimize region files by removing unused space
     */
    public OptimizationResult optimizeRegionFiles(World world) {
        OptimizationResult result = new OptimizationResult();
        result.startTime = System.currentTimeMillis();

        File regionDir = getRegionDirectory(world);
        if (regionDir == null || !regionDir.exists()) {
            result.error = "Region directory not found";
            return result;
        }

        Logger.info("Optimizing region files for " + world.getName() + "...");

        if (autoDefragment && !defragRiskWarned) {
            Logger.warning("Defragmentation rewrites .mca files on disk while " + world.getName() +
                    " is still loaded. There is no public API to invalidate the server's own " +
                    "in-memory region-file cache afterward, so a chunk save that lands mid-operation " +
                    "could still write to now-stale offsets. flushRegionCache() runs first to narrow " +
                    "this window, but it is not eliminated - disable paper.region-files.auto-defragment " +
                    "if this risk is a concern for this server.");
            defragRiskWarned = true;
        }

        File[] files = regionDir.listFiles((dir, name) -> name.endsWith(".mca"));
        if (files == null) {
            result.error = "No region files found";
            return result;
        }

        for (File file : files) {
            try {
                long sizeBefore = file.length();

                if (autoDefragment) {
                    defragmentRegionFile(file);
                }

                long sizeAfter = file.length();
                long saved = sizeBefore - sizeAfter;

                if (saved > 0) {
                    result.filesOptimized++;
                    result.bytesFreed += saved;
                    bytesFreed += saved;
                    optimizedRegions++;
                }

                result.filesProcessed++;

            } catch (Exception e) {
                Logger.warning("Error optimizing " + file.getName() + ": " + e.getMessage());
                result.errors++;
            }
        }

        result.endTime = System.currentTimeMillis();
        result.duration = result.endTime - result.startTime;

        Logger.info("Region optimization complete: " + result.filesOptimized + " files optimized, " +
                formatBytes(result.bytesFreed) + " freed in " + result.duration + "ms");

        return result;
    }

    /**
     * Defragment a region file by compacting chunk sectors.
     *
     * This works purely at the Anvil container level: it never inspects or
     * modifies chunk NBT/compressed payload bytes, only relocates whole
     * 4096-byte sectors to remove the gaps left by deleted/relocated chunks
     * and rewrites the two 4KB header tables to point at the new locations.
     * That makes it safe across Minecraft versions without needing to
     * understand chunk data itself.
     */
    private void defragmentRegionFile(File file) throws IOException {
        // Only defragment if file is larger than 1MB
        if (file.length() < 1024 * 1024) {
            return;
        }

        File tempFile = new File(file.getParentFile(), file.getName() + ".defrag.tmp");
        boolean completed = false;

        try {
            try (RandomAccessFile source = new RandomAccessFile(file, "r")) {
                long fileLength = source.length();
                if (fileLength < 8192) {
                    return; // not a complete region file
                }

                byte[] locationTable = new byte[4096];
                byte[] timestampTable = new byte[4096];
                source.seek(0);
                source.readFully(locationTable);
                source.readFully(timestampTable);

                int[] sectorOffset = new int[1024];
                int[] sectorCount = new int[1024];
                int usedSectors = 0;

                for (int i = 0; i < 1024; i++) {
                    int offset = ((locationTable[i * 4] & 0xFF) << 16) |
                            ((locationTable[i * 4 + 1] & 0xFF) << 8) |
                            (locationTable[i * 4 + 2] & 0xFF);
                    int count = locationTable[i * 4 + 3] & 0xFF;

                    if (offset == 0 || count == 0) continue;

                    long byteOffset = (long) offset * 4096L;
                    long byteLength = (long) count * 4096L;

                    // Sanity check: entries must land inside the file and
                    // never inside the 8KB header. If not, the file is
                    // corrupt/mid-write - leave it untouched.
                    if (offset < 2 || byteOffset + byteLength > fileLength) {
                        Logger.warning("Skipping defragmentation of " + file.getName() +
                                " - invalid chunk sector table entry");
                        return;
                    }

                    sectorOffset[i] = offset;
                    sectorCount[i] = count;
                    usedSectors += count;
                }

                if (usedSectors == 0) {
                    return; // no chunks to compact
                }

                long newLength = 8192L + (long) usedSectors * 4096L;
                if (newLength >= fileLength) {
                    return; // already compact, nothing to gain
                }

                try (RandomAccessFile dest = new RandomAccessFile(tempFile, "rw")) {
                    dest.setLength(newLength);

                    byte[] newLocationTable = new byte[4096];
                    int cursorSector = 2; // sectors 0-1 are the 8KB header

                    for (int i = 0; i < 1024; i++) {
                        if (sectorCount[i] == 0) continue;

                        byte[] buffer = new byte[sectorCount[i] * 4096];
                        source.seek((long) sectorOffset[i] * 4096L);
                        source.readFully(buffer);

                        dest.seek((long) cursorSector * 4096L);
                        dest.write(buffer);

                        newLocationTable[i * 4] = (byte) ((cursorSector >> 16) & 0xFF);
                        newLocationTable[i * 4 + 1] = (byte) ((cursorSector >> 8) & 0xFF);
                        newLocationTable[i * 4 + 2] = (byte) (cursorSector & 0xFF);
                        newLocationTable[i * 4 + 3] = (byte) (sectorCount[i] & 0xFF);

                        cursorSector += sectorCount[i];
                    }

                    dest.seek(0);
                    dest.write(newLocationTable);
                    dest.write(timestampTable);
                }
            }

            // Atomically swap the compacted copy in place of the original
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            completed = true;

        } finally {
            if (!completed && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    /**
     * Find and remove empty region files
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

    private boolean isRegionEmpty(File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            // Read first 4KB (locations)
            byte[] header = new byte[4096];
            raf.read(header);

            // Check if any chunks exist
            for (int i = 0; i < 1024; i++) {
                int offset = (header[i * 4] & 0xFF) << 16 |
                        (header[i * 4 + 1] & 0xFF) << 8 |
                        (header[i * 4 + 2] & 0xFF);

                if (offset > 0) {
                    return false; // Found a chunk
                }
            }

            return true; // No chunks found
        }
    }

    /**
     * Get region directory for a world
     */
    private File getRegionDirectory(World world) {
        File worldFolder = world.getWorldFolder();

        // Check different possible paths
        File regionDir = new File(worldFolder, "region");
        if (regionDir.exists()) return regionDir;

        // For Nether/End
        String envName = world.getEnvironment().name().toLowerCase();
        regionDir = new File(worldFolder, "DIM-1/region"); // Nether
        if (regionDir.exists() && envName.contains("nether")) return regionDir;

        regionDir = new File(worldFolder, "DIM1/region"); // End
        if (regionDir.exists() && envName.contains("end")) return regionDir;

        return null;
    }

    /**
     * Get statistics about region files
     */
    public RegionStats getStatistics() {
        RegionStats stats = new RegionStats();
        stats.totalRegions = totalRegions;
        stats.totalSize = totalRegionSize.get();
        stats.optimizedRegions = optimizedRegions;
        stats.bytesFreed = bytesFreed;
        stats.paperSupported = paperRegionSupported;
        stats.incrementalSaving = incrementalSaving;
        stats.lastOptimization = lastOptimizationTime;

        // Calculate per-world stats
        for (RegionFileInfo info : regionFiles.values()) {
            stats.sizeByWorld.merge(info.worldName, info.size, Long::sum);
            stats.countByWorld.merge(info.worldName, 1, Integer::sum);
        }

        return stats;
    }

    /**
     * Force flush region cache (Paper only)
     */
    public void flushRegionCache() {
        if (!paperRegionSupported) {
            Logger.debug("Region cache flushing not supported");
            return;
        }

        Logger.info("Flushing region cache...");
        saveAllWorlds(false);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // Configuration methods
    public void setIncrementalSaving(boolean enabled) {
        this.incrementalSaving = enabled;
    }

    public void setSaveInterval(int seconds) {
        this.saveInterval = seconds;
    }

    public void setAutoDefragment(boolean enabled) {
        this.autoDefragment = enabled;
    }

    public void setCacheCleanupInterval(int seconds) {
        this.cacheCleanupInterval = seconds;
    }

    // Inner classes
    private static class RegionFileInfo {
        String worldName;
        String fileName;
        String path;
        long size;
        long lastModified;
    }

    public static class OptimizationResult {
        public long startTime;
        public long endTime;
        public long duration;
        public int filesProcessed;
        public int filesOptimized;
        public long bytesFreed;
        public int errors;
        public String error;
    }

    public static class RegionStats {
        public int totalRegions;
        public long totalSize;
        public int optimizedRegions;
        public long bytesFreed;
        public boolean paperSupported;
        public boolean incrementalSaving;
        public long lastOptimization;
        public Map<String, Long> sizeByWorld = new HashMap<>();
        public Map<String, Integer> countByWorld = new HashMap<>();
    }
}