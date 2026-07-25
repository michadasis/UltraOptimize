package me.rimuru.ultraoptimize.managers;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.config.ConfigManager;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

/**
 * Reduces AI/pathfinding CPU cost by disabling awareness (goal and
 * pathfinding processing) for mobs that are outside advanced.pathfinding-limit
 * blocks of every player, per advanced.optimize-ai. The sweep interval is
 * derived from advanced.entity-tick-rate when advanced.optimize-entity-ticking
 * is enabled, otherwise it runs once a second.
 */
public class EntityAIManager {

    private static final long DEFAULT_INTERVAL_TICKS = 20L; // 1 second

    private final UltraOptimize plugin;
    private final ConfigManager config;

    private BukkitTask task;
    private int mobsFrozen;
    private int mobsAwakened;

    public EntityAIManager(UltraOptimize plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
    }

    public void start() {
        if (!config.isOptimizeAI()) {
            Logger.info("AI pathfinding optimization disabled in config");
            return;
        }

        long interval = config.isOptimizeEntityTicking()
                ? Math.max(1, config.getEntityTickRate()) * DEFAULT_INTERVAL_TICKS
                : DEFAULT_INTERVAL_TICKS;

        task = new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    sweep();
                } catch (Exception e) {
                    Logger.warning("Error during AI optimization sweep: " + e.getMessage());
                }
            }
        }.runTaskTimer(plugin, interval, interval);

        Logger.info("AI pathfinding optimization started (limit: " + config.getPathfindingLimit() +
                " blocks, interval: " + interval + " ticks)");
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
        }

        // Re-awaken anything we froze so behavior doesn't change if the
        // feature gets disabled or the plugin reloads/unloads.
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Mob && !((Mob) entity).isAware()) {
                    ((Mob) entity).setAware(true);
                }
            }
        }
    }

    public void restart() {
        shutdown();
        start();
    }

    private void sweep() {
        int limit = config.getPathfindingLimit();
        mobsFrozen = 0;
        mobsAwakened = 0;

        for (World world : Bukkit.getWorlds()) {
            if (!isWorldEnabled(world.getName())) continue;

            List<Player> players = world.getPlayers();
            if (players.isEmpty()) continue; // nobody around to notice AI either way

            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Mob)) continue;
                Mob mob = (Mob) entity;

                if (config.getExemptEntities().contains(mob.getType())) continue;

                boolean nearPlayer = isWithinRange(mob, players, limit);

                if (!nearPlayer && mob.isAware()) {
                    mob.setAware(false);
                    mobsFrozen++;
                } else if (nearPlayer && !mob.isAware()) {
                    mob.setAware(true);
                    mobsAwakened++;
                }
            }
        }
    }

    private boolean isWithinRange(Mob mob, List<Player> players, int limit) {
        double limitSquared = (double) limit * limit;
        for (Player player : players) {
            if (player.getLocation().distanceSquared(mob.getLocation()) <= limitSquared) {
                return true;
            }
        }
        return false;
    }

    private boolean isWorldEnabled(String worldName) {
        List<String> enabled = config.getEnabledWorlds();
        List<String> excluded = config.getExcludedWorlds();

        if (!excluded.isEmpty() && excluded.contains(worldName)) {
            return false;
        }

        if (!enabled.isEmpty() && !enabled.contains(worldName)) {
            return false;
        }

        return true;
    }

    public int getMobsFrozen() {
        return mobsFrozen;
    }

    public int getMobsAwakened() {
        return mobsAwakened;
    }
}
