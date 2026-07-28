package me.rimuru.ultraoptimize.listeners;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.config.ConfigManager;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ItemSpawnEvent;

public class EntityListener implements Listener {

    private final UltraOptimize plugin;
    private final ConfigManager config;

    public EntityListener(UltraOptimize plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntitySpawn(CreatureSpawnEvent event) {
        if (!config.isLimitSpawns()) return;

        try {
            Entity entity = event.getEntity();
            if (entity == null) return;

            // Check if entity type is exempt
            if (config.getExemptEntities().contains(entity.getType())) {
                return;
            }

            // Check chunk entity limits
            if (!plugin.getEntityManager().canEntitySpawn(
                    event.getLocation().getChunk(),
                    event.getEntityType())) {
                event.setCancelled(true);
            }

        } catch (Exception e) {
            Logger.warning("Error handling entity spawn: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        try {
            // Entity tracking is refreshed on a timer (EntityManager's
            // entityCountTask) rather than here - this event can fire
            // extremely often (mining, farms), and updateEntityCounts() is a
            // full Bukkit.getWorlds()/world.getEntities() scan, so running it
            // per-event turned every item drop into a server-wide entity scan.

            // Auto-merge items if enabled
            if (config.isAutoMergeItems()) {
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (!event.getEntity().isDead()) {
                        plugin.getEntityManager().mergeNearbyItems(event.getEntity());
                    }
                }, 20L); // Delay by 1 second to allow items to settle
            }

        } catch (Exception e) {
            Logger.warning("Error handling item spawn: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        try {
            LivingEntity entity = event.getEntity();
            if (entity == null) return;

            // Remove drops if configured during optimization
            if (config.isRemoveDrops() &&
                    plugin.getPerformanceMonitor().getCurrentTPS() < config.getTpsThreshold()) {
                event.getDrops().clear();
                event.setDroppedExp(0);
            }

            // Entity tracking is refreshed on a timer (EntityManager's
            // entityCountTask) - see onItemSpawn above for why this isn't
            // done inline here.

        } catch (Exception e) {
            Logger.warning("Error handling entity death: " + e.getMessage());
        }
    }
}