package me.rimuru.ultraoptimize.managers;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.config.ConfigManager;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class EntityManager {

    private static final long CLEANUP_INTERVAL_TICKS = 20L * 300; // 5 minutes
    // entityCounts backs the purely cosmetic "Top Entities" section of /uo
    // stats, so it doesn't need per-event freshness - refreshing it on a
    // short timer instead of inline in event handlers is what keeps
    // updateEntityCounts()'s full Bukkit.getWorlds()/world.getEntities()
    // scan off the hot path (see EntityListener).
    private static final long ENTITY_COUNT_INTERVAL_TICKS = 100L; // 5 seconds

    private final UltraOptimize plugin;
    private final ConfigManager config;

    private final Map<EntityType, Integer> entityCounts;
    private final Map<Location, Long> lastMergeTime;

    private BukkitTask cleanupTask;
    private BukkitTask entityCountTask;

    public EntityManager(UltraOptimize plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.entityCounts = new ConcurrentHashMap<>();
        this.lastMergeTime = new ConcurrentHashMap<>();
    }

    /**
     * Starts a periodic sweep of lastMergeTime. Without this, entries only
     * get pruned as a side effect of optimizeWorld() running (auto-optimize
     * triggering or a manual /uo optimize), so a healthy server that never
     * dips below the TPS threshold would otherwise grow this map forever as
     * items merge. Also starts the periodic entityCounts refresh.
     */
    public void start() {
        cleanupTask = new BukkitRunnable() {
            @Override
            public void run() {
                cleanupMergeTimeCache();
            }
        }.runTaskTimer(plugin, CLEANUP_INTERVAL_TICKS, CLEANUP_INTERVAL_TICKS);

        entityCountTask = new BukkitRunnable() {
            @Override
            public void run() {
                updateEntityCounts();
            }
        }.runTaskTimer(plugin, ENTITY_COUNT_INTERVAL_TICKS, ENTITY_COUNT_INTERVAL_TICKS);
    }

    public void shutdown() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }
        if (entityCountTask != null) {
            entityCountTask.cancel();
        }
    }

    public int optimizeWorld(World world) {
        if (world == null) return 0;

        int removed = 0;
        Map<Chunk, List<Entity>> chunkEntities = new HashMap<>();

        try {
            // Group entities by chunk for efficient processing
            for (Entity entity : world.getEntities()) {
                if (!isEntityExempt(entity)) {
                    Chunk chunk = entity.getLocation().getChunk();
                    chunkEntities.computeIfAbsent(chunk, k -> new ArrayList<>()).add(entity);
                }
            }

            // Process each chunk
            for (Map.Entry<Chunk, List<Entity>> entry : chunkEntities.entrySet()) {
                removed += processChunkEntities(entry.getKey(), entry.getValue());
            }

            // Clean up old merge time records
            cleanupMergeTimeCache();

        } catch (Exception e) {
            Logger.severe("Error optimizing world " + world.getName() + ": " + e.getMessage());
            e.printStackTrace();
        }

        return removed;
    }

    private int processChunkEntities(Chunk chunk, List<Entity> entities) {
        int removed = 0;

        try {
            List<Item> items = new ArrayList<>();
            List<ExperienceOrb> expOrbs = new ArrayList<>();
            List<Arrow> arrows = new ArrayList<>();
            List<Monster> monsters = new ArrayList<>();
            int itemCount = 0;
            int mobCount = 0;

            // Categorize entities
            for (Entity entity : entities) {
                if (entity instanceof Item) {
                    items.add((Item) entity);
                    itemCount++;
                } else if (entity instanceof ExperienceOrb) {
                    expOrbs.add((ExperienceOrb) entity);
                } else if (entity instanceof Arrow) {
                    arrows.add((Arrow) entity);
                } else if (entity instanceof Monster) {
                    monsters.add((Monster) entity);
                    mobCount++;
                }
            }

            // Merge nearby items and XP orbs
            if (config.isAutoMergeItems()) {
                removed += mergeItems(items);
                removed += mergeExperienceOrbs(expOrbs);
            }

            // Remove stuck arrows
            removed += removeStuckArrows(arrows);

            // Remove excess items
            if (itemCount > config.getMaxItemsPerChunk()) {
                int toRemove = itemCount - config.getMaxItemsPerChunk();
                removed += removeExcessItems(items, toRemove);
            }

            // Remove excess mobs if lagging
            if (mobCount > config.getMaxEntitiesPerChunk() && config.isClearLagging()) {
                int toRemove = mobCount - config.getMaxEntitiesPerChunk();
                removed += removeExcessMobs(monsters, toRemove);
            }

        } catch (Exception e) {
            Logger.warning("Error processing chunk entities: " + e.getMessage());
        }

        return removed;
    }

    public int mergeItems(List<Item> items) {
        if (items == null || items.size() < 2) return 0;

        int merged = 0;
        Map<Material, List<Item>> itemsByType = new HashMap<>();

        try {
            // Group items by material type
            for (Item item : items) {
                if (item == null || item.isDead()) continue;
                ItemStack stack = item.getItemStack();
                if (stack == null) continue;

                itemsByType.computeIfAbsent(stack.getType(), k -> new ArrayList<>()).add(item);
            }

            // Merge similar items that are close together
            for (List<Item> similarItems : itemsByType.values()) {
                if (similarItems.size() < 2) continue;

                // Sort by location for efficient proximity checking
                similarItems.sort(Comparator.comparingDouble(item ->
                        item.getLocation().getX() + item.getLocation().getZ()));

                for (int i = 0; i < similarItems.size(); i++) {
                    Item item1 = similarItems.get(i);
                    if (item1 == null || item1.isDead()) continue;

                    for (int j = i + 1; j < similarItems.size(); j++) {
                        Item item2 = similarItems.get(j);
                        if (item2 == null || item2.isDead()) continue;

                        // Break if items are too far apart (optimization)
                        double distance = item1.getLocation().distance(item2.getLocation());
                        if (distance > config.getItemMergeRadius() * 2) break;

                        if (canMergeItems(item1, item2)) {
                            if (mergeItemPair(item1, item2)) {
                                merged++;
                            }
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

    public void mergeNearbyItems(Item item) {
        if (item == null || item.isDead()) return;

        try {
            Location loc = item.getLocation();

            // Prevent too frequent merging at same location
            Long lastMerge = lastMergeTime.get(loc);
            if (lastMerge != null && System.currentTimeMillis() - lastMerge < 1000) {
                return;
            }

            List<Entity> nearby = item.getNearbyEntities(
                    config.getItemMergeRadius(),
                    config.getItemMergeRadius(),
                    config.getItemMergeRadius()
            );

            List<Item> items = nearby.stream()
                    .filter(e -> e instanceof Item)
                    .map(e -> (Item) e)
                    .collect(Collectors.toList());

            items.add(item);

            if (mergeItems(items) > 0) {
                lastMergeTime.put(loc, System.currentTimeMillis());
            }

        } catch (Exception e) {
            Logger.warning("Error merging nearby items: " + e.getMessage());
        }
    }

    private boolean canMergeItems(Item item1, Item item2) {
        try {
            double distance = item1.getLocation().distance(item2.getLocation());
            if (distance > config.getItemMergeRadius()) {
                return false;
            }

            ItemStack stack1 = item1.getItemStack();
            ItemStack stack2 = item2.getItemStack();

            // Check if items are similar and stackable
            if (!stack1.isSimilar(stack2)) {
                return false;
            }

            // Don't merge if combined would exceed max stack
            int total = stack1.getAmount() + stack2.getAmount();
            return total <= stack1.getMaxStackSize() * 2; // Allow some overflow for optimization

        } catch (Exception e) {
            return false;
        }
    }

    private boolean mergeItemPair(Item item1, Item item2) {
        try {
            ItemStack stack1 = item1.getItemStack();
            ItemStack stack2 = item2.getItemStack();

            int total = stack1.getAmount() + stack2.getAmount();
            int maxStack = stack1.getMaxStackSize();

            if (total <= maxStack) {
                // Merge completely
                stack1.setAmount(total);
                item1.setItemStack(stack1);
                item2.remove();
                return true;
            } else {
                // Merge partially
                stack1.setAmount(maxStack);
                stack2.setAmount(total - maxStack);
                item1.setItemStack(stack1);
                item2.setItemStack(stack2);
                return false;
            }

        } catch (Exception e) {
            return false;
        }
    }

    public int mergeExperienceOrbs(List<ExperienceOrb> orbs) {
        if (orbs == null || orbs.size() < 2) return 0;

        int merged = 0;

        try {
            // Sort by location for efficient processing
            orbs.sort(Comparator.comparingDouble(orb ->
                    orb.getLocation().getX() + orb.getLocation().getZ()));

            for (int i = 0; i < orbs.size(); i++) {
                ExperienceOrb orb1 = orbs.get(i);
                if (orb1 == null || orb1.isDead()) continue;

                for (int j = i + 1; j < orbs.size(); j++) {
                    ExperienceOrb orb2 = orbs.get(j);
                    if (orb2 == null || orb2.isDead()) continue;

                    double distance = orb1.getLocation().distance(orb2.getLocation());

                    // Break if orbs are too far (optimization)
                    if (distance > 2.0) break;

                    if (distance < 1.0) {
                        orb1.setExperience(orb1.getExperience() + orb2.getExperience());
                        orb2.remove();
                        merged++;
                    }
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

                // Remove arrows that are stuck in ground/blocks
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
            // Sort by age (remove oldest first)
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
            // Sort by type (remove common mobs first) and health
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
                if (mob != null && !mob.isDead()) {
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
        // Lower priority = removed first
        switch (type) {
            case ZOMBIE:
            case SKELETON:
            case SPIDER:
                return 1; // Common mobs
            case CREEPER:
            case ENDERMAN:
                return 2; // Less common
            case BLAZE:
            case GHAST:
                return 3; // Nether mobs
            case WITHER:
            case ENDER_DRAGON:
                return 10; // Bosses (never remove)
            default:
                return 1;
        }
    }

    public int clearEntities(World world, EntityClearType type) {
        int removed = 0;

        try {
            List<Entity> toRemove = new ArrayList<>();

            for (Entity entity : world.getEntities()) {
                if (isEntityExempt(entity)) continue;

                boolean shouldRemove = false;

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
                        shouldRemove = !(entity instanceof Player);
                        break;
                }

                if (shouldRemove) {
                    toRemove.add(entity);
                }
            }

            // Remove in batches for better performance
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

    public void updateEntityCounts() {
        entityCounts.clear();

        try {
            for (World world : Bukkit.getWorlds()) {
                for (Entity entity : world.getEntities()) {
                    entityCounts.merge(entity.getType(), 1, Integer::sum);
                }
            }
        } catch (Exception e) {
            Logger.warning("Error updating entity counts: " + e.getMessage());
        }
    }

    public boolean isEntityExempt(Entity entity) {
        if (entity == null) return true;
        return config.getExemptEntities().contains(entity.getType());
    }

    public boolean canEntitySpawn(Chunk chunk, EntityType type) {
        try {
            int count = 0;
            for (Entity entity : chunk.getEntities()) {
                if (entity.getType() == type) {
                    count++;
                }
            }

            return count < config.getMaxEntitiesPerChunk();

        } catch (Exception e) {
            return true; // Safe default - allow spawn on error
        }
    }

    private void cleanupMergeTimeCache() {
        long now = System.currentTimeMillis();
        lastMergeTime.entrySet().removeIf(entry -> now - entry.getValue() > 60000); // 1 minute
    }

    public Map<EntityType, Integer> getEntityCounts() {
        return new HashMap<>(entityCounts);
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
}