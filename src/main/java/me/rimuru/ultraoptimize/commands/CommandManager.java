package me.rimuru.ultraoptimize.commands;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.managers.*;
import me.rimuru.ultraoptimize.paper.PaperOptimizationManager;
import me.rimuru.ultraoptimize.paper.RegionFileOptimizer;
import me.rimuru.ultraoptimize.paper.WatchdogMonitor;
import me.rimuru.ultraoptimize.utils.Logger;
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
                    return handleGC(sender);

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
            sender.sendMessage("§c[UltraOptimize] Error: " + e.getMessage());
            Logger.severe("Command error: " + e.getMessage());
            e.printStackTrace();
            return true;
        }
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("ultraoptimize.reload")) {
            sender.sendMessage("§cNo permission!");
            return true;
        }

        plugin.reload();
        sender.sendMessage("§a[UltraOptimize] Configuration reloaded!");
        return true;
    }

    private boolean handleClear(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ultraoptimize.clear")) {
            sender.sendMessage("§cNo permission!");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage("§cUsage: /uo clear <items|mobs|all|xp|arrows>");
            return true;
        }

        EntityManager.EntityClearType type;
        try {
            type = EntityManager.EntityClearType.valueOf(args[1].toUpperCase());
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cInvalid type! Use: items, mobs, xp, arrows, or all");
            return true;
        }

        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            removed += plugin.getEntityManager().clearEntities(world, type);
        }

        sender.sendMessage("§a[UltraOptimize] Removed " + removed + " entities!");
        return true;
    }

    private boolean handleOptimize(CommandSender sender) {
        if (!sender.hasPermission("ultraoptimize.optimize")) {
            sender.sendMessage("§cNo permission!");
            return true;
        }

        sender.sendMessage("§a[UltraOptimize] Running manual optimization...");
        OptimizationManager.OptimizationResult result = plugin.getOptimizationManager().performManualOptimization();

        sender.sendMessage("§a[UltraOptimize] Optimization complete! Removed " +
                result.entitiesRemoved + " entities.");
        return true;
    }

    private boolean handleStats(CommandSender sender) {
        if (!sender.hasPermission("ultraoptimize.stats")) {
            sender.sendMessage("§cNo permission!");
            return true;
        }

        PerformanceMonitor.PerformanceReport report = plugin.getPerformanceMonitor().generateReport();
        Map<EntityType, Integer> entityCounts = plugin.getEntityManager().getEntityCounts();
        ChunkPreloader.PreloadStatistics preloadStats = plugin.getChunkPreloader().getStatistics();

        sender.sendMessage("§6§l╔═══════════════════════════╗");
        sender.sendMessage("§6§l║  UltraOptimize Statistics  ║");
        sender.sendMessage("§6§l╚═══════════════════════════╝");
        sender.sendMessage("§eCurrent TPS: §f" + String.format("%.2f", report.currentTPS) +
                " §7(Avg: " + String.format("%.2f", report.averageTPS) + ")");
        sender.sendMessage("§eMemory: §f" + report.memoryInfo.usedMemory + "MB §7/ §f" +
                report.memoryInfo.totalMemory + "MB §7/ §f" +
                report.memoryInfo.maxMemory + "MB");
        sender.sendMessage("§eMemory Usage: §f" + String.format("%.1f", report.memoryInfo.usagePercent) + "%");
        sender.sendMessage("§eTotal Entities: §f" + report.totalEntities);
        sender.sendMessage("§eLoaded Chunks: §f" + report.totalChunks);
        sender.sendMessage("§eSpawn Chunks Preloaded: §f" + preloadStats.chunksPreloaded);
        sender.sendMessage("§eCurrently Preloading: §f" + (preloadStats.isPreloading ? "§aYes" : "§cNo"));
        sender.sendMessage("§eAuto-Optimize: §f" + (plugin.getOptimizationManager().isAutoOptimizeEnabled() ? "§aEnabled" : "§cDisabled"));
        sender.sendMessage("§eSpawn Preloading: §f" + (plugin.getConfigManager().isChunkPreloadingEnabled() ? "§aEnabled" : "§cDisabled"));
        sender.sendMessage("§eTotal Optimizations: §f" + plugin.getStatisticsManager().getTotalOptimizations());
        sender.sendMessage("§eLifetime Entities Removed: §f" + plugin.getStatisticsManager().getEntitiesRemoved());
        sender.sendMessage("§eLifetime Items Merged: §f" + plugin.getStatisticsManager().getItemsMerged());
        sender.sendMessage("§eLifetime Chunks Preloaded: §f" + plugin.getStatisticsManager().getChunksPreloaded());
        if (plugin.getConfigManager().isOptimizeAI()) {
            sender.sendMessage("§eMobs with AI Paused: §f" + plugin.getEntityAIManager().getMobsFrozen());
        }

        sender.sendMessage("");
        sender.sendMessage("§6§lTop Entities:");
        entityCounts.entrySet().stream()
                .sorted(Map.Entry.<EntityType, Integer>comparingByValue().reversed())
                .limit(5)
                .forEach(entry -> sender.sendMessage("  §e" + entry.getKey() + ": §f" + entry.getValue()));

        return true;
    }

    private boolean handleChunks(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ultraoptimize.chunks")) {
            sender.sendMessage("§cNo permission!");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage("§cUsage: /uo chunks <unload|info>");
            return true;
        }

        String action = args[1].toLowerCase();

        if (action.equals("unload")) {
            int unloaded = plugin.getChunkManager().unloadEmptyChunks();
            sender.sendMessage("§a[UltraOptimize] Unloaded " + unloaded + " empty chunks!");
        } else if (action.equals("info")) {
            ChunkManager.ChunkStatistics stats = plugin.getChunkManager().getStatistics();
            sender.sendMessage("§6§l[Chunk Information]");
            sender.sendMessage("§eTotal Chunks: §f" + stats.totalChunks);
            sender.sendMessage("§eTracked Chunks: §f" + stats.trackedChunks);
            sender.sendMessage("§eProblematic Chunks: §f" + stats.problematicChunks);
        } else {
            sender.sendMessage("§cUsage: /uo chunks <unload|info>");
        }

        return true;
    }

    private boolean handlePreload(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ultraoptimize.preload")) {
            sender.sendMessage("§c[UltraOptimize] No permission!");
            return true;
        }

        if (!plugin.getConfigManager().isChunkPreloadingEnabled()) {
            sender.sendMessage("§c[UltraOptimize] Chunk preloading is disabled in config!");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage("§c[UltraOptimize] Usage: /uo preload <info|restart>");
            return true;
        }

        String action = args[1].toLowerCase();

        if (action.equals("info")) {
            ChunkPreloader.PreloadStatistics stats = plugin.getChunkPreloader().getStatistics();
            sender.sendMessage("§6§l[Spawn Chunk Preloading Statistics]");
            sender.sendMessage("§eTotal Chunks Preloaded: §f" + stats.chunksPreloaded);
            sender.sendMessage("§eCurrently Preloading: §f" + (stats.isPreloading ? "§aYes" : "§cNo"));
            sender.sendMessage("§ePreload Radius: §f" + plugin.getConfigManager().getPreloadRadius() + " chunks");
            sender.sendMessage("§eChunks/Tick: §f" + plugin.getConfigManager().getPreloadChunksPerTick());
            sender.sendMessage("§ePattern: §f" + (plugin.getConfigManager().isSpiralPattern() ? "Spiral" : "Square"));
        } else if (action.equals("restart")) {
            if (plugin.getChunkPreloader().isPreloading()) {
                sender.sendMessage("§c[UltraOptimize] Chunk preloading is already in progress!");
                return true;
            }
            sender.sendMessage("§a[UltraOptimize] Restarting spawn chunk preloading...");
            plugin.getChunkPreloader().restart();
        } else {
            sender.sendMessage("§c[UltraOptimize] Usage: /uo preload <info|restart>");
        }

        return true;
    }

    private boolean handleGC(CommandSender sender) {
        if (!sender.hasPermission("ultraoptimize.gc")) {
            sender.sendMessage("§cNo permission!");
            return true;
        }

        sender.sendMessage("§a[UltraOptimize] Running garbage collection...");
        plugin.getPerformanceMonitor().performGarbageCollection();
        return true;
    }

    private boolean handleAuto(CommandSender sender) {
        if (!sender.hasPermission("ultraoptimize.auto")) {
            sender.sendMessage("§cNo permission!");
            return true;
        }

        plugin.getOptimizationManager().toggleAutoOptimize();
        boolean enabled = plugin.getOptimizationManager().isAutoOptimizeEnabled();
        sender.sendMessage("§a[UltraOptimize] Auto-optimization " +
                (enabled ? "§aenabled" : "§cdisabled"));
        return true;
    }

    private boolean handleMerge(CommandSender sender) {
        if (!sender.hasPermission("ultraoptimize.merge")) {
            sender.sendMessage("§cNo permission!");
            return true;
        }

        sender.sendMessage("§a[UltraOptimize] Merging items and XP orbs...");
        int merged = 0;
        for (World world : Bukkit.getWorlds()) {
            merged += plugin.getEntityManager().optimizeWorld(world);
        }
        sender.sendMessage("§a[UltraOptimize] Merged " + merged + " entities!");
        return true;
    }

    private boolean handleView(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ultraoptimize.view")) {
            sender.sendMessage("§cNo permission!");
            return true;
        }

        if (!plugin.getPerformanceMonitor().isViewDistanceSupported()) {
            sender.sendMessage("§c[UltraOptimize] View distance management is not supported on this server version!");
            sender.sendMessage("§7Requires Minecraft 1.14 or newer.");
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage("§cUsage: /uo view <world> <distance>");
            return true;
        }

        World world = Bukkit.getWorld(args[1]);
        if (world == null) {
            sender.sendMessage("§cWorld not found!");
            return true;
        }

        try {
            int distance = Integer.parseInt(args[2]);

            if (distance < 2 || distance > 32) {
                sender.sendMessage("§cView distance must be between 2 and 32!");
                return true;
            }

            plugin.getPerformanceMonitor().setWorldViewDistance(world, distance);
            sender.sendMessage("§a[UltraOptimize] Set view distance to " + distance +
                    " for " + world.getName());
        } catch (NumberFormatException e) {
            sender.sendMessage("§cInvalid distance!");
        }

        return true;
    }

    private boolean handleReport(CommandSender sender) {
        if (!sender.hasPermission("ultraoptimize.report")) {
            sender.sendMessage("§cNo permission!");
            return true;
        }

        generateReport(sender);
        return true;
    }

    private boolean handleInfo(CommandSender sender) {
        if (!sender.hasPermission("ultraoptimize.info")) {
            sender.sendMessage("§cNo permission!");
            return true;
        }

        showPluginInfo(sender);
        return true;
    }

    private void generateReport(CommandSender sender) {
        sender.sendMessage("§a[UltraOptimize] Generating performance report...");

        PerformanceMonitor.PerformanceReport report = plugin.getPerformanceMonitor().generateReport();
        ChunkPreloader.PreloadStatistics preloadStats = plugin.getChunkPreloader().getStatistics();

        StringBuilder output = new StringBuilder();
        output.append("\n§6§l╔═══════════════════════════════════╗\n");
        output.append("§6§l║    Performance Analysis Report    ║\n");
        output.append("§6§l╚═══════════════════════════════════╝\n\n");

        output.append("§e§lServer Performance:\n");
        output.append("  §7TPS: §f").append(String.format("%.2f", report.currentTPS));
        if (report.currentTPS < 15) output.append(" §c(CRITICAL)");
        else if (report.currentTPS < 18) output.append(" §e(WARNING)");
        else output.append(" §a(GOOD)");
        output.append("\n  §7Avg TPS: §f").append(String.format("%.2f", report.averageTPS)).append("\n\n");

        output.append("§e§lMemory Usage:\n");
        output.append("  §7Used: §f").append(report.memoryInfo.usedMemory).append("MB / ")
                .append(report.memoryInfo.maxMemory).append("MB");
        output.append(" §7(").append(String.format("%.1f", report.memoryInfo.usagePercent)).append("%)");
        if (report.memoryInfo.usagePercent > 90) output.append(" §c(CRITICAL)");
        else if (report.memoryInfo.usagePercent > 75) output.append(" §e(WARNING)");
        else output.append(" §a(GOOD)");
        output.append("\n\n");

        output.append("§e§lWorld Statistics:\n");
        output.append("  §7Total Entities: §f").append(report.totalEntities);
        if (report.totalEntities > 5000) output.append(" §e(HIGH)");
        output.append("\n  §7Loaded Chunks: §f").append(report.totalChunks).append("\n");
        output.append("  §7Preloaded Chunks: §f").append(preloadStats.preloadedChunksCount).append("\n");
        output.append("  §7Entities/Chunk: §f").append(String.format("%.2f", report.entitiesPerChunk)).append("\n\n");

        output.append("§e§lChunk Preloading:\n");
        output.append("  §7Status: ").append(plugin.getConfigManager().isChunkPreloadingEnabled() ? "§aEnabled" : "§cDisabled").append("\n");
        output.append("  §7Queue Size: §f").append(preloadStats.queueSize).append("\n");
        output.append("  §7Session Preloaded: §f").append(preloadStats.chunksPreloaded).append("\n\n");

        output.append("§e§lRecommendations:\n");
        if (report.currentTPS < 18) {
            output.append("  §c⚠ Low TPS detected. Consider:\n");
            output.append("    §7- Running /uo optimize\n");
            output.append("    §7- Reducing entity limits\n");
            output.append("    §7- Lowering preload radius\n");
        }
        if (report.memoryInfo.usagePercent > 80) {
            output.append("  §c⚠ High memory usage. Consider:\n");
            output.append("    §7- Running /uo gc\n");
            output.append("    §7- Reducing preload radius\n");
        }
        if (report.currentTPS >= 19 && report.memoryInfo.usagePercent < 70) {
            output.append("  §a✓ Server is running optimally!\n");
        }

        sender.sendMessage(output.toString());
    }

    private void showPluginInfo(CommandSender sender) {
        sender.sendMessage("§6§l╔═══════════════════════════╗");
        sender.sendMessage("§6§l║   UltraOptimize v" + plugin.getDescription().getVersion() + "      ║");
        sender.sendMessage("§6§l╚═══════════════════════════╝");
        sender.sendMessage("§eStatus: §aRunning");
        sender.sendMessage("§eAuto-Optimize: " + (plugin.getOptimizationManager().isAutoOptimizeEnabled() ? "§aEnabled" : "§cDisabled"));
        sender.sendMessage("§eChunk Preloading: " + (plugin.getConfigManager().isChunkPreloadingEnabled() ? "§aEnabled" : "§cDisabled"));
        sender.sendMessage("§ePreload Radius: §f" + plugin.getConfigManager().getPreloadRadius() + " chunks");
        sender.sendMessage("§eOptimization Interval: §f" + plugin.getConfigManager().getAutoOptimizeInterval() + "s");
        sender.sendMessage("§eTPS Threshold: §f" + plugin.getConfigManager().getTpsThreshold());
        sender.sendMessage("§eView Distance Control: " + (plugin.getPerformanceMonitor().isViewDistanceSupported() ? "§aSupported" : "§cNot Supported (1.14+ required)"));
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage("§6§l╔═══════════════════════════╗");
        sender.sendMessage("§6§l║   UltraOptimize Commands   ║");
        sender.sendMessage("§6§l╚═══════════════════════════╝");
        sender.sendMessage("§e/uo reload §7- Reload configuration");
        sender.sendMessage("§e/uo clear <type> §7- Clear entities (items/mobs/all/xp/arrows)");
        sender.sendMessage("§e/uo optimize §7- Run manual optimization");
        sender.sendMessage("§e/uo stats §7- Show detailed statistics");
        sender.sendMessage("§e/uo chunks <action> §7- Manage chunks (unload/info)");
        sender.sendMessage("§e/uo preload <action> §7- Chunk preloading (info/restart)");
        sender.sendMessage("§e/uo gc §7- Run garbage collection");
        sender.sendMessage("§e/uo auto §7- Toggle auto-optimization");
        sender.sendMessage("§e/uo merge §7- Merge nearby items/xp");
        if (plugin.getPerformanceMonitor().isViewDistanceSupported()) {
            sender.sendMessage("§e/uo view <world> <dist> §7- Set view distance (1.14+)");
        }
        sender.sendMessage("§e/uo report §7- Generate performance report");
        sender.sendMessage("§e/uo info §7- Show plugin configuration");

        // Paper-specific commands
        if (plugin.getPaperManager().isPaperDetected()) {
            sender.sendMessage("");
            sender.sendMessage("§6§lPaper Commands:");
            sender.sendMessage("§e/uo paper stats §7- Paper optimization stats");
            sender.sendMessage("§e/uo paper optimize §7- Run Paper optimization");
            sender.sendMessage("§e/uo paper watchdog <action> §7- Watchdog management");
            sender.sendMessage("§e/uo paper regions <action> §7- Region file management");
        }
    }
    private boolean handlePaper(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ultraoptimize.paper")) {
            sender.sendMessage("§cNo permission!");
            return true;
        }

        if (!plugin.getPaperManager().isPaperDetected()) {
            sender.sendMessage("§c[UltraOptimize] Not running on Paper server!");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage("§cUsage: /uo paper <stats|optimize|watchdog|regions>");
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
                sender.sendMessage("§cUsage: /uo paper <stats|optimize|watchdog|regions>");
                return true;
        }
    }

    private boolean handlePaperStats(CommandSender sender) {
        PaperOptimizationManager.PaperStats stats = plugin.getPaperManager().getStatistics();

        sender.sendMessage("§6§l╔══════════════════════════╗");
        sender.sendMessage("§6§l║  Paper Optimization Stats  ║");
        sender.sendMessage("§6§l╚══════════════════════════╝");

        sender.sendMessage("§eServer Version: §f" + stats.serverVersion);
        sender.sendMessage("");

        // Chunk System Stats
        if (stats.chunkSystemStats != null) {
            sender.sendMessage("§6§lChunk System:");
            sender.sendMessage("  §eActive Tickets: §f" + stats.chunkSystemStats.activeTickets);
            sender.sendMessage("  §eTracked Chunks: §f" + stats.chunkSystemStats.trackedChunks);
            sender.sendMessage("  §ePriority Chunks: §f" + stats.chunkSystemStats.priorityChunks);
            sender.sendMessage("  §ePaper Support: §f" + (stats.chunkSystemStats.paperSupported ? "§aYes" : "§cNo"));

            if (!stats.chunkSystemStats.ticketsByType.isEmpty()) {
                sender.sendMessage("  §eTickets by Type:");
                stats.chunkSystemStats.ticketsByType.forEach((type, count) ->
                        sender.sendMessage("    §7" + type + ": §f" + count));
            }
            sender.sendMessage("");
        }

        // Watchdog Stats
        if (stats.watchdogStats != null) {
            sender.sendMessage("§6§lWatchdog Monitor:");
            sender.sendMessage("  §eTotal Hangs: §f" + stats.watchdogStats.totalHangs);
            sender.sendMessage("  §eTotal Hang Time: §f" + formatTime(stats.watchdogStats.totalHangTime));
            sender.sendMessage("  §eAverage Hang: §f" + stats.watchdogStats.averageHangDuration + "ms");
            sender.sendMessage("  §eEmergency Mode: §f" + (stats.watchdogStats.emergencyMode ? "§cACTIVE" : "§aInactive"));
            sender.sendMessage("  §ePaper Watchdog: §f" + (stats.watchdogStats.paperWatchdogSupported ? "§aYes" : "§cNo"));

            if (!stats.watchdogStats.hangCauses.isEmpty()) {
                sender.sendMessage("  §eHang Causes:");
                stats.watchdogStats.hangCauses.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .limit(5)
                        .forEach(entry -> sender.sendMessage("    §7" + entry.getKey() + ": §f" + entry.getValue()));
            }
            sender.sendMessage("");
        }

        // Region File Stats
        if (stats.regionStats != null) {
            sender.sendMessage("§6§lRegion Files:");
            sender.sendMessage("  §eTotal Regions: §f" + stats.regionStats.totalRegions);
            sender.sendMessage("  §eTotal Size: §f" + formatBytes(stats.regionStats.totalSize));
            sender.sendMessage("  §eOptimized: §f" + stats.regionStats.optimizedRegions);
            sender.sendMessage("  §eBytes Freed: §f" + formatBytes(stats.regionStats.bytesFreed));
            sender.sendMessage("  §eIncremental Save: §f" + (stats.regionStats.incrementalSaving ? "§aEnabled" : "§cDisabled"));

            if (!stats.regionStats.sizeByWorld.isEmpty()) {
                sender.sendMessage("  §eSize by World:");
                stats.regionStats.sizeByWorld.forEach((world, size) ->
                        sender.sendMessage("    §7" + world + ": §f" + formatBytes(size) +
                                " (§e" + stats.regionStats.countByWorld.get(world) + " §7regions)"));
            }
        }

        return true;
    }

    private boolean handlePaperOptimize(CommandSender sender) {
        sender.sendMessage("§a[UltraOptimize] Starting Paper optimization...");

        PaperOptimizationManager.PaperOptimizationResult result =
                plugin.getPaperManager().performOptimization();

        if (result.success) {
            sender.sendMessage("§a[UltraOptimize] Paper optimization complete!");
            sender.sendMessage("§eRegions Optimized: §f" + result.regionsOptimized);
            sender.sendMessage("§eEmpty Regions Removed: §f" + result.emptyRegionsRemoved);
            sender.sendMessage("§eBytes Freed: §f" + formatBytes(result.bytesFreed));
            sender.sendMessage("§eDuration: §f" + result.duration + "ms");
        } else {
            sender.sendMessage("§c[UltraOptimize] Optimization failed: " + result.error);
        }

        return true;
    }

    private boolean handleWatchdog(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /uo paper watchdog <status|reset|emergency>");
            return true;
        }

        WatchdogMonitor monitor = plugin.getPaperManager().getWatchdogMonitor();
        if (monitor == null) {
            sender.sendMessage("§cWatchdog monitor not available!");
            return true;
        }

        String action = args[2].toLowerCase();

        switch (action) {
            case "status":
                WatchdogMonitor.WatchdogStats stats = monitor.getStatistics();
                sender.sendMessage("§6§l[Watchdog Status]");
                sender.sendMessage("§eTotal Hangs: §f" + stats.totalHangs);
                sender.sendMessage("§eCurrent Hang Count: §f" + stats.currentHangCount);
                sender.sendMessage("§eEmergency Mode: §f" + (stats.emergencyMode ? "§cACTIVE" : "§aInactive"));

                if (!stats.recentHangs.isEmpty()) {
                    sender.sendMessage("§eRecent Hangs:");
                    stats.recentHangs.stream().limit(5).forEach(hang ->
                            sender.sendMessage("  §7" + hang.toString()));
                }
                break;

            case "reset":
                monitor.resetHangCount();
                sender.sendMessage("§a[UltraOptimize] Watchdog hang counter reset");
                break;

            case "emergency":
                if (monitor.isEmergencyMode()) {
                    sender.sendMessage("§c[UltraOptimize] Server is currently in EMERGENCY MODE");
                    sender.sendMessage("§7Emergency mode will deactivate when performance improves");
                } else {
                    sender.sendMessage("§a[UltraOptimize] Server is not in emergency mode");
                }
                break;

            default:
                sender.sendMessage("§cUsage: /uo paper watchdog <status|reset|emergency>");
                break;
        }

        return true;
    }

    private boolean handleRegions(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /uo paper regions <stats|optimize|clean> [world]");
            return true;
        }

        RegionFileOptimizer optimizer = plugin.getPaperManager().getRegionOptimizer();
        if (optimizer == null) {
            sender.sendMessage("§cRegion optimizer not available!");
            return true;
        }

        String action = args[2].toLowerCase();

        switch (action) {
            case "stats":
                RegionFileOptimizer.RegionStats stats = optimizer.getStatistics();
                sender.sendMessage("§6§l[Region File Statistics]");
                sender.sendMessage("§eTotal Regions: §f" + stats.totalRegions);
                sender.sendMessage("§eTotal Size: §f" + formatBytes(stats.totalSize));
                sender.sendMessage("§eOptimized: §f" + stats.optimizedRegions);
                sender.sendMessage("§eBytes Freed (lifetime): §f" + formatBytes(stats.bytesFreed));
                break;

            case "optimize":
                World world = getTargetWorld(sender, args, 3);
                if (world == null) return true;

                sender.sendMessage("§a[UltraOptimize] Optimizing region files for " + world.getName() + "...");
                RegionFileOptimizer.OptimizationResult result = optimizer.optimizeRegionFiles(world);

                sender.sendMessage("§a[UltraOptimize] Region optimization complete:");
                sender.sendMessage("§eFiles Processed: §f" + result.filesProcessed);
                sender.sendMessage("§eFiles Optimized: §f" + result.filesOptimized);
                sender.sendMessage("§eBytes Freed: §f" + formatBytes(result.bytesFreed));
                sender.sendMessage("§eDuration: §f" + result.duration + "ms");
                break;

            case "clean":
                World targetWorld = getTargetWorld(sender, args, 3);
                if (targetWorld == null) return true;

                sender.sendMessage("§a[UltraOptimize] Removing empty region files from " + targetWorld.getName() + "...");
                int removed = optimizer.removeEmptyRegions(targetWorld);
                sender.sendMessage("§a[UltraOptimize] Removed " + removed + " empty region files");
                break;

            default:
                sender.sendMessage("§cUsage: /uo paper regions <stats|optimize|clean> [world]");
                break;
        }

        return true;
    }

    private World getTargetWorld(CommandSender sender, String[] args, int index) {
        if (args.length > index) {
            World world = Bukkit.getWorld(args[index]);
            if (world == null) {
                sender.sendMessage("§cWorld not found: " + args[index]);
                return null;
            }
            return world;
        } else if (sender instanceof org.bukkit.entity.Player) {
            return ((org.bukkit.entity.Player) sender).getWorld();
        } else {
            sender.sendMessage("§cPlease specify a world name");
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