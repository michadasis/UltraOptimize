# UltraOptimize

UltraOptimize is a Spigot/Paper plugin that helps low end and mid size Minecraft servers keep their TPS up. It watches entity counts, chunk activity and memory, and steps in with cleanup and throttling when things start to get out of hand. Some features go further on Paper, since Paper exposes APIs that plain Spigot just doesn't have.

## What it actually does

**Auto optimization.** When TPS drops below a threshold you set, it runs a cleanup pass automatically. You can also trigger one manually with `/uo optimize`.

**Entity handling.** Caps entities and items per chunk, merges nearby dropped items so they don't pile up individually, and lets you clear specific categories (items, mobs, XP orbs, arrows) on demand. Certain entity types (villagers, armor stands, item frames, etc.) are exempt by default so you don't accidentally wipe out builds.

**Chunk management.** Unloads empty chunks around players (with a configurable radius so you don't unload chunks people are actually standing near), and preloads chunks around spawn on startup so players don't hit a wall of unloaded terrain the moment they join. Preloading can run in a spiral or a simple square pattern.

**Redstone and hopper throttling.** Redstone updates get rate limited per location instead of firing constantly, and hopper transfers are throttled to a configurable tick rate (defaults to vanilla's normal 8 tick pace, so this only matters if you turn it down further).

**AI/pathfinding pause.** Mobs far from every player have their AI and pathfinding paused, since there's no point spending CPU on a zombie pathing toward nobody.

**View distance control.** Can adjust view distance automatically based on TPS. This needs Paper (or a Paper based fork like Purpur), because the API for changing view distance at runtime simply doesn't exist on plain Spigot, at any version. There's a short cooldown on adjustments so it doesn't flap the view distance up and down when TPS is hovering right at the threshold.

### Paper only

* A chunk ticket system to keep important chunks (like spawn) loaded reliably, plus urgent loading for high priority chunks.
* A watchdog that watches for server hangs and can kick off an emergency optimization pass if things get bad enough.
* Incremental world saving, and a command to clean up empty region files.

A couple of things that used to be here got pulled. There was a live region file defragmentation feature, but it rewrote `.mca` files on disk while the world was still loaded, and there was no reliable way to make the server's own region cache aware of that afterward. A save landing at the wrong moment could corrupt world data, so it's gone rather than just disabled by default. Particle reduction and lighting optimization are also not implemented. Bukkit and Paper don't currently expose a general way to hook into either of those, so the config keys exist for forward compatibility but don't do anything yet.

## Requirements

* Minecraft 1.13+. Older versions (1.8.9 through 1.12) haven't been tested and might not work.
* Spigot or Paper, or a compatible fork. Paper is recommended since you get the extra features.
* Java 25. The plugin is built with `--release 25`, so anything older can't load the jar.

`plugin.yml` declares `api-version: 26.2`. If you're running a Paper fork rather than Paper itself, the Paper specific features won't activate unless the underlying APIs actually exist on that server, not just the Paper API surface.

## Installation

1. Grab `UltraOptimize.jar` from the releases page.
2. Drop it in your server's `plugins` folder.
3. Start or restart the server.
4. Edit `plugins/UltraOptimize/config.yml` to taste.
5. Run `/uo reload` to pick up the changes without restarting.

## Configuration

A basic setup looks something like this:

```yaml
auto-optimize:
  enabled: true
  interval: 300        # seconds between optimization checks
  tps-threshold: 18    # run optimization when TPS falls below this

entities:
  max-per-chunk: 50
  max-items-per-chunk: 100
  auto-merge-items: true
  item-merge-radius: 1.5
  exempt-types:
    - VILLAGER
    - ARMOR_STAND
    - ITEM_FRAME

chunk-preloading:
  enabled: true
  preload-radius: 5    # chunks around spawn
  chunks-per-tick: 3   # loading speed
  spiral-pattern: true # spiral vs square
  generate-chunks: false

chunks:
  unload-empty: true
  aggressive-unload: true
  unload-interval: 300
  unload-radius: 8     # don't unload chunks this close to players

performance:
  optimize-redstone: true
  optimize-hoppers: true
  max-hoppers-per-chunk: 10
  hopper-tick-rate: 8

advanced:
  async-chunks: true
  optimize-ai: true
  optimize-view-distance: false
  auto-view-distance: false
  min-view-distance: 3
  max-view-distance: 10
```

The full `config.yml` that ships with the plugin has more knobs than this and is commented, so it's worth a read if you want to fine tune something specific.

## Commands

`/uo` or `/ultraoptimize` on its own shows the help menu.

**General**
* `/uo reload` reloads the config.
* `/uo stats` shows current TPS, memory, entity counts, and (if AI pausing is on) how many mobs currently have their AI paused.
* `/uo info` shows the plugin's active configuration.
* `/uo report` runs a performance analysis and tries to point at the likely bottleneck.

**Optimization**
* `/uo optimize` runs a manual pass right now.
* `/uo auto` toggles automatic optimization on or off.
* `/uo gc` forces a garbage collection.
* `/uo merge` merges nearby items and XP orbs immediately.

**Entities**
* `/uo clear items` / `/uo clear mobs` / `/uo clear xp` / `/uo clear arrows` remove that specific category.
* `/uo clear all` removes everything except players.

**Chunks**
* `/uo chunks unload` unloads empty chunks right now.
* `/uo chunks info` shows chunk counts, including how many look problematic.
* `/uo preload info` shows preload status and settings.
* `/uo preload restart` restarts spawn preloading.

**View distance (Paper only)**
* `/uo view <world> <distance>` sets the view distance for a world.

**Paper only**
* `/uo paper stats`, `/uo paper optimize`
* `/uo paper watchdog status`, `/uo paper watchdog reset`
* `/uo paper regions stats`, `/uo paper regions clean [world]`

## Permissions

`ultraoptimize.*` grants everything. Individually: `reload`, `clear`, `optimize`, `stats`, `chunks`, `preload`, `gc`, `auto`, `merge`, `view`, `report`, `info`, `notify`, and `paper` (for the Paper only commands). They all default to op except the base `ultraoptimize.use`, which defaults to true so anyone can see the help menu.

## Statistics

The plugin keeps a running lifetime total of optimizations run, entities removed, items merged, and chunks unloaded and preloaded, plus your average TPS for the current session. These totals get written to `plugins/UltraOptimize/lifetime-stats.yml` every five minutes and reloaded on startup, so a crash won't wipe them out the way an in memory only counter would. A readable summary of each session also gets appended to `plugins/UltraOptimize/statistics.log` on shutdown.

## Tuning by server size

These are starting points, not rules. Adjust based on what `/uo report` and `/uo stats` actually show you.

**Small (1 to 10 players)**
```yaml
auto-optimize:
  interval: 600
  tps-threshold: 17
chunk-preloading:
  preload-radius: 3
  chunks-per-tick: 5
chunks:
  unload-radius: 6
```

**Medium (10 to 50 players)**
```yaml
auto-optimize:
  interval: 300
  tps-threshold: 18
chunk-preloading:
  preload-radius: 5
  chunks-per-tick: 3
chunks:
  unload-radius: 8
```

**Large (50+ players)**
```yaml
auto-optimize:
  interval: 180
  tps-threshold: 19
chunk-preloading:
  preload-radius: 7
  chunks-per-tick: 2
chunks:
  unload-radius: 10
  max-loaded: 5000
```

If you're on Paper, it's generally worth turning on `async-chunks` and `optimize-ai` regardless of server size, since neither costs you much and both help.

## Troubleshooting

**Chunks aren't preloading.** Check `/uo preload info`, confirm `chunk-preloading.enabled` is true in the config, look at the console for errors, and try `/uo preload restart`.

**Memory usage is high.** Lower the preload radius, turn on `chunks.aggressive-unload`, cap `max-loaded`, or just run `/uo gc`.

**TPS is low even with optimization running.** Check `/uo report` for what it thinks the bottleneck is, look at entity counts with `/uo stats`, consider dropping the view distance, and check `/uo chunks info` for problem chunks.

**Paper features aren't doing anything.** Confirm you're actually on Paper with `/version`, not just a fork that claims Paper API compatibility. Check the startup console log for confirmation the plugin detected Paper. A few features also need a specific Paper version.

**View distance says it's not supported.** Run `/uo info` and read the reason it gives. If it says you need Paper, that's because plain Spigot has no runtime API for this at any Minecraft version, so you'd need to switch to Paper or a fork like Purpur. If you're already confirmed on Paper, check the startup console log for the specific reason.

## Monitoring

`/uo stats` is the quickest way to check in: TPS (want it above 19 ideally), memory usage (under 80% is a reasonable target), total entity count, loaded chunks against your configured max, and which entity types are spiking.

If TPS craters, a reasonable order of operations is: run `/uo optimize` first, check `/uo report` to figure out why, run `/uo clear mobs` if the mob count looks high, run `/uo gc` if memory is the problem, and restart the server if none of that helps.
