package me.rimuru.ultraoptimize.listeners;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.config.ConfigManager;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.Location;
import org.bukkit.block.Hopper;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Throttles hopper item transfers based on performance.hopper-tick-rate.
 * Vanilla hoppers transfer every 8 ticks (400ms) by default, so a tick-rate
 * of 8 reproduces vanilla behavior; raising it spaces transfers further
 * apart to cut down on inventory-move processing in hopper-heavy builds.
 */
public class HopperListener implements Listener {

    private static final long TICK_MILLIS = 50L;
    private static final long STALE_ENTRY_MILLIS = 300_000L; // 5 minutes

    private final UltraOptimize plugin;
    private final ConfigManager config;
    private final Map<String, Long> lastTransfer;
    private BukkitTask cleanupTask;

    public HopperListener(UltraOptimize plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.lastTransfer = new ConcurrentHashMap<>();
    }

    public void start() {
        cleanupTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::cleanupStaleEntries, 20L * 300, 20L * 300);
    }

    public void shutdown() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }
        lastTransfer.clear();
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (!config.isOptimizeHoppers()) return;

        try {
            String key = getHolderKey(event.getSource().getHolder());
            if (key == null) return;

            int tickRate = Math.max(1, config.getHopperTickRate());
            long minIntervalMillis = tickRate * TICK_MILLIS;
            long now = System.currentTimeMillis();

            Long last = lastTransfer.get(key);
            if (last != null && now - last < minIntervalMillis) {
                event.setCancelled(true);
                return;
            }

            lastTransfer.put(key, now);

        } catch (Exception e) {
            Logger.warning("Error throttling hopper transfer: " + e.getMessage());
        }
    }

    private String getHolderKey(InventoryHolder holder) {
        if (holder instanceof Hopper) {
            Location loc = ((Hopper) holder).getLocation();
            if (loc.getWorld() == null) return null;
            return "block_" + loc.getWorld().getName() + "_" +
                    loc.getBlockX() + "_" + loc.getBlockY() + "_" + loc.getBlockZ();
        }
        if (holder instanceof HopperMinecart) {
            return "cart_" + ((HopperMinecart) holder).getUniqueId();
        }
        return null;
    }

    private void cleanupStaleEntries() {
        long now = System.currentTimeMillis();
        lastTransfer.entrySet().removeIf(entry -> now - entry.getValue() > STALE_ENTRY_MILLIS);
    }
}
