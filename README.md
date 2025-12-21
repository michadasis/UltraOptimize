UltraOptimize
A powerful, comprehensive optimization plugin for Minecraft servers (Spigot/Paper) that automatically manages chunks, entities, and performance to keep your server running smoothly.
🚀 Features
Core Optimization

Auto-Optimization: Automatically removes excess entities when TPS drops
Smart Entity Management: Merge nearby items and XP orbs to reduce lag
Chunk Management: Intelligent chunk loading/unloading with aggressive cleanup
Performance Monitoring: Real-time TPS tracking and memory management
Spawn Chunk Preloading: Preload spawn chunks in spiral or square patterns

Paper-Specific Features
When running on Paper servers, UltraOptimize unlocks advanced features:

Advanced Chunk System: Plugin chunk tickets and urgent chunk loading
Watchdog Monitor: Automatic hang detection with emergency mode
Region File Optimization: Defragment and clean region files
Incremental Saving: Reduce lag from world saves

Entity Optimization

Clear items, mobs, XP orbs, and arrows
Automatic item merging within configurable radius
Exempt specific entity types from removal
Per-chunk entity limits with spawn blocking

Chunk Features

Aggressive chunk unloading for empty chunks
Configurable unload radius around players
Async chunk loading support (1.13+)
Chunk preloading with configurable patterns
Max loaded chunks limit

Performance Features

Redstone optimization (limit excessive updates)
Hopper optimization with tick rate adjustment
Dynamic view distance adjustment (1.14+)
Automatic garbage collection when memory is high
Particle reduction options

📋 Requirements

Minecraft: 1.13+ (1.8.9-1.12 untested but may work)
Server: Spigot, Paper (recommended), or compatible forks
Java: 8 or higher
Note: api-version 1.13 means the plugin is built for 1.13+, but Paper features work best on Paper 1.14+

📦 Installation

Download the latest UltraOptimize.jar from releases
Place it in your server's plugins folder
Start or restart your server
Configure settings in plugins/UltraOptimize/config.yml
Reload with /uo reload

⚙️ Configuration
Basic Settings
yamlauto-optimize:
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
🎮 Commands
Basic Commands

/uo or /ultraoptimize - Show help menu
/uo reload - Reload configuration
/uo stats - Show detailed server statistics
/uo info - Show plugin configuration
/uo report - Generate performance analysis report

Optimization Commands

/uo optimize - Run manual optimization
/uo auto - Toggle auto-optimization on/off
/uo gc - Force garbage collection
/uo merge - Merge nearby items and XP orbs

Entity Commands

/uo clear items - Remove all dropped items
/uo clear mobs - Remove all hostile mobs
/uo clear xp - Remove all XP orbs
/uo clear arrows - Remove all arrows
/uo clear all - Remove all entities (except players)

Chunk Commands

/uo chunks unload - Unload empty chunks
/uo chunks info - Show chunk statistics
/uo preload info - Show preloading status
/uo preload restart - Restart spawn chunk preloading

View Distance (1.14+)

/uo view <world> <distance> - Set view distance for a world

Paper-Only Commands

/uo paper stats - Paper optimization statistics
/uo paper optimize - Run Paper optimization
/uo paper watchdog status - Watchdog monitor status
/uo paper watchdog reset - Reset hang counter
/uo paper regions stats - Region file statistics
/uo paper regions optimize [world] - Optimize region files
/uo paper regions clean [world] - Remove empty region files

🔐 Permissions

ultraoptimize.* - All permissions
ultraoptimize.reload - Reload configuration
ultraoptimize.clear - Clear entities
ultraoptimize.optimize - Run optimizations
ultraoptimize.stats - View statistics
ultraoptimize.chunks - Manage chunks
ultraoptimize.preload - Chunk preloading commands
ultraoptimize.gc - Force garbage collection
ultraoptimize.auto - Toggle auto-optimization
ultraoptimize.merge - Merge entities
ultraoptimize.view - Manage view distance
ultraoptimize.report - Generate reports
ultraoptimize.info - View plugin info
ultraoptimize.paper - Paper-specific commands
ultraoptimize.notify - Receive optimization notifications

📊 Statistics Tracking
UltraOptimize tracks and saves these lifetime statistics:

Total optimizations performed
Entities removed
Items merged
Chunks unloaded
Chunks preloaded
Session uptime
Average TPS

Statistics are saved to plugins/UltraOptimize/statistics.log on shutdown.
🔧 Performance Tips
For Small Servers (1-10 players)
yamlauto-optimize:
  interval: 600
  tps-threshold: 17

chunk-preloading:
  preload-radius: 3
  chunks-per-tick: 5

chunks:
  unload-radius: 6
For Medium Servers (10-50 players)
yamlauto-optimize:
  interval: 300
  tps-threshold: 18

chunk-preloading:
  preload-radius: 5
  chunks-per-tick: 3

chunks:
  unload-radius: 8
For Large Servers (50+ players)
yamlauto-optimize:
  interval: 180
  tps-threshold: 19

chunk-preloading:
  preload-radius: 7
  chunks-per-tick: 2

chunks:
  unload-radius: 10
  max-loaded: 5000
Paper Optimization
On Paper servers, enable these for maximum performance:
yamlauto-optimize:
  interval: 240

chunk-preloading:
  enabled: true
  async-chunks: true

advanced:
  optimize-ai: true
  optimize-entity-ticking: true
🐛 Troubleshooting
Chunks Not Preloading

Check /uo preload info for status
Ensure chunk-preloading.enabled: true
Verify no errors in console
Try /uo preload restart

High Memory Usage

Lower preload-radius
Enable chunks.aggressive-unload
Reduce max-loaded-chunks
Run /uo gc manually

Low TPS Despite Optimization

Check /uo report for bottlenecks
Review entity counts with /uo stats
Consider lowering view distance
Check for problematic chunks with /uo chunks info

Paper Features Not Working

Verify running on Paper: /version
Check console for "Paper server detected"
Some features require specific Paper versions

📈 Monitoring
Key Metrics to Watch
Use /uo stats to monitor:

Current TPS: Should stay above 19
Memory Usage: Keep below 80%
Total Entities: Watch for excessive growth
Loaded Chunks: Monitor vs max-loaded limit
Entity Counts: Look for entity type spikes

Emergency Situations
If TPS drops critically low:

Run /uo optimize immediately
Check /uo report for causes
Consider /uo clear mobs if entity count is high
Run /uo gc if memory usage is high
Restart server if issues persist
