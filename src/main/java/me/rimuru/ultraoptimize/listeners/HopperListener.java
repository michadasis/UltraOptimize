package me.rimuru.ultraoptimize.listeners;

import me.rimuru.ultraoptimize.UltraOptimize;
import me.rimuru.ultraoptimize.config.ConfigManager;
import me.rimuru.ultraoptimize.utils.Keys;
import me.rimuru.ultraoptimize.utils.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Hopper;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Throttles hopper item transfers based on performance.hopper-tick-rate.
 *
 * <p><b>Read this before enabling it.</b> Vanilla sets a hopper's 8-tick
 * transfer cooldown only when a transfer <i>succeeds</i>. Cancelling
 * InventoryMoveItemEvent means no cooldown gets set, so the hopper retries on
 * the very next tick instead of in 8 - which means a throttled hopper does
 * <i>more</i> work, and fires more events, than an unthrottled one. There is no
 * way to fix that from the Bukkit API; the vanilla cooldown is only settable
 * from inside the server's own transfer code.
 *
 * <p>This is therefore off by default and only worth enabling at rates well
 * above vanilla's 8 ticks, where the reduction in actual item movement (and the
 * downstream entity/inventory work it causes) outweighs the extra retries.
 * It will also break item sorters and any build that relies on hopper timing.
 */
public class HopperListener implements Listener {

    private static final long TICK_MILLIS = 50L;
    private static final long STALE_ENTRY_MILLIS = 300_000L; // 5 minutes
    private static final long CLEANUP_INTERVAL_TICKS = 20L * 300;

    /**
     * Server ticks are nominally 50ms but jitter by a few ms either way, so a
     * strict "now - last < rate * 50" test cancels a random fraction of
     * transfers that vanilla had already spaced correctly - which is what made
     * item sorters fail intermittently at the default rate of 8. Allow one
     * tick of slack so only genuinely-early transfers are throttled.
     */
    private static final long JITTER_TOLERANCE_MILLIS = TICK_MILLIS;

    private final UltraOptimize plugin;
    private final ConfigManager config;

    // Keyed by world UID, then by packed block coordinate - no per-event
    // String allocation. Hopper minecarts move, so they are keyed by entity ID.
    private final Map<UUID, Map<Long, Long>> lastBlockTransfer;
    private final Map<UUID, Long> lastCartTransfer;

    private BukkitTask cleanupTask;

    public HopperListener(UltraOptimize plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.lastBlockTransfer = new ConcurrentHashMap<>();
        this.lastCartTransfer = new ConcurrentHashMap<>();
    }

    public void start() {
        cleanupTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::cleanupStaleEntries, CLEANUP_INTERVAL_TICKS, CLEANUP_INTERVAL_TICKS);
    }

    public void shutdown() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        lastBlockTransfer.clear();
        lastCartTransfer.clear();
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (!config.isOptimizeHoppers()) return;

        try {
            int tickRate = Math.max(1, config.getHopperTickRate());
            long minIntervalMillis = (tickRate * TICK_MILLIS) - JITTER_TOLERANCE_MILLIS;
            if (minIntervalMillis <= 0) return; // rate at or below vanilla - nothing to do

            long now = System.currentTimeMillis();
            InventoryHolder holder = event.getSource().getHolder();

            if (holder instanceof Hopper) {
                Location loc = ((Hopper) holder).getLocation();
                World world = loc.getWorld();
                if (world == null) return;

                Map<Long, Long> worldMap = lastBlockTransfer.computeIfAbsent(
                        world.getUID(), k -> new ConcurrentHashMap<>());
                long key = Keys.block(loc);

                Long last = worldMap.get(key);
                if (last != null && now - last < minIntervalMillis) {
                    event.setCancelled(true);
                    return;
                }
                worldMap.put(key, now);

            } else if (holder instanceof HopperMinecart) {
                UUID id = ((HopperMinecart) holder).getUniqueId();

                Long last = lastCartTransfer.get(id);
                if (last != null && now - last < minIntervalMillis) {
                    event.setCancelled(true);
                    return;
                }
                lastCartTransfer.put(id, now);
            }

        } catch (Exception e) {
            Logger.warning("Error throttling hopper transfer: " + e.getMessage());
        }
    }

    private void cleanupStaleEntries() {
        long now = System.currentTimeMillis();

        for (Map<Long, Long> worldMap : lastBlockTransfer.values()) {
            worldMap.entrySet().removeIf(entry -> now - entry.getValue() > STALE_ENTRY_MILLIS);
        }
        lastBlockTransfer.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        lastCartTransfer.entrySet().removeIf(entry -> now - entry.getValue() > STALE_ENTRY_MILLIS);
    }
}
