package me.rimuru.ultraoptimize.managers;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class StatisticsManager {

    private static final long AUTOSAVE_INTERVAL_TICKS = 20L * 60 * 5; // 5 minutes

    private final UltraOptimize plugin;
    private final File lifetimeStatsFile;

    private final AtomicInteger totalOptimizations;
    private final AtomicInteger entitiesRemoved;
    private final AtomicInteger itemsMerged;
    private final AtomicInteger chunksUnloaded;
    private final AtomicLong chunksPreloaded;
    private final AtomicLong lifetimeUptime;

    private long sessionStartTime;
    private BukkitTask autosaveTask;

    public StatisticsManager(UltraOptimize plugin) {
        this.plugin = plugin;
        this.lifetimeStatsFile = new File(plugin.getDataFolder(), "lifetime-stats.yml");
        this.totalOptimizations = new AtomicInteger(0);
        this.entitiesRemoved = new AtomicInteger(0);
        this.itemsMerged = new AtomicInteger(0);
        this.chunksUnloaded = new AtomicInteger(0);
        this.chunksPreloaded = new AtomicLong(0);
        this.lifetimeUptime = new AtomicLong(0);
        this.sessionStartTime = System.currentTimeMillis();

        loadLifetimeStatistics();
    }

    /**
     * Starts periodic autosaving so lifetime totals survive a crash, not
     * just a clean shutdown.
     */
    public void start() {
        // Asynchronous: this writes a YAML file to disk, and the counters it
        // reads are all Atomics. There is no reason to stall a tick for it.
        autosaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,
                this::persistLifetimeStatistics, AUTOSAVE_INTERVAL_TICKS, AUTOSAVE_INTERVAL_TICKS);
    }

    public void shutdown() {
        if (autosaveTask != null) {
            autosaveTask.cancel();
        }
    }

    private void loadLifetimeStatistics() {
        if (!lifetimeStatsFile.exists()) {
            return;
        }

        try {
            YamlConfiguration data = YamlConfiguration.loadConfiguration(lifetimeStatsFile);
            totalOptimizations.set(data.getInt("total-optimizations", 0));
            entitiesRemoved.set(data.getInt("entities-removed", 0));
            itemsMerged.set(data.getInt("items-merged", 0));
            chunksUnloaded.set(data.getInt("chunks-unloaded", 0));
            chunksPreloaded.set(data.getLong("chunks-preloaded", 0));
            lifetimeUptime.set(data.getLong("lifetime-uptime-millis", 0));

            Logger.info("Loaded lifetime statistics (optimizations: " + totalOptimizations.get() +
                    ", entities removed: " + entitiesRemoved.get() + ")");
        } catch (Exception e) {
            Logger.warning("Failed to load lifetime statistics: " + e.getMessage());
        }
    }

    private void persistLifetimeStatistics() {
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }

            YamlConfiguration data = new YamlConfiguration();
            data.set("total-optimizations", getTotalOptimizations());
            data.set("entities-removed", getEntitiesRemoved());
            data.set("items-merged", getItemsMerged());
            data.set("chunks-unloaded", getChunksUnloaded());
            data.set("chunks-preloaded", getChunksPreloaded());
            data.set("lifetime-uptime-millis", lifetimeUptime.get() + getUptime());
            data.save(lifetimeStatsFile);
        } catch (IOException e) {
            Logger.warning("Failed to persist lifetime statistics: " + e.getMessage());
        }
    }

    public void incrementOptimizations() {
        totalOptimizations.incrementAndGet();
    }

    public void incrementEntitiesRemoved(int amount) {
        entitiesRemoved.addAndGet(amount);
    }

    public void incrementItemsMerged(int amount) {
        itemsMerged.addAndGet(amount);
    }

    public void incrementChunksUnloaded(int amount) {
        chunksUnloaded.addAndGet(amount);
    }

    public void incrementChunksPreloaded(long amount) {
        chunksPreloaded.addAndGet(amount);
    }

    public int getTotalOptimizations() {
        return totalOptimizations.get();
    }

    public int getEntitiesRemoved() {
        return entitiesRemoved.get();
    }

    public int getItemsMerged() {
        return itemsMerged.get();
    }

    public int getChunksUnloaded() {
        return chunksUnloaded.get();
    }

    public long getChunksPreloaded() {
        return chunksPreloaded.get();
    }

    public long getUptime() {
        return System.currentTimeMillis() - sessionStartTime;
    }

    public long getLifetimeUptime() {
        return lifetimeUptime.get() + getUptime();
    }

    public void reset() {
        totalOptimizations.set(0);
        entitiesRemoved.set(0);
        itemsMerged.set(0);
        chunksUnloaded.set(0);
        chunksPreloaded.set(0);
        lifetimeUptime.set(0);
        sessionStartTime = System.currentTimeMillis();
        persistLifetimeStatistics();
        Logger.info("Statistics reset");
    }

    public void saveStatistics() {
        try {
            File statsFile = new File(plugin.getDataFolder(), "statistics.log");

            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }

            try (FileWriter writer = new FileWriter(statsFile, true)) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

                writer.write("\n=================================\n");
                writer.write("Session End: " + sdf.format(new Date()) + "\n");
                writer.write("=================================\n");
                writer.write("Session Uptime: " + formatUptime(getUptime()) + "\n");
                writer.write("Lifetime Uptime: " + formatUptime(getLifetimeUptime()) + "\n");
                writer.write("Average TPS: " + String.format("%.2f", plugin.getPerformanceMonitor().getAverageTPS()) + "\n");
                writer.write("Total Entities: " + plugin.getEntityManager().getTotalEntities() + "\n");
                writer.write("Loaded Chunks: " + getTotalChunks() + "\n");
                writer.write("Total Optimizations: " + getTotalOptimizations() + "\n");
                writer.write("Entities Removed: " + getEntitiesRemoved() + "\n");
                writer.write("Items Merged: " + getItemsMerged() + "\n");
                writer.write("Chunks Unloaded: " + getChunksUnloaded() + "\n");
                writer.write("Chunks Preloaded: " + getChunksPreloaded() + "\n");
                writer.write("\n");
            }

            persistLifetimeStatistics();

            Logger.info("Statistics saved successfully");

        } catch (IOException e) {
            Logger.severe("Failed to save statistics: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private int getTotalChunks() {
        int total = 0;
        for (World world : Bukkit.getWorlds()) {
            total += world.getLoadedChunks().length;
        }
        return total;
    }

    private String formatUptime(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) {
            return days + "d " + (hours % 24) + "h";
        } else if (hours > 0) {
            return hours + "h " + (minutes % 60) + "m";
        } else if (minutes > 0) {
            return minutes + "m " + (seconds % 60) + "s";
        } else {
            return seconds + "s";
        }
    }

    public Map<String, Object> getStatisticsMap() {
        Map<String, Object> stats = new ConcurrentHashMap<>();

        stats.put("total_optimizations", getTotalOptimizations());
        stats.put("entities_removed", getEntitiesRemoved());
        stats.put("items_merged", getItemsMerged());
        stats.put("chunks_unloaded", getChunksUnloaded());
        stats.put("chunks_preloaded", getChunksPreloaded());
        stats.put("uptime", getUptime());
        stats.put("uptime_formatted", formatUptime(getUptime()));

        return stats;
    }
}