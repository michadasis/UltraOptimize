package me.rimuru.ultraoptimize.managers;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.config.ConfigManager;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

/**
 * Reduces AI/pathfinding CPU cost by disabling awareness for mobs that are
 * outside advanced.pathfinding-limit blocks of every player.
 *
 * <p>Note the tradeoff: an unaware mob does not path, so mob farms and grinders
 * stop producing while their mobs are frozen, and frozen mobs accumulate rather
 * than wandering into despawn range. On a memory-constrained server that can
 * work against you. Raise pathfinding-limit or turn the feature off if entity
 * counts start climbing.
 *
 * <p>Ownership of "which mobs did this plugin freeze" is recorded with a
 * {@code PersistentDataContainer} tag on the mob itself, not an in-memory
 * collection. An in-memory {@code Set<UUID>} was tried first and removed: the
 * moment a frozen mob's UUID fell out of that set while the mob was still
 * unaware - its chunk unloading mid-sweep, a plugin reload, a server restart,
 * anything that clears or desyncs plugin memory without also touching the
 * mob - the sweep below permanently disowned it. That "never touch a mob we
 * don't remember freezing" rule is deliberate and stays: it is what keeps
 * this feature from re-awakening NoAI mobs placed by map makers, spawn eggs,
 * or other plugins. A tag stored on the mob's own persisted data survives
 * every one of those disruptions that plugin memory cannot, so ownership can
 * no longer drift out of sync with reality.
 */
public class EntityAIManager {

    private static final long DEFAULT_INTERVAL_TICKS = 20L; // 1 second

    private final UltraOptimize plugin;
    private final ConfigManager config;
    private final NamespacedKey frozenKey;

    // Reused across the sweep so proximity checks allocate no Location objects.
    // The old isWithinRange() allocated two per mob per player per second.
    private final Location mobLocation = new Location(null, 0, 0, 0);
    private final Location playerLocation = new Location(null, 0, 0, 0);

    private BukkitTask task;

    public EntityAIManager(UltraOptimize plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.frozenKey = new NamespacedKey(plugin, "ai-frozen");
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

    /** Re-awakens every mob still carrying this plugin's frozen-by-us tag. */
    private void thawAll() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Mob)) continue;
                Mob mob = (Mob) entity;

                if (!mob.getPersistentDataContainer().has(frozenKey, PersistentDataType.BYTE)) continue;

                if (!mob.isAware()) {
                    mob.setAware(true);
                }
                mob.getPersistentDataContainer().remove(frozenKey);
            }
        }
    }

    private void sweep() {
        int limit = config.getPathfindingLimit();
        double limitSquared = (double) limit * limit;

        for (World world : Bukkit.getWorlds()) {
            if (!isWorldEnabled(world.getName())) continue;

            List<Player> players = world.getPlayers();
            if (players.isEmpty()) continue; // nobody around to notice AI either way

            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Mob)) continue;
                Mob mob = (Mob) entity;

                if (config.getExemptEntities().contains(mob.getType())) continue;

                boolean ours = mob.getPersistentDataContainer().has(frozenKey, PersistentDataType.BYTE);

                // Never touch a mob that is already unaware for someone else's
                // reasons - that is somebody's NPC or decoration.
                if (!ours && !mob.isAware()) continue;

                if (isWithinRange(mob, players, limitSquared)) {
                    if (ours) {
                        mob.setAware(true);
                        mob.getPersistentDataContainer().remove(frozenKey);
                    }
                } else {
                    // Re-check isAware() even for mobs we already believe are
                    // frozen: setAware() is a transient flag, not persisted to
                    // NBT, so a chunk unload/reload cycle can reset it to true
                    // on the reloaded entity while our tag (which IS persisted)
                    // still says "ours". Without this check that mob stays
                    // awake - defeating the optimization - until it wanders
                    // back within range on its own.
                    if (!ours || mob.isAware()) {
                        mob.setAware(false);
                        mob.getPersistentDataContainer().set(frozenKey, PersistentDataType.BYTE, (byte) 1);
                    }
                }
            }
        }
    }

    /**
     * Sets every currently-unaware mob in every world back to aware, regardless
     * of whether this plugin's tag says it froze it.
     *
     * <p>Manual recovery tool only - never called automatically. It cannot
     * distinguish a mob stuck by the pre-tag version of this feature from one
     * a map maker, spawn egg, or another plugin (Citizens NPCs, arena setups)
     * deliberately left unaware, so it re-awakens both. Use it once to clear
     * mobs stuck from before this fix; ordinary operation never needs it,
     * since the sweep above now self-heals through restarts and reloads.
     */
    public int unstickAll() {
        int count = 0;

        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Mob)) continue;
                Mob mob = (Mob) entity;

                if (mob.isAware()) continue;

                mob.setAware(true);
                mob.getPersistentDataContainer().remove(frozenKey);
                count++;
            }
        }

        return count;
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

    /**
     * Counts currently-frozen mobs on demand by scanning every entity's tag.
     *
     * <p>This used to be an O(1) read of the in-memory tracking set's size.
     * Losing that is the deliberate tradeoff for the correctness fix above -
     * this is only ever called from "/uo stats", an on-demand admin command,
     * never from a hot path, so an O(entities) scan here costs nothing that
     * matters.
     */
    public int getMobsFrozen() {
        int count = 0;

        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Mob &&
                        ((Mob) entity).getPersistentDataContainer().has(frozenKey, PersistentDataType.BYTE)) {
                    count++;
                }
            }
        }

        return count;
    }
}
