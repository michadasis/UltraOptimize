# UltraOptimize

UltraOptimize is a comprehensive optimization plugin for Minecraft servers running Spigot or Paper. It automatically manages chunks, entities, and general server performance to help keep gameplay smooth under load.

## Features

### Core Optimization

* Automatic optimization that removes excess entities when TPS drops below a configured threshold.
* Smart entity management that merges nearby items and experience orbs to reduce lag.
* Intelligent chunk loading and unloading, including aggressive cleanup of empty chunks.
* Real time performance monitoring, covering TPS tracking and memory usage.
* Spawn chunk preloading in either a spiral or square pattern.

### Paper Specific Features

When running on a Paper server, UltraOptimize enables the following additional systems:

* An advanced chunk system using plugin chunk tickets and urgent chunk loading.
* A watchdog monitor that detects server hangs and can trigger an emergency mode.
* Region file optimization that defragments region files through sector level compaction (chunk data itself is never touched) and removes empty region files.
* Incremental saving to reduce lag caused by world saves.

### Entity Optimization

* Clears items, hostile mobs, experience orbs, and arrows on demand.
* Automatically merges nearby items within a configurable radius.
* Allows specific entity types to be exempted from removal.
* Enforces per chunk entity limits, including blocking new spawns once a chunk is full.

### Chunk Features

* Aggressively unloads chunks that are empty.
* Configurable unload radius around players.
* Async chunk loading support on 1.13 and newer.
* Chunk preloading with a configurable pattern.
* An optional maximum on the number of loaded chunks.

### Performance Features

* Redstone optimization that limits excessive updates using a per location rolling time window.
* Hopper optimization that throttles item transfers per hopper according to a configurable tick rate. The default value reproduces vanilla's own eight tick cooldown.
* AI pathfinding optimization that pauses AI and pathfinding processing for mobs outside a configurable distance from every player.
* Dynamic view distance adjustment on 1.14 and newer, controlled by both the optimize view distance and auto view distance settings.
* Automatic garbage collection when memory usage is high.
* Particle reduction options are reserved for a future release. There is currently no general purpose Bukkit or Paper API that allows a plugin to intercept arbitrary vanilla particle effects, so the reduce particles and particle reduction settings exist in the configuration file but have no effect yet.

## Requirements

* Minecraft 1.13 or newer. Versions 1.8.9 through 1.12 are untested but may work.
* A Spigot or Paper server, or a compatible fork. Paper is recommended.
* Java 25 or newer. The plugin is compiled with the `--release 25` flag (see `pom.xml`), so a runtime older than Java 25 cannot load the built jar.

The `plugin.yml` file declares `api-version: 26.2`, matching the Spigot API version this build targets. Paper specific features require actually running on Paper itself, rather than a Paper API compatible fork, since the underlying APIs need to exist on the server. Those features also work best on Paper 1.14 or newer.

## Installation

1. Download the latest `UltraOptimize.jar` from the releases page.
2. Place it in the server's plugins folder.
3. Start or restart the server.
4. Configure the plugin in `plugins/UltraOptimize/config.yml`.
5. Reload the configuration with `/uo reload`.

## Configuration

The following example shows a typical basic configuration.

```yaml
auto-optimize:
  enabled: true
  interval: 300        # Seconds between optimizations
  tps-threshold: 18    # Run optimization when TPS falls below this

entities:
  max-per-chunk: 50
  max-items-per-chunk: 100
  auto-merge-items: true
  item-merge-radius: 1.5
  exempt-types:        # Entities that won't be removed
    - VILLAGER
    - ARMOR_STAND
    - ITEM_FRAME

chunk-preloading:
  enabled: true
  preload-radius: 5    # Chunks around spawn
  chunks-per-tick: 3   # Loading speed
  spiral-pattern: true # vs square pattern
  generate-chunks: false

chunks:
  unload-empty: true
  aggressive-unload: true
  unload-interval: 300
  unload-radius: 8     # Don't unload chunks this close to players

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

## Commands

### Basic Commands

* `/uo` or `/ultraoptimize`: shows the help menu.
* `/uo reload`: reloads the configuration.
* `/uo stats`: shows detailed server statistics, including the count of mobs with paused AI when `advanced.optimize-ai` is enabled.
* `/uo info`: shows the plugin's current configuration.
* `/uo report`: generates a performance analysis report.

### Optimization Commands

* `/uo optimize`: runs a manual optimization pass.
* `/uo auto`: toggles automatic optimization on or off.
* `/uo gc`: forces garbage collection.
* `/uo merge`: merges nearby items and experience orbs.

### Entity Commands

* `/uo clear items`: removes all dropped items.
* `/uo clear mobs`: removes all hostile mobs.
* `/uo clear xp`: removes all experience orbs.
* `/uo clear arrows`: removes all arrows.
* `/uo clear all`: removes all entities except players.

### Chunk Commands

* `/uo chunks unload`: unloads empty chunks.
* `/uo chunks info`: shows chunk statistics.
* `/uo preload info`: shows the current preloading status.
* `/uo preload restart`: restarts spawn chunk preloading.

### View Distance (1.14+)

* `/uo view <world> <distance>`: sets the view distance for a world.

### Paper Only Commands

* `/uo paper stats`: shows Paper optimization statistics.
* `/uo paper optimize`: runs a Paper specific optimization pass.
* `/uo paper watchdog status`: shows the watchdog monitor's status.
* `/uo paper watchdog reset`: resets the hang counter.
* `/uo paper regions stats`: shows region file statistics.
* `/uo paper regions optimize [world]`: optimizes region files.
* `/uo paper regions clean [world]`: removes empty region files.

## Permissions

* `ultraoptimize.*`: grants all permissions.
* `ultraoptimize.reload`: allows reloading the configuration.
* `ultraoptimize.clear`: allows clearing entities.
* `ultraoptimize.optimize`: allows running optimizations.
* `ultraoptimize.stats`: allows viewing statistics.
* `ultraoptimize.chunks`: allows managing chunks.
* `ultraoptimize.preload`: allows using chunk preloading commands.
* `ultraoptimize.gc`: allows forcing garbage collection.
* `ultraoptimize.auto`: allows toggling automatic optimization.
* `ultraoptimize.merge`: allows merging entities.
* `ultraoptimize.view`: allows managing view distance.
* `ultraoptimize.report`: allows generating reports.
* `ultraoptimize.info`: allows viewing plugin information.
* `ultraoptimize.paper`: allows access to Paper specific commands.
* `ultraoptimize.notify`: allows receiving optimization notifications.

## Statistics Tracking

UltraOptimize tracks the following statistics.

* Total optimizations performed (lifetime)
* Entities removed (lifetime)
* Items merged (lifetime)
* Chunks unloaded (lifetime)
* Chunks preloaded (lifetime)
* Session uptime
* Average TPS for the current session

Lifetime totals are persisted to `plugins/UltraOptimize/lifetime-stats.yml`. They are loaded back in when the server starts and are saved automatically every five minutes, so they survive restarts and crashes rather than only clean shutdowns. A readable summary of each session, including both session and lifetime uptime, is also appended to `plugins/UltraOptimize/statistics.log` when the plugin shuts down.

## Performance Tips

### Small Servers (1 to 10 players)

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

### Medium Servers (10 to 50 players)

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

### Large Servers (50+ players)

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

### Paper Optimization

On Paper servers, enabling the following tends to give the best results.

```yaml
auto-optimize:
  interval: 240

chunk-preloading:
  enabled: true
  async-chunks: true

advanced:
  optimize-ai: true
  optimize-entity-ticking: true
```

## Troubleshooting

### Chunks Not Preloading

* Check `/uo preload info` for the current status.
* Confirm that `chunk-preloading.enabled` is set to true.
* Check the console for errors.
* Try `/uo preload restart`.

### High Memory Usage

* Lower the preload radius.
* Enable `chunks.aggressive-unload`.
* Reduce the maximum number of loaded chunks.
* Run `/uo gc` manually.

### Low TPS Despite Optimization

* Check `/uo report` for likely bottlenecks.
* Review entity counts with `/uo stats`.
* Consider lowering the view distance.
* Check for problematic chunks with `/uo chunks info`.

### Paper Features Not Working

* Confirm the server is actually running Paper with `/version`.
* Check the console for a message confirming that a Paper server was detected.
* Some features require a specific Paper version.

### Before Enabling auto-defragment

* This setting rewrites region (`.mca`) files on disk by compacting chunk sectors.
* Take a world backup first, as with any tool that rewrites world files.
* Only whole 4096 byte sectors are moved; chunk data itself is never parsed or modified.

## Monitoring

### Key Metrics to Watch

Use `/uo stats` to monitor the following.

* Current TPS, which should generally stay above 19.
* Memory usage, which should ideally stay below 80 percent.
* Total entity count, watching for unusually fast growth.
* Loaded chunks, compared against the configured maximum.
* Entity counts by type, watching for sudden spikes.

### Emergency Situations

If TPS drops to a critically low level, the following sequence is recommended.

1. Run `/uo optimize` immediately.
2. Check `/uo report` to identify the likely cause.
3. Consider running `/uo clear mobs` if the entity count is high.
4. Run `/uo gc` if memory usage is high.
5. Restart the server if problems persist.
