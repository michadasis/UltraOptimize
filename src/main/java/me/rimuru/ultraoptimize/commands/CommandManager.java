package me.rimuru.ultraoptimize.commands;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.managers.*;
import me.rimuru.ultraoptimize.paper.PaperOptimizationManager;
import me.rimuru.ultraoptimize.paper.RegionFileOptimizer;
import me.rimuru.ultraoptimize.paper.WatchdogMonitor;
import me.rimuru.ultraoptimize.utils.Logger;
import me.rimuru.ultraoptimize.utils.Msg;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;

import java.util.Map;

public class CommandManager implements CommandExecutor {

    private final UltraOptimize plugin;

    public CommandManager(UltraOptimize plugin) {
        this.plugin = plugin;

        plugin.getCommand("ultraoptimize").setExecutor(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("ultraoptimize") &&
                !cmd.getName().equalsIgnoreCase("uo")) {
            return false;
        }

        if (args.length == 0) {
            showHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        try {
            switch (subCommand) {
                case "reload":
                    return handleReload(sender);

                case "clear":
                    return handleClear(sender, args);

                case "optimize":
                    return handleOptimize(sender);

                case "stats":
                    return handleStats(sender);

                case "chunks":
                    return handleChunks(sender, args);

                case "preload":
                    return handlePreload(sender, args);

                case "gc":
                    return handleGC(sender, args);

                case "auto":
                    return handleAuto(sender);

                case "merge":
                    return handleMerge(sender);

                case "view":
                    return handleView(sender, args);

                case "report":
                    return handleReport(sender);

                case "info":
                    return handleInfo(sender);

                case "paper":
                    return handlePaper(sender, args);

                default:
                    showHelp(sender);
                    return true;
            }
        } catch (Exception e) {
            Msg.error(sender, "An unexpected error occurred: " + e.getMessage());
            Logger.severe("Command error: " + e.getMessage());
            e.printStackTrace();
            return true;
        }
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("ultraoptimize.reload")) {
            Msg.noPermission(sender);
            return true;
        }

        plugin.reload();
        Msg.success(sender, "Configuration reloaded successfully.");
        return true;
    }

    private boolean handleClear(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ultraoptimize.clear")) {
            Msg.noPermission(sender);
            return true;
        }

        if (args.length < 2) {
            Msg.usage(sender, "/uo clear <items|mobs|all|xp|arrows>");
            return true;
        }

        EntityManager.EntityClearType type;
        try {
            type = EntityManager.EntityClearType.valueOf(args[1].toUpperCase());
        } catch (IllegalArgumentException e) {
            Msg.error(sender, "Invalid type! Use: items, mobs, xp, arrows, or all");
            return true;
        }

        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            removed += plugin.getEntityManager().clearEntities(world, type);
        }

        Msg.success(sender, "Removed §f" + removed + " §aentities.");
        if (type == EntityManager.EntityClearType.ALL) {
            Msg.send(sender, "§7Cleared dropped items, XP, projectiles and hostile mobs. " +
                    "Vehicles, tamed animals and passive mobs were left alone.");
        }
        return true;
    }

    private boolean handleOptimize(CommandSender sender) {
        if (!sender.hasPermission("ultraoptimize.optimize")) {
            Msg.noPermission(sender);
            return true;
        }

        Msg.info(sender, "Running manual optimization...");
        OptimizationManager.OptimizationResult result = plugin.getOptimizationManager().performManualOptimization();

        Msg.success(sender, "Optimization complete! Removed §f" + result.entitiesRemoved + " §aentities.");
        return true;
    }

    private boolean handleStats(CommandSender sender) {
        if (!sender.hasPermission("ultraoptimize.stats")) {
            Msg.noPermission(sender);
            return true;
        }

        PerformanceMonitor.PerformanceReport report = plugin.getPerformanceMonitor().generateReport();
        Map<EntityType, Integer> entityCounts = plugin.getEntityManager().getEntityCounts();
        ChunkPreloader.PreloadStatistics preloadStats = plugin.getChunkPreloader().getStatistics();

        Msg.header(sender, "UltraOptimize Statistics");
        Msg.kv(sender, "Current TPS", String.format("%.2f", report.currentTPS) +
                " §7(avg " + String.format("%.2f", report.averageTPS) + ")");
        Msg.kv(sender, "Memory", report.memoryInfo.usedMemory + "MB §7/ §f" +
                report.memoryInfo.totalMemory + "MB §7/ §f" +
                report.memoryInfo.maxMemory + "MB §7(" +
                String.format("%.1f", report.memoryInfo.usagePercent) + "%)");
        Msg.kv(sender, "Total Entities", String.valueOf(report.totalEntities));
        Msg.kv(sender, "Loaded Chunks", String.valueOf(report.totalChunks));
        Msg.kv(sender, "Spawn Chunks Preloaded", String.valueOf(preloadStats.chunksPreloaded));
        Msg.kv(sender, "Currently Preloading", Msg.bool(preloadStats.isPreloading, "Yes", "No"));
        Msg.kv(sender, "Auto-Optimize", Msg.bool(plugin.getOptimizationManager().isAutoOptimizeEnabled()));
        Msg.kv(sender, "Spawn Preloading", Msg.bool(plugin.getConfigManager().isChunkPreloadingEnabled()));
        Msg.kv(sender, "Total Optimizations", String.valueOf(plugin.getStatisticsManager().getTotalOptimizations()));
        Msg.kv(sender, "Lifetime Entities Removed", String.valueOf(plugin.getStatisticsManager().getEntitiesRemoved()));
        Msg.kv(sender, "Lifetime Items Merged", String.valueOf(plugin.getStatisticsManager().getItemsMerged()));
        Msg.kv(sender, "Lifetime Chunks Preloaded", String.valueOf(plugin.getStatisticsManager().getChunksPreloaded()));
        if (plugin.getConfigManager().isOptimizeAI()) {
            Msg.kv(sender, "Mobs with AI Paused", String.valueOf(plugin.getEntityAIManager().getMobsFrozen()));
        }

        Msg.section(sender, "Top Entities");
        entityCounts.entrySet().stream()
                .sorted(Map.Entry.<EntityType, Integer>comparingByValue().reversed())
                .limit(5)
                .forEach(entry -> Msg.kv(sender, 2, entry.getKey().toString(), String.valueOf(entry.getValue())));

        return true;
    }

    private boolean handleChunks(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ultraoptimize.chunks")) {
            Msg.noPermission(sender);
            return true;
        }

        if (args.length < 2) {
            Msg.usage(sender, "/uo chunks <unload|info>");
            return true;
        }

        String action = args[1].toLowerCase();

        if (action.equals("unload")) {
            int unloaded = plugin.getChunkManager().unloadEmptyChunks();
            Msg.success(sender, "Unloaded §f" + unloaded + " §aempty chunks.");
        } else if (action.equals("info")) {
            ChunkManager.ChunkStatistics stats = plugin.getChunkManager().getStatistics();
            Msg.header(sender, "Chunk Information");
            Msg.kv(sender, "Total Chunks", String.valueOf(stats.totalChunks));
            Msg.kv(sender, "Tracked Chunks", String.valueOf(stats.trackedChunks));
            Msg.kv(sender, "Problematic Chunks", String.valueOf(stats.problematicChunks));
        } else {
            Msg.usage(sender, "/uo chunks <unload|info>");
        }

        return true;
    }

    private boolean handlePreload(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ultraoptimize.preload")) {
            Msg.noPermission(sender);
            return true;
        }

        if (!plugin.getConfigManager().isChunkPreloadingEnabled()) {
            Msg.error(sender, "Chunk preloading is disabled in config.");
            return true;
        }

        if (args.length < 2) {
            Msg.usage(sender, "/uo preload <info|restart>");
            return true;
        }

        String action = args[1].toLowerCase();

        if (action.equals("info")) {
            ChunkPreloader.PreloadStatistics stats = plugin.getChunkPreloader().getStatistics();
            Msg.header(sender, "Spawn Chunk Preloading Statistics");
            Msg.kv(sender, "Total Chunks Preloaded", String.valueOf(stats.chunksPreloaded));
            Msg.kv(sender, "Currently Preloading", Msg.bool(stats.isPreloading, "Yes", "No"));
            Msg.kv(sender, "Preload Radius", plugin.getConfigManager().getPreloadRadius() + " chunks");
            Msg.kv(sender, "Chunks/Tick", String.valueOf(plugin.getConfigManager().getPreloadChunksPerTick()));
            Msg.kv(sender, "Pattern", plugin.getConfigManager().isSpiralPattern() ? "Spiral" : "Square");
        } else if (action.equals("restart")) {
            if (plugin.getChunkPreloader().isPreloading()) {
                Msg.error(sender, "Chunk preloading is already in progress.");
                return true;
            }
            Msg.info(sender, "Restarting spawn chunk preloading...");
            plugin.getChunkPreloader().restart();
        } else {
            Msg.usage(sender, "/uo preload <info|restart>");
        }

        return true;
    }

    private boolean handleGC(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ultraoptimize.gc")) {
            Msg.noPermission(sender);
            return true;
        }

        boolean force = args.length > 1 && args[1].equalsIgnoreCase("force");

        long wait = plugin.getPerformanceMonitor().getMillisUntilGarbageCollectionAllowed();
        if (!force && wait > 0) {
            Msg.error(sender, "Garbage collection ran recently. A forced full GC pauses the whole " +
                    "server, so it's rate limited.");
            Msg.send(sender, "§7Available again in " + (wait / 1000) + "s, or use §f/uo gc force§7.");
            return true;
        }

        Msg.info(sender, "Running garbage collection (the server will pause briefly)...");
        long freed = plugin.getPerformanceMonitor().requestGarbageCollection(true);
        Msg.success(sender, "Reclaimed §f" + Math.max(0, freed) + "MB§a.");
        return true;
    }

    private boolean handleAuto(CommandSender sender) {
        if (!sender.hasPermission("ultraoptimize.auto")) {
            Msg.noPermission(sender);
            return true;
        }

        plugin.getOptimizationManager().toggleAutoOptimize();
        boolean enabled = plugin.getOptimizationManager().isAutoOptimizeEnabled();
        Msg.success(sender, "Auto-optimization " + Msg.bool(enabled, "enabled", "disabled") + "§a.");
        return true;
    }

    private boolean handleMerge(CommandSender sender) {
        if (!sender.hasPermission("ultraoptimize.merge")) {
            Msg.noPermission(sender);
            return true;
        }

        // This runs the full optimization pass, which merges items and XP but
        // also removes stuck arrows and any excess items/mobs over the
        // per-chunk caps. Reporting it as "merged" was misleading.
        Msg.info(sender, "Running entity merge and cleanup...");
        int affected = 0;
        for (World world : Bukkit.getWorlds()) {
            affected += plugin.getEntityManager().optimizeWorld(world);
        }
        Msg.success(sender, "Merged or removed §f" + affected + " §aentities.");
        return true;
    }

    private boolean handleView(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ultraoptimize.view")) {
            Msg.noPermission(sender);
            return true;
        }

        if (!plugin.getPerformanceMonitor().isViewDistanceSupported()) {
            Msg.error(sender, "View distance management is not supported on this server.");
            Msg.send(sender, "§7" + viewDistanceUnsupportedReason());
            return true;
        }

        if (args.length < 3) {
            Msg.usage(sender, "/uo view <world> <distance>");
            return true;
        }

        World world = Bukkit.getWorld(args[1]);
        if (world == null) {
            Msg.error(sender, "World not found: §f" + args[1]);
            return true;
        }

        try {
            int distance = Integer.parseInt(args[2]);

            if (distance < 2 || distance > 32) {
                Msg.error(sender, "View distance must be between 2 and 32.");
                return true;
            }

            plugin.getPerformanceMonitor().setWorldViewDistance(world, distance);
            Msg.success(sender, "Set view distance to §f" + distance + " §afor §f" + world.getName() + "§a.");
        } catch (NumberFormatException e) {
            Msg.error(sender, "Invalid distance! Must be a whole number.");
        }

        return true;
    }

    private boolean handleReport(CommandSender sender) {
        if (!sender.hasPermission("ultraoptimize.report")) {
            Msg.noPermission(sender);
            return true;
        }

        generateReport(sender);
        return true;
    }

    private boolean handleInfo(CommandSender sender) {
        if (!sender.hasPermission("ultraoptimize.info")) {
            Msg.noPermission(sender);
            return true;
        }

        showPluginInfo(sender);
        return true;
    }

    private void generateReport(CommandSender sender) {
        Msg.info(sender, "Generating performance report...");

        PerformanceMonitor.PerformanceReport report = plugin.getPerformanceMonitor().generateReport();
        ChunkPreloader.PreloadStatistics preloadStats = plugin.getChunkPreloader().getStatistics();

        Msg.header(sender, "Performance Analysis Report");

        Msg.section(sender, "Server Performance");
        String tpsStatus = report.currentTPS < 15 ? "§c(CRITICAL)" :
                report.currentTPS < 18 ? "§e(WARNING)" : "§a(GOOD)";
        Msg.kv(sender, 2, "TPS", String.format("%.2f", report.currentTPS) + " " + tpsStatus);
        Msg.kv(sender, 2, "Avg TPS", String.format("%.2f", report.averageTPS));

        Msg.section(sender, "Memory Usage");
        String memStatus = report.memoryInfo.usagePercent > 90 ? "§c(CRITICAL)" :
                report.memoryInfo.usagePercent > 75 ? "§e(WARNING)" : "§a(GOOD)";
        Msg.kv(sender, 2, "Used", report.memoryInfo.usedMemory + "MB §7/ §f" + report.memoryInfo.maxMemory +
                "MB §7(" + String.format("%.1f", report.memoryInfo.usagePercent) + "%) " + memStatus);

        Msg.section(sender, "World Statistics");
        String entityNote = report.totalEntities > 5000 ? " §e(HIGH)" : "";
        Msg.kv(sender, 2, "Total Entities", report.totalEntities + entityNote);
        Msg.kv(sender, 2, "Loaded Chunks", String.valueOf(report.totalChunks));
        Msg.kv(sender, 2, "Preloaded Chunks", String.valueOf(preloadStats.chunksPreloaded));
        Msg.kv(sender, 2, "Entities/Chunk", String.format("%.2f", report.entitiesPerChunk));

        Msg.section(sender, "Chunk Preloading");
        Msg.kv(sender, 2, "Status", Msg.bool(plugin.getConfigManager().isChunkPreloadingEnabled()));
        Msg.kv(sender, 2, "Session Preloaded", String.valueOf(preloadStats.chunksPreloaded));

        Msg.section(sender, "Recommendations");
        if (report.currentTPS < 18) {
            Msg.send(sender, "  §c⚠ Low TPS detected. Consider:");
            Msg.send(sender, "    §7- Running /uo optimize");
            Msg.send(sender, "    §7- Reducing entity limits");
            Msg.send(sender, "    §7- Lowering preload radius");
        }
        if (report.memoryInfo.usagePercent > 80) {
            Msg.send(sender, "  §c⚠ High memory usage. Consider:");
            Msg.send(sender, "    §7- Lowering view distance and simulation distance");
            Msg.send(sender, "    §7- Reducing entity limits");
            Msg.send(sender, "    §7- Disabling chunk preloading and spawn chunk tickets");
            Msg.send(sender, "    §7(a forced GC via /uo gc pauses the server and rarely helps)");
        }
        if (report.currentTPS >= 19 && report.memoryInfo.usagePercent < 70) {
            Msg.send(sender, "  §a✓ Server is running optimally!");
        }
    }

    private void showPluginInfo(CommandSender sender) {
        Msg.header(sender, "UltraOptimize v" + plugin.getDescription().getVersion());
        Msg.kv(sender, "Status", "§aRunning");
        Msg.kv(sender, "Auto-Optimize", Msg.bool(plugin.getOptimizationManager().isAutoOptimizeEnabled()));
        Msg.kv(sender, "Chunk Preloading", Msg.bool(plugin.getConfigManager().isChunkPreloadingEnabled()));
        Msg.kv(sender, "Preload Radius", plugin.getConfigManager().getPreloadRadius() + " chunks");
        Msg.kv(sender, "Optimization Interval", plugin.getConfigManager().getAutoOptimizeInterval() + "s");
        Msg.kv(sender, "TPS Threshold", String.valueOf(plugin.getConfigManager().getTpsThreshold()));
        String viewDistanceStatus = plugin.getPerformanceMonitor().isViewDistanceSupported()
                ? "§aSupported"
                : "§cNot Supported §7(" + viewDistanceUnsupportedReason() + ")";
        Msg.kv(sender, "View Distance Control", viewDistanceStatus);
    }

    /**
     * World#setViewDistance only exists on Paper's Bukkit API (and forks
     * like Purpur), never on plain Spigot, regardless of Minecraft version.
     * Report the actual blocker instead of the misleading "1.14+ required".
     */
    private String viewDistanceUnsupportedReason() {
        if (!plugin.getPaperManager().isPaperDetected()) {
            return "requires Paper or a Paper-based fork; not available on Spigot";
        }
        return "requires Minecraft 1.14 or newer";
    }

    private void showHelp(CommandSender sender) {
        Msg.header(sender, "UltraOptimize Commands");
        Msg.send(sender, "§e/uo reload §8- §7Reload configuration");
        Msg.send(sender, "§e/uo clear <type> §8- §7Clear entities (items/mobs/all/xp/arrows)");
        Msg.send(sender, "§e/uo optimize §8- §7Run manual optimization");
        Msg.send(sender, "§e/uo stats §8- §7Show detailed statistics");
        Msg.send(sender, "§e/uo chunks <action> §8- §7Manage chunks (unload/info)");
        Msg.send(sender, "§e/uo preload <action> §8- §7Chunk preloading (info/restart)");
        Msg.send(sender, "§e/uo gc §8- §7Run garbage collection");
        Msg.send(sender, "§e/uo auto §8- §7Toggle auto-optimization");
        Msg.send(sender, "§e/uo merge §8- §7Merge nearby items/xp");
        if (plugin.getPerformanceMonitor().isViewDistanceSupported()) {
            Msg.send(sender, "§e/uo view <world> <dist> §8- §7Set view distance (Paper only)");
        }
        Msg.send(sender, "§e/uo report §8- §7Generate performance report");
        Msg.send(sender, "§e/uo info §8- §7Show plugin configuration");

        // Paper-specific commands
        if (plugin.getPaperManager().isPaperDetected()) {
            Msg.section(sender, "Paper Commands");
            Msg.send(sender, "§e/uo paper stats §8- §7Paper optimization stats");
            Msg.send(sender, "§e/uo paper optimize §8- §7Run Paper optimization");
            Msg.send(sender, "§e/uo paper watchdog <action> §8- §7Watchdog management");
            Msg.send(sender, "§e/uo paper regions <action> §8- §7Region file management");
        }
    }

    private boolean handlePaper(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ultraoptimize.paper")) {
            Msg.noPermission(sender);
            return true;
        }

        if (!plugin.getPaperManager().isPaperDetected()) {
            Msg.error(sender, "Not running on a Paper server.");
            return true;
        }

        if (args.length < 2) {
            Msg.usage(sender, "/uo paper <stats|optimize|watchdog|regions>");
            return true;
        }

        String subCommand = args[1].toLowerCase();

        switch (subCommand) {
            case "stats":
                return handlePaperStats(sender);
            case "optimize":
                return handlePaperOptimize(sender);
            case "watchdog":
                return handleWatchdog(sender, args);
            case "regions":
                return handleRegions(sender, args);
            default:
                Msg.usage(sender, "/uo paper <stats|optimize|watchdog|regions>");
                return true;
        }
    }

    private boolean handlePaperStats(CommandSender sender) {
        PaperOptimizationManager.PaperStats stats = plugin.getPaperManager().getStatistics();

        Msg.header(sender, "Paper Optimization Stats");
        Msg.kv(sender, "Server Version", stats.serverVersion);

        // Chunk System Stats
        if (stats.chunkSystemStats != null) {
            Msg.section(sender, "Chunk System");
            Msg.kv(sender, 2, "Active Tickets", String.valueOf(stats.chunkSystemStats.activeTickets));
            Msg.kv(sender, 2, "Paper Support", Msg.bool(stats.chunkSystemStats.paperSupported, "Yes", "No"));

            if (!stats.chunkSystemStats.ticketsByType.isEmpty()) {
                Msg.send(sender, "  §eTickets by Type:");
                stats.chunkSystemStats.ticketsByType.forEach((type, count) ->
                        Msg.kv(sender, 4, type.toString(), String.valueOf(count)));
            }
        }

        // Chunk Loader Stats
        if (stats.chunkLoaderStats != null) {
            Msg.section(sender, "Chunk Loader");
            Msg.kv(sender, 2, "Total Loads", String.valueOf(stats.chunkLoaderStats.totalLoads));
            Msg.kv(sender, 2, "Newly Generated",
                    stats.chunkLoaderStats.generatedChunks + (stats.chunkLoaderStats.estimatedValues ? " §7(sampled)" : ""));
        }

        // Watchdog Stats
        if (stats.watchdogStats != null) {
            Msg.section(sender, "Watchdog Monitor");
            Msg.kv(sender, 2, "Total Hangs", String.valueOf(stats.watchdogStats.totalHangs));
            Msg.kv(sender, 2, "Total Hang Time", formatTime(stats.watchdogStats.totalHangTime));
            Msg.kv(sender, 2, "Average Hang", stats.watchdogStats.averageHangDuration + "ms");
            Msg.kv(sender, 2, "Emergency Mode", stats.watchdogStats.emergencyMode ? "§cACTIVE" : "§aInactive");

            if (!stats.watchdogStats.hangCauses.isEmpty()) {
                Msg.send(sender, "  §eHang Causes:");
                stats.watchdogStats.hangCauses.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .limit(5)
                        .forEach(entry -> Msg.kv(sender, 4, entry.getKey(), String.valueOf(entry.getValue())));
            }
        }

        // Region File Stats
        if (stats.regionStats != null) {
            Msg.section(sender, "Region Files");
            Msg.kv(sender, 2, "Total Regions", String.valueOf(stats.regionStats.totalRegions));
            Msg.kv(sender, 2, "Total Size", formatBytes(stats.regionStats.totalSize));
            Msg.kv(sender, 2, "Periodic World Save", Msg.bool(stats.regionStats.incrementalSaving));

            if (!stats.regionStats.sizeByWorld.isEmpty()) {
                Msg.send(sender, "  §eSize by World:");
                stats.regionStats.sizeByWorld.forEach((world, size) ->
                        Msg.send(sender, "    §7" + world + "§8: §f" + formatBytes(size) +
                                " §7(§e" + stats.regionStats.countByWorld.get(world) + " §7regions)"));
            }
        }

        return true;
    }

    private boolean handlePaperOptimize(CommandSender sender) {
        Msg.info(sender, "Starting Paper optimization...");

        PaperOptimizationManager.PaperOptimizationResult result =
                plugin.getPaperManager().performOptimization();

        if (result.success) {
            Msg.success(sender, "Paper optimization complete!");
            Msg.kv(sender, "Empty Regions Removed", String.valueOf(result.emptyRegionsRemoved));
            Msg.kv(sender, "Duration", result.duration + "ms");
        } else {
            Msg.error(sender, "Optimization failed: §f" + result.error);
        }

        return true;
    }

    private boolean handleWatchdog(CommandSender sender, String[] args) {
        if (args.length < 3) {
            Msg.usage(sender, "/uo paper watchdog <status|reset|emergency>");
            return true;
        }

        WatchdogMonitor monitor = plugin.getPaperManager().getWatchdogMonitor();
        if (monitor == null) {
            Msg.error(sender, "Watchdog monitor not available.");
            return true;
        }

        String action = args[2].toLowerCase();

        switch (action) {
            case "status":
                WatchdogMonitor.WatchdogStats stats = monitor.getStatistics();
                Msg.header(sender, "Watchdog Status");
                Msg.kv(sender, "Total Hangs", String.valueOf(stats.totalHangs));
                Msg.kv(sender, "Current Hang Count", String.valueOf(stats.currentHangCount));
                Msg.kv(sender, "Emergency Mode", stats.emergencyMode ? "§cACTIVE" : "§aInactive");

                if (!stats.recentHangs.isEmpty()) {
                    Msg.section(sender, "Recent Hangs");
                    stats.recentHangs.stream().limit(5).forEach(hang ->
                            Msg.send(sender, "  §7- §f" + hang));
                }
                break;

            case "reset":
                monitor.resetHangCount();
                Msg.success(sender, "Watchdog hang counter reset.");
                break;

            case "emergency":
                if (monitor.isEmergencyMode()) {
                    Msg.error(sender, "Server is currently in EMERGENCY MODE.");
                    Msg.send(sender, "§7Emergency mode will deactivate when performance improves.");
                } else {
                    Msg.success(sender, "Server is not in emergency mode.");
                }
                break;

            default:
                Msg.usage(sender, "/uo paper watchdog <status|reset|emergency>");
                break;
        }

        return true;
    }

    private boolean handleRegions(CommandSender sender, String[] args) {
        if (args.length < 3) {
            Msg.usage(sender, "/uo paper regions <stats|clean> [world]");
            return true;
        }

        RegionFileOptimizer optimizer = plugin.getPaperManager().getRegionOptimizer();
        if (optimizer == null) {
            Msg.error(sender, "Region optimizer not available.");
            return true;
        }

        String action = args[2].toLowerCase();

        switch (action) {
            case "stats":
                RegionFileOptimizer.RegionStats stats = optimizer.getStatistics();
                Msg.header(sender, "Region File Statistics");
                Msg.kv(sender, "Total Regions", String.valueOf(stats.totalRegions));
                Msg.kv(sender, "Total Size", formatBytes(stats.totalSize));
                break;

            case "clean":
                World targetWorld = getTargetWorld(sender, args, 3);
                if (targetWorld == null) return true;

                Msg.info(sender, "Removing empty region files from §f" + targetWorld.getName() + "§f...");
                int removed = optimizer.removeEmptyRegions(targetWorld);
                Msg.success(sender, "Removed §f" + removed + " §aempty region files.");
                break;

            default:
                Msg.usage(sender, "/uo paper regions <stats|clean> [world]");
                break;
        }

        return true;
    }

    private World getTargetWorld(CommandSender sender, String[] args, int index) {
        if (args.length > index) {
            World world = Bukkit.getWorld(args[index]);
            if (world == null) {
                Msg.error(sender, "World not found: §f" + args[index]);
                return null;
            }
            return world;
        } else if (sender instanceof org.bukkit.entity.Player) {
            return ((org.bukkit.entity.Player) sender).getWorld();
        } else {
            Msg.error(sender, "Please specify a world name.");
            return null;
        }
    }

    private String formatTime(long millis) {
        if (millis < 1000) return millis + "ms";
        if (millis < 60000) return String.format("%.1fs", millis / 1000.0);
        return String.format("%.1fm", millis / 60000.0);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
