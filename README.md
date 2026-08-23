# UltraOptimize

UltraOptimize is an optimization plugin for Minecraft servers running Spigot or Paper. It manages chunks, entities, and general server performance to help keep gameplay smooth under load.

Most features ship **disabled**. The ones that are off by default are off because they cost more than they save on a small server, or because they change gameplay in ways you should opt into deliberately rather than discover later. Each one documents why in `config.yml`. Turn things on one at a time and measure the result with a profiler such as Spark rather than trusting any plugin's own claims, including this one's.

## Features

### Core Optimization

* Automatic optimization that removes excess entities when TPS drops below a configured threshold.
* Smart entity management that merges nearby items and experience orbs to reduce lag.
* Chunk unloading, including optional aggressive cleanup of empty chunks.
* Performance monitoring covering TPS tracking and memory usage, sampled once per second.
* Optional spawn chunk preloading in either a spiral or square pattern.

### Paper Specific Features

When running on a Paper server, UltraOptimize enables the following additional systems:

* An advanced chunk system using plugin chunk tickets and urgent chunk loading.
* A watchdog monitor that detects main thread stalls and can trigger an emergency cleanup pass.
* Region file reporting, and removal of empty region files on demand.

### Entity Optimization

* Clears items, hostile mobs, experience orbs, and arrows on demand.
* Automatically merges nearby items within a configurable radius.
* Allows specific entity types to be exempted from removal.
* Enforces per chunk entity limits, including blocking new spawns once a chunk is full.

### Chunk Features

* Optionally unloads chunks that are empty and far from players.
* Configurable unload radius around players.
* Async chunk loading support on 1.13 and newer.
* Chunk preloading with a configurable pattern.
* An optional maximum on the number of loaded chunks.

### Performance Features

* AI pathfinding optimization that pauses AI and pathfinding for mobs outside a configurable distance from every player. Only mobs the plugin itself paused are ever resumed, so mobs deliberately set to NoAI by map makers, spawn eggs, or other plugins are left alone.
* Dynamic view distance adjustment, controlled by both the optimize view distance and auto view distance settings. This requires Paper or a Paper based fork such as Purpur; the underlying API to change view distance at runtime does not exist on plain Spigot at any Minecraft version.
* Rate limited garbage collection when memory usage is critical, with a five minute minimum between runs.
* Redstone update suppression for any block firing more than a hundred times per second, using a per location rolling window. **Off by default.** This does not make redstone cheaper: the block still ticks, and the suppressed update simply becomes a no-op. What it does is stop a runaway circuit from cascading, at the cost of silently stalling clocks and jamming piston doors once a build crosses the threshold.
* Hopper transfer throttling according to a configurable tick rate. **Off by default, and worth understanding before you enable it.** Vanilla only sets a hopper's eight tick cooldown when a transfer *succeeds*, so cancelling the transfer means the hopper retries on the very next tick instead of in eight. A throttled hopper therefore does more work, and fires more events, than an unthrottled one. There is no way to avoid this from the Bukkit API. It is only worth enabling at rates well above eight, where the reduction in actual item movement outweighs the extra retries, and it will break item sorters.
* Particle reduction options are reserved for a future release. There is currently no general purpose Bukkit or Paper API that allows a plugin to intercept arbitrary vanilla particle effects, so the reduce particles and particle reduction settings exist in the configuration file but have no effect yet.
* `performance.max-hoppers-per-chunk` currently has no effect. It previously fed a console warning on chunk load that cost far more than the warning was worth, and the setting is retained only so existing configuration files keep loading cleanly.
* The following settings are also reserved for a future release and currently have no effect: `entities.clear-radius`, `chunks.force-upgrade`, `paper.cpu-profile`, and `paper.chunk-system.use-urgent-loading`. They are validated and loaded, but nothing in the plugin reads them yet. `notifications.show-statistics` is the same - notification content is currently fixed regardless of this setting.
* `chunks.max-loaded` does not enforce a cap. It only logs a console warning when total loaded chunks exceeds it during a chunk unload pass (see `chunks.aggressive-unload`, `/uo chunks unload`, and the periodic chunk monitor); chunks are never refused or force-unloaded because of it.

### A note on periodic world saving

`paper.region-files.incremental-saving` is **off by default and should stay that way on constrained hardware.** There is no Bukkit or Paper API for an incremental or asynchronous world save. When enabled, this option performs a full, synchronous save of every loaded chunk in every world, on the main thread, on the configured interval. The minimum interval is five minutes for that reason. On a host with a slow or shared disk, expect a visible pause each time it fires.

## Requirements

* Minecraft 1.13 or newer. Versions 1.8.9 through 1.12 are untested but may work.
* A Spigot or Paper server, or a compatible fork. Paper is recommended.
* Java 25 or newer. The plugin is compiled with the `--release 25` flag (see `pom.xml`), so a runtime older than Java 25 cannot load the built jar. Many budget hosts still default to Java 17 or 21, so check what your host actually runs before deploying.

The `plugin.yml` file declares `api-version: 26.2`, matching the Spigot API version this build targets. Paper specific features require actually running on Paper itself, rather than a Paper API compatible fork, since the underlying APIs need to exist on the server. Those features also work best on Paper 1.14 or newer.

## Installation

1. Download the latest `UltraOptimize.jar` from the releases page.
2. Place it in the server's plugins folder.
3. Start or restart the server.
4. Configure the plugin in `plugins/UltraOptimize/config.yml`.
5. Reload the configuration with `/uo reload`.

## Upgrading from an earlier version

Several defaults changed to protect low memory servers. Bukkit only adds keys that are missing from an existing `config.yml`; it never rewrites values you already have. **An existing installation will keep the old, more aggressive settings until you edit the file yourself.**

The settings that changed:

```yaml
chunks:
  aggressive-unload: false          # was true
chunk-preloading:
  enabled: false                    # was true
performance:
  optimize-redstone: false          # was true
  optimize-hoppers: false           # was true
paper:
  chunk-system:
    ticket-spawn-chunks: false      # was true
  watchdog:
    auto-emergency-optimization: false   # was true
  region-files:
    incremental-saving: false       # was true
    save-interval: 900              # was 30
```

Two keys are no longer read and can be deleted, though leaving them in place is harmless: `paper.region-files.cache-cleanup-interval` and `paper.region-files.cache-timeout`.

Behaviour that changed in ways you may notice:

* `/uo clear all` no longer removes every non-player entity. It now clears dropped items, experience orbs, projectiles, and hostile mobs only. Chest and hopper minecarts, tamed animals, saddled horses, and passive mobs survive it.
* `/uo gc` is rate limited to once every five minutes. Use `/uo gc force` to override.
* Preloading, spawn chunk tickets, and aggressive unloading undo one another. Do not enable all three together.

## Configuration

The following example shows a typical basic configuration, with the shipped defaults.

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
  enabled: false       # Loaded chunks are the largest consumer of heap
  preload-radius: 5    # Chunks around spawn
  chunks-per-tick: 3   # Loading speed
  spiral-pattern: true # vs square pattern
  generate-chunks: false

chunks:
  unload-empty: true
  aggressive-unload: false
  unload-interval: 300
  unload-radius: 8     # Don't unload chunks this close to players

performance:
  optimize-redstone: false
  optimize-hoppers: false
  max-hoppers-per-chunk: 10   # Currently has no effect
  hopper-tick-rate: 8

advanced:
  async-chunks: true
  optimize-ai: true
  optimize-view-distance: false
  auto-view-distance: false
  min-view-distance: 3
  max-view-distance: 10
```

Any entity name in `exempt-types` that is not a valid `EntityType` on your Minecraft version is logged as a warning at startup and then ignored. This matters more than it sounds: `BOAT` stopped existing as a single entity type in 1.21.2, when it was split into `OAK_BOAT`, `BIRCH_BOAT`, and so on. Check your startup log if you are relying on an exemption.

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
* `/uo gc`: forces garbage collection. Rate limited to once every five minutes, since a full collection pauses the entire server. Use `/uo gc force` to bypass the limit.
* `/uo merge`: runs the merge and cleanup pass. As well as merging items and experience orbs, this removes stuck arrows and any items or mobs over the configured per chunk limits.

### Entity Commands

* `/uo clear items`: removes all dropped items.
* `/uo clear mobs`: removes all hostile mobs.
* `/uo clear xp`: removes all experience orbs.
* `/uo clear arrows`: removes all arrows.
* `/uo clear all`: removes dropped items, experience orbs, projectiles, and hostile mobs. Vehicles, tamed animals, and passive mobs are left alone.

All clear commands respect `entities.exempt-types`.

### Chunk Commands

* `/uo chunks unload`: unloads empty chunks.
* `/uo chunks info`: shows chunk statistics.
* `/uo preload info`: shows the current preloading status.
* `/uo preload restart`: restarts spawn chunk preloading.

### View Distance (Paper only)

* `/uo view <world> <distance>`: sets the view distance for a world. Requires Paper or a Paper based fork; see Troubleshooting below.

### Paper Only Commands

* `/uo paper stats`: shows Paper optimization statistics.
* `/uo paper optimize`: runs a Paper specific optimization pass.
* `/uo paper watchdog status`: shows the watchdog monitor's status.
* `/uo paper watchdog reset`: resets the hang counter.
* `/uo paper watchdog emergency`: reports whether emergency mode is currently active.
* `/uo paper regions stats`: shows region file statistics.
* `/uo paper regions clean [world]`: removes empty region files. This deletes world data; back up first.

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

## The watchdog monitor

The watchdog detects stalls of the **main thread**, which is not the same thing as a slow server. It works by having a task write a timestamp on the main thread every tick, and a separate background thread check how long ago that timestamp was written. A healthy server sits at roughly fifty milliseconds. Even a server struggling along at five TPS only reaches about two hundred. The default ten second threshold is therefore well clear of ordinary slowness and only fires when the main thread has genuinely stopped.

When a stall crosses the critical threshold, and if `paper.watchdog.auto-emergency-optimization` is enabled, the plugin runs one cleanup pass: an entity sweep across every world, followed by an unload of empty chunks. It is rate limited to once every five minutes, and it deliberately does not force a garbage collection, since a full stop-the-world pause on an already struggling server makes matters worse. Emergency mode stands down automatically once the server has been healthy for about thirty seconds.

`auto-emergency-optimization` is off by default. Handing a full world scan to a server that is already failing to keep up is not obviously the right response, and you should decide for yourself whether it suits your situation.

## Statistics Tracking

UltraOptimize tracks the following statistics.

* Total optimizations performed (lifetime)
* Entities removed (lifetime)
* Items merged (lifetime)
* Chunks unloaded (lifetime)
* Chunks preloaded (lifetime)
* Session uptime
* Average TPS for the current session

Lifetime totals are persisted to `plugins/UltraOptimize/lifetime-stats.yml`. They are loaded back in when the server starts and are saved asynchronously every five minutes, so they survive restarts and crashes rather than only clean shutdowns. A readable summary of each session, including both session and lifetime uptime, is also appended to `plugins/UltraOptimize/statistics.log` when the plugin shuts down.

TPS figures shown in `/uo report` cover a rolling sixty second window. Where the server exposes its own TPS reporting, as Spigot and Paper both do, that is used in preference to the plugin's own measurements.

## Performance Tips

### Low Memory Servers (1 to 2 GB heap)

The shipped defaults already target this case. The most useful thing you can do is resist turning features on.

```yaml
auto-optimize:
  interval: 600
  tps-threshold: 17

chunk-preloading:
  enabled: false

chunks:
  aggressive-unload: false
  unload-radius: 6

paper:
  chunk-system:
    ticket-spawn-chunks: false
  region-files:
    incremental-saving: false
```

The largest wins on a small heap are not in this file at all. Lower `view-distance` and `simulation-distance` in `server.properties`, and keep entity counts down. No plugin setting will make up for a view distance of ten on a 1 GB heap.

### Small Servers (1 to 10 players)

```yaml
auto-optimize:
  interval: 600
  tps-threshold: 17

chunks:
  unload-radius: 6
```

### Medium Servers (10 to 50 players)

```yaml
auto-optimize:
  interval: 300
  tps-threshold: 18

chunks:
  unload-radius: 8
```

### Large Servers (50+ players)

```yaml
auto-optimize:
  interval: 180
  tps-threshold: 19

chunks:
  unload-radius: 10
  max-loaded: 5000
```

### Chunk preloading

Preloading is worth considering only when you have memory to spare and a specific reason to want spawn chunks resident, such as a busy hub world. It loads `(2 * radius + 1)^2` chunks per world at startup whether or not anyone is near spawn, and those chunks stay in the heap.

```yaml
chunk-preloading:
  enabled: true
  preload-radius: 3
  chunks-per-tick: 3

advanced:
  async-chunks: true
```

Do not combine this with `chunks.aggressive-unload`, which will unload most of what you just preloaded on its next pass, leaving you with the disk reads and the save-on-unload cost and none of the benefit.

### Paper Optimization

On Paper servers, the AI and entity ticking options are the safest additions.

```yaml
auto-optimize:
  interval: 240

advanced:
  async-chunks: true
  optimize-ai: true
  optimize-entity-ticking: true
```

Note that `advanced.optimize-ai` pauses AI for distant mobs, which means mob farms and grinders stop producing while their mobs are paused, and paused mobs accumulate rather than wandering into despawn range. If entity counts start climbing after you enable it, raise `advanced.pathfinding-limit` or turn it off.

`paper.chunk-system.ticket-spawn-chunks` adds **permanent** plugin chunk tickets around spawn. Those chunks can never unload while the plugin is enabled, and they tick. That is a fixed memory cost of roughly `(2 * radius + 1)^2` chunks per world, so leave it off unless you have measured that you need it.

## Troubleshooting

### Chunks Not Preloading

* Confirm that `chunk-preloading.enabled` is set to true. It is **false** by default as of this version.
* Check `/uo preload info` for the current status.
* Check the console for errors.
* Try `/uo preload restart`.

### High Memory Usage

* Lower `view-distance` and `simulation-distance` in `server.properties`. This is almost always the largest single factor.
* Disable `chunk-preloading` and `paper.chunk-system.ticket-spawn-chunks` if either is enabled.
* Reduce the maximum number of loaded chunks.
* Reduce `entities.max-per-chunk` and `entities.max-items-per-chunk`.
* `/uo gc` is available, but a forced collection pauses the whole server and rarely helps for long. If memory climbs back immediately, something is holding it and a GC will not fix that.

### Hoppers or Item Sorters Behaving Oddly

* Check whether `performance.optimize-hoppers` is enabled. Throttling cancels transfers, which breaks anything that depends on hopper timing.
* Disable it and test again before investigating anything else.

### Redstone Clocks Stopping or Doors Sticking

* Check whether `performance.optimize-redstone` is enabled. Any block exceeding a hundred updates per second has its updates suppressed, which stalls fast clocks.
* Disable it and test again.

### Mobs Standing Still

* This is `advanced.optimize-ai` working as designed: mobs beyond `advanced.pathfinding-limit` blocks from every player have their AI paused.
* Raise `pathfinding-limit`, or disable `optimize-ai` if you run farms that depend on distant mob movement.

### Low TPS Despite Optimization

* Check `/uo report` for likely bottlenecks.
* Review entity counts with `/uo stats`.
* Consider lowering the view distance.
* Check for problematic chunks with `/uo chunks info`.
* Profile the server with Spark. It will tell you what is actually consuming the tick, which no plugin's own statistics can.

### Paper Features Not Working

* Confirm the server is actually running Paper with `/version`.
* Check the console for a message confirming that a Paper server was detected.
* Some features require a specific Paper version.

### View Distance Control Reports as Not Supported

* Run `/uo info` and check the View Distance Control line for the reason given.
* If it says the server needs to be Paper, plain Spigot has no API for changing view distance at runtime, at any Minecraft version. Switch to Paper or a Paper based fork such as Purpur to use this feature.
* If Paper is already confirmed with `/version`, check the console at startup for the exact reason logged by the plugin.

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

1. Run `/uo report` to identify the likely cause before changing anything.
2. Run `/uo optimize`.
3. Consider running `/uo clear mobs` if the entity count is high.
4. Restart the server if problems persist.

Forcing a garbage collection is deliberately absent from that list. If memory is genuinely exhausted the collector is already running constantly, and adding a manual stop-the-world pause on top makes the server less responsive, not more.
