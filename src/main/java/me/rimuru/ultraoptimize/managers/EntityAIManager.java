package me.rimuru.ultraoptimize.managers;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.config.ConfigManager;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reduces AI/pathfinding CPU cost by disabling awareness for mobs that are
 * outside advanced.pathfinding-limit blocks of every player.
 *
 * <p>Note the tradeoff: an unaware mob does not path, so mob farms and grinders
 * stop producing while their mobs are frozen, and frozen mobs accumulate rather
 * than wandering into despawn range. On a memory-constrained server that can
 * work against you. Raise pathfinding-limit or turn the feature off if entity
 * counts start climbing.
 */
public class EntityAIManager {

    private static final long DEFAULT_INTERVAL_TICKS = 20L; // 1 second

    private final UltraOptimize plugin;
    private final ConfigManager config;

    /**
     * Mobs this manager put to sleep. Restoration is limited to exactly these
     * UUIDs: the old code called setAware(true) on any unaware mob it met near
     * a player, and on every unaware mob in every world at shutdown, which
     * silently woke up NoAI mobs placed deliberately by map makers, spawn eggs,
     * or other plugins (Citizens-style NPCs, decorative mobs, arena setups).
     */
    private final Set<UUID> frozenMobs = ConcurrentHashMap.newKeySet();

    // Reused across the sweep so proximity checks allocate no Location objects.
    // The old isWithinRange() allocated two per mob per player per second.
    private final Location mobLocation = new Location(null, 0, 0, 0);
    private final Location playerLocation = new Location(null, 0, 0, 0);

    private BukkitTask task;

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
            task = null;
        }

        thawAll();
    }

    public void restart() {
        shutdown();
        start();
    }

    /** Re-awakens only the mobs this manager froze. */
    private void thawAll() {
        if (frozenMobs.isEmpty()) return;

        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Mob)) continue;
                if (!frozenMobs.contains(entity.getUniqueId())) continue;

                Mob mob = (Mob) entity;
                if (!mob.isAware()) {
                    mob.setAware(true);
                }
            }
        }

        frozenMobs.clear();
    }

    private void sweep() {
        int limit = config.getPathfindingLimit();
        double limitSquared = (double) limit * limit;
        int frozenCount = 0;

        for (World world : Bukkit.getWorlds()) {
            if (!isWorldEnabled(world.getName())) continue;

            List<Player> players = world.getPlayers();
            if (players.isEmpty()) continue; // nobody around to notice AI either way

            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Mob)) continue;
                Mob mob = (Mob) entity;

                if (config.getExemptEntities().contains(mob.getType())) continue;

                UUID id = mob.getUniqueId();
                boolean ours = frozenMobs.contains(id);

                // Never touch a mob that is already unaware for someone else's
                // reasons - that is somebody's NPC or decoration.
                if (!ours && !mob.isAware()) continue;

                if (isWithinRange(mob, players, limitSquared)) {
                    if (ours) {
                        mob.setAware(true);
                        frozenMobs.remove(id);
                    }
                } else {
                    if (!ours) {
                        mob.setAware(false);
                        frozenMobs.add(id);
                    }
                    frozenCount++;
                }
            }
        }

        // Drop bookkeeping for mobs that have since died or unloaded.
        if (frozenMobs.size() > frozenCount * 2 + 64) {
            frozenMobs.removeIf(id -> Bukkit.getEntity(id) == null);
        }
    }

    private boolean isWithinRange(Mob mob, List<Player> players, double limitSquared) {
        mob.getLocation(mobLocation);

        for (Player player : players) {
            player.getLocation(playerLocation);

            double dx = playerLocation.getX() - mobLocation.getX();
            double dy = playerLocation.getY() - mobLocation.getY();
            double dz = playerLocation.getZ() - mobLocation.getZ();

            if ((dx * dx) + (dy * dy) + (dz * dz) <= limitSquared) {
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

        return enabled.isEmpty() || enabled.contains(worldName);
    }

    public int getMobsFrozen() {
        return frozenMobs.size();
    }
}
