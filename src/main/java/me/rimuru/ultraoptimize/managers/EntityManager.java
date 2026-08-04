package me.rimuru.ultraoptimize.managers;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.config.ConfigManager;
import me.rimuru.ultraoptimize.utils.Keys;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class EntityManager {

    // Safety sweep only. pendingChunkMerges entries are released by the merge
    // task itself one second after they are claimed; this exists purely so a
    // slot cannot survive an unexpected task failure.
    private static final long PENDING_SWEEP_INTERVAL_TICKS = 20L * 300; // 5 minutes

    private final UltraOptimize plugin;
    private final ConfigManager config;

    // Chunks with an item-merge sweep already scheduled, keyed by world UID
    // then packed chunk coordinate. A hopper, farm, or explosion can drop
    // dozens of items in the same chunk within a single tick; without this,
    // ItemSpawnEvent would schedule one delayed merge task - each doing its own
    // getNearbyEntities() scan - per item instead of one task per chunk.
    private final Map<UUID, Set<Long>> pendingChunkMerges;

    private BukkitTask pendingSweepTask;

    public EntityManager(UltraOptimize plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.pendingChunkMerges = new ConcurrentHashMap<>();
    }

    public void start() {
        pendingSweepTask = new BukkitRunnable() {
            @Override
            public void run() {
                pendingChunkMerges.clear();
            }
        }.runTaskTimer(plugin, PENDING_SWEEP_INTERVAL_TICKS, PENDING_SWEEP_INTERVAL_TICKS);
    }

    public void shutdown() {
        if (pendingSweepTask != null) {
            pendingSweepTask.cancel();
            pendingSweepTask = null;
        }
        pendingChunkMerges.clear();
    }

    public int optimizeWorld(World world) {
        if (world == null) return 0;

        int removed = 0;
        // Grouped by packed chunk key rather than by Chunk object. The old code
        // called entity.getLocation().getChunk() per entity, which allocated a
        // fresh Location AND did a chunk lookup for every entity in the world on
        // every optimization pass. Entity#getChunk() would be the direct
        // replacement but is not present in every API version, so this fills one
        // reused Location and derives the chunk coordinates arithmetically.
        Map<Long, List<Entity>> chunkEntities = new HashMap<>();
        Location cursor = new Location(null, 0, 0, 0);

        try {
            for (Entity entity : world.getEntities()) {
                if (isEntityExempt(entity)) continue;

                entity.getLocation(cursor);
                long key = Keys.chunk(cursor.getBlockX() >> 4, cursor.getBlockZ() >> 4);
                chunkEntities.computeIfAbsent(key, k -> new ArrayList<>()).add(entity);
            }

            for (List<Entity> entities : chunkEntities.values()) {
                removed += processChunkEntities(entities);
            }

        } catch (Exception e) {
            Logger.severe("Error optimizing world " + world.getName() + ": " + e.getMessage());
            e.printStackTrace();
        }

        return removed;
    }

    private int processChunkEntities(List<Entity> entities) {
        int removed = 0;

        try {
            List<Item> items = new ArrayList<>();
            List<ExperienceOrb> expOrbs = new ArrayList<>();
            List<Arrow> arrows = new ArrayList<>();
            List<Monster> monsters = new ArrayList<>();

            for (Entity entity : entities) {
                if (entity instanceof Item) {
                    items.add((Item) entity);
                } else if (entity instanceof ExperienceOrb) {
                    expOrbs.add((ExperienceOrb) entity);
                } else if (entity instanceof Arrow) {
                    arrows.add((Arrow) entity);
                } else if (entity instanceof Monster) {
                    monsters.add((Monster) entity);
                }
            }

            if (config.isAutoMergeItems()) {
                removed += mergeItems(items);
                removed += mergeExperienceOrbs(expOrbs);
            }

            removed += removeStuckArrows(arrows);

            if (items.size() > config.getMaxItemsPerChunk()) {
                removed += removeExcessItems(items, items.size() - config.getMaxItemsPerChunk());
            }

            if (monsters.size() > config.getMaxEntitiesPerChunk() && config.isClearLagging()) {
                removed += removeExcessMobs(monsters, monsters.size() - config.getMaxEntitiesPerChunk());
            }

        } catch (Exception e) {
            Logger.warning("Error processing chunk entities: " + e.getMessage());
        }

        return removed;
    }

    /**
     * Merges stackable items that are within entities.item-merge-radius of one
     * another.
     *
     * <p>The previous implementation sorted by {@code x + z} and broke out of
     * the inner loop once that sum exceeded the radius. {@code x + z} is not a
     * proximity ordering - two items twenty blocks apart on the anti-diagonal
     * share a key - so the break both terminated early (missing valid merges)
     * and ran long, degrading to O(n^2). Sorting by X alone gives a break
     * condition that is actually sound: once the X gap exceeds the radius, no
     * later item in the list can be in range.
     *
     * <p>Positions are also snapshotted once per item instead of calling
     * {@code getLocation()} twice per comparison, which allocated two Location
     * objects per pair.
     */
    public int mergeItems(List<Item> items) {
        if (items == null || items.size() < 2) return 0;

        int merged = 0;
        double radius = config.getItemMergeRadius();
        double radiusSquared = radius * radius;

        try {
            Map<Material, List<Positioned>> itemsByType = new HashMap<>();

            for (Item item : items) {
                if (item == null || item.isDead()) continue;
                ItemStack stack = item.getItemStack();
                if (stack == null) continue;

                itemsByType.computeIfAbsent(stack.getType(), k -> new ArrayList<>())
                        .add(new Positioned(item));
            }

            for (List<Positioned> group : itemsByType.values()) {
                if (group.size() < 2) continue;

                group.sort(Comparator.comparingDouble(p -> p.x));

                for (int i = 0; i < group.size(); i++) {
                    Positioned first = group.get(i);
                    if (first.item.isDead()) continue;

                    for (int j = i + 1; j < group.size(); j++) {
                        Positioned second = group.get(j);
                        if (second.item.isDead()) continue;

                        // Sorted ascending by X, so nothing further along can
                        // be in range once this gap is exceeded.
                        if (second.x - first.x > radius) break;

                        if (first.distanceSquaredTo(second) > radiusSquared) continue;

                        if (mergeItemPair(first.item, second.item)) {
                            merged++;
                            if (first.item.isDead()) break;
                        }
                    }
                }
            }

        } catch (Exception e) {
            Logger.warning("Error merging items: " + e.getMessage());
        }

        if (merged > 0) {
            plugin.getStatisticsManager().incrementItemsMerged(merged);
        }
        return merged;
    }

    /**
     * Attempts to reserve a merge sweep for the given chunk. Returns true if
     * this call claimed the slot, false if one is already scheduled.
     */
    public boolean claimChunkMergeSlot(World world, int chunkX, int chunkZ) {
        if (world == null) return false;
        return pendingChunkMerges
                .computeIfAbsent(world.getUID(), k -> ConcurrentHashMap.newKeySet())
                .add(Keys.chunk(chunkX, chunkZ));
    }

    public void releaseChunkMergeSlot(World world, int chunkX, int chunkZ) {
        if (world == null) return;
        Set<Long> chunks = pendingChunkMerges.get(world.getUID());
        if (chunks != null) {
            chunks.remove(Keys.chunk(chunkX, chunkZ));
        }
    }

    /**
     * Merges items near the given one.
     *
     * <p>The per-location rate limit that used to guard this was a
     * {@code Map<Location, Long>}. Location hashes on exact doubles plus yaw and
     * pitch and holds a strong reference to its World, and item.getLocation()
     * returns a fresh object with continuous coordinates - so the lookup never
     * hit (the rate limit did nothing) while every merge inserted a permanent
     * new key. The per-chunk claim above already debounces bursts, so the map is
     * gone entirely.
     */
    public void mergeNearbyItems(Item item) {
        if (item == null || item.isDead()) return;

        try {
            double radius = config.getItemMergeRadius();
            List<Entity> nearby = item.getNearbyEntities(radius, radius, radius);

            List<Item> items = new ArrayList<>(nearby.size() + 1);
            items.add(item);
            for (Entity entity : nearby) {
                if (entity instanceof Item) {
                    items.add((Item) entity);
                }
            }

            mergeItems(items);

        } catch (Exception e) {
            Logger.warning("Error merging nearby items: " + e.getMessage());
        }
    }

    /**
     * Merges two item stacks if and only if they combine into a single legal
     * stack.
     *
     * <p>The old code accepted pairs up to {@code maxStackSize * 2} and then
     * "partially merged" them - rewriting both stacks, removing nothing, and
     * returning false. Nothing converged: the same pair was reconsidered on
     * every subsequent pass forever.
     */
    private boolean mergeItemPair(Item first, Item second) {
        try {
            ItemStack stack1 = first.getItemStack();
            ItemStack stack2 = second.getItemStack();

            if (stack1 == null || stack2 == null) return false;
            if (!stack1.isSimilar(stack2)) return false;

            int total = stack1.getAmount() + stack2.getAmount();
            if (total > stack1.getMaxStackSize()) return false;

            stack1.setAmount(total);
            first.setItemStack(stack1);
            second.remove();
            return true;

        } catch (Exception e) {
            return false;
        }
    }

    public int mergeExperienceOrbs(List<ExperienceOrb> orbs) {
        if (orbs == null || orbs.size() < 2) return 0;

        int merged = 0;

        try {
            List<Positioned> positioned = new ArrayList<>(orbs.size());
            for (ExperienceOrb orb : orbs) {
                if (orb != null && !orb.isDead()) {
                    positioned.add(new Positioned(orb));
                }
            }

            // Same sound break condition as mergeItems: sorted by X.
            positioned.sort(Comparator.comparingDouble(p -> p.x));

            for (int i = 0; i < positioned.size(); i++) {
                Positioned first = positioned.get(i);
                if (first.entity.isDead()) continue;

                for (int j = i + 1; j < positioned.size(); j++) {
                    Positioned second = positioned.get(j);
                    if (second.entity.isDead()) continue;

                    if (second.x - first.x > 1.0) break;
                    if (first.distanceSquaredTo(second) > 1.0) continue;

                    ExperienceOrb orb1 = (ExperienceOrb) first.entity;
                    ExperienceOrb orb2 = (ExperienceOrb) second.entity;

                    orb1.setExperience(orb1.getExperience() + orb2.getExperience());
                    orb2.remove();
                    merged++;
                }
            }

        } catch (Exception e) {
            Logger.warning("Error merging experience orbs: " + e.getMessage());
        }

        return merged;
    }

    private int removeStuckArrows(List<Arrow> arrows) {
        int removed = 0;

        try {
            for (Arrow arrow : arrows) {
                if (arrow == null || arrow.isDead()) continue;

                if (arrow.isInBlock() || arrow.getTicksLived() > 6000) { // 5 minutes
                    arrow.remove();
                    removed++;
                }
            }
        } catch (Exception e) {
            Logger.warning("Error removing stuck arrows: " + e.getMessage());
        }

        return removed;
    }

    private int removeExcessItems(List<Item> items, int toRemove) {
        int removed = 0;

        try {
            // Oldest first.
            items.sort(Comparator.comparingInt(Item::getTicksLived).reversed());

            for (int i = 0; i < toRemove && i < items.size(); i++) {
                Item item = items.get(i);
                if (item != null && !item.isDead()) {
                    item.remove();
                    removed++;
                }
            }
        } catch (Exception e) {
            Logger.warning("Error removing excess items: " + e.getMessage());
        }

        return removed;
    }

    private int removeExcessMobs(List<Monster> monsters, int toRemove) {
        int removed = 0;

        try {
            monsters.sort((m1, m2) -> {
                int priority1 = getMobPriority(m1.getType());
                int priority2 = getMobPriority(m2.getType());
                if (priority1 != priority2) {
                    return Integer.compare(priority1, priority2);
                }
                return Double.compare(m1.getHealth(), m2.getHealth());
            });

            for (int i = 0; i < toRemove && i < monsters.size(); i++) {
                Monster mob = monsters.get(i);
                if (mob != null && !mob.isDead() && getMobPriority(mob.getType()) < 10) {
                    mob.remove();
                    removed++;
                }
            }
        } catch (Exception e) {
            Logger.warning("Error removing excess mobs: " + e.getMessage());
        }

        return removed;
    }

    private int getMobPriority(EntityType type) {
        // Lower priority = removed first. 10 means never remove.
        switch (type) {
            case CREEPER:
            case ENDERMAN:
                return 2;
            case BLAZE:
            case GHAST:
                return 3;
            case WITHER:
            case ENDER_DRAGON:
                return 10;
            default:
                return 1;
        }
    }

    /**
     * Clears entities of the given category.
     *
     * <p>ALL is a whitelist, not "everything that is not a Player". The old
     * inverted test deleted chest, hopper and furnace minecarts and their
     * contents, tamed wolves and cats, saddled horses, and llamas with cargo -
     * and relied on entities.exempt-types to save them, which silently fails
     * whenever a name in that list is not a valid EntityType on the running
     * version (BOAT, for one, no longer exists as of 1.21.2).
     */
    public int clearEntities(World world, EntityClearType type) {
        int removed = 0;

        try {
            List<Entity> toRemove = new ArrayList<>();

            for (Entity entity : world.getEntities()) {
                if (isEntityExempt(entity)) continue;
                if (entity instanceof Player) continue;

                boolean shouldRemove;

                switch (type) {
                    case ITEMS:
                        shouldRemove = entity instanceof Item;
                        break;
                    case MOBS:
                        shouldRemove = entity instanceof Monster;
                        break;
                    case XP:
                        shouldRemove = entity instanceof ExperienceOrb;
                        break;
                    case ARROWS:
                        shouldRemove = entity instanceof Arrow;
                        break;
                    case ALL:
                        shouldRemove = entity instanceof Item
                                || entity instanceof ExperienceOrb
                                || entity instanceof Projectile
                                || entity instanceof Monster;
                        break;
                    default:
                        shouldRemove = false;
                        break;
                }

                if (shouldRemove) {
                    toRemove.add(entity);
                }
            }

            for (Entity entity : toRemove) {
                entity.remove();
                removed++;
            }

        } catch (Exception e) {
            Logger.severe("Error clearing entities: " + e.getMessage());
            e.printStackTrace();
        }

        return removed;
    }

    public boolean isEntityExempt(Entity entity) {
        if (entity == null) return true;
        return config.getExemptEntities().contains(entity.getType());
    }

    /**
     * Whether another mob may spawn in this chunk.
     *
     * <p>Counts living non-player entities, which is what
     * entities.max-per-chunk actually means. The old version counted only
     * entities of the incoming type but compared that against the overall
     * per-chunk cap, so the limit never bound in mixed-mob chunks.
     */
    public boolean canEntitySpawn(Chunk chunk) {
        try {
            int count = 0;
            for (Entity entity : chunk.getEntities()) {
                if (entity instanceof LivingEntity && !(entity instanceof Player)) {
                    count++;
                }
            }

            return count < config.getMaxEntitiesPerChunk();

        } catch (Exception e) {
            return true; // Safe default - allow spawn on error
        }
    }

    /**
     * Counts entities by type across all worlds.
     *
     * <p>Computed on demand. This used to run on a 5-second timer to keep a
     * cached map warm for the cosmetic "Top Entities" block in /uo stats -
     * a full Bukkit.getWorlds()/world.getEntities() walk, allocating a fresh
     * list of every entity on the server, twelve times a minute, for a display
     * nobody was looking at.
     */
    public Map<EntityType, Integer> getEntityCounts() {
        Map<EntityType, Integer> counts = new EnumMap<>(EntityType.class);

        try {
            for (World world : Bukkit.getWorlds()) {
                for (Entity entity : world.getEntities()) {
                    counts.merge(entity.getType(), 1, Integer::sum);
                }
            }
        } catch (Exception e) {
            Logger.warning("Error counting entities: " + e.getMessage());
        }

        return counts;
    }

    public int getTotalEntities() {
        int total = 0;
        for (World world : Bukkit.getWorlds()) {
            total += world.getEntities().size();
        }
        return total;
    }

    public enum EntityClearType {
        ITEMS, MOBS, XP, ARROWS, ALL
    }

    /**
     * An entity plus a snapshot of its position, so proximity checks don't
     * allocate a Location per comparison.
     */
    private static final class Positioned {
        final Entity entity;
        final Item item;
        final double x;
        final double y;
        final double z;

        Positioned(Item item) {
            this.entity = item;
            this.item = item;
            org.bukkit.Location loc = item.getLocation();
            this.x = loc.getX();
            this.y = loc.getY();
            this.z = loc.getZ();
        }

        Positioned(ExperienceOrb orb) {
            this.entity = orb;
            this.item = null;
            org.bukkit.Location loc = orb.getLocation();
            this.x = loc.getX();
            this.y = loc.getY();
            this.z = loc.getZ();
        }

        double distanceSquaredTo(Positioned other) {
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return (dx * dx) + (dy * dy) + (dz * dz);
        }
    }
}
