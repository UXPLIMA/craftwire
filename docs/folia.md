# Folia

Craftwire's server plugin runs on [Folia](https://papermc.io/software/folia) as well as on Paper: the same jar, no
setting to change. Folia ticks every region of the world on its own thread, so plugins that assume one main thread
break there, usually only once players are spread over the map. Craftwire's bots and scenarios can put players in
far-apart regions, and `exceptions` tells you which line of your plugin touched something from the wrong thread.

Folia is supported for Minecraft 26.2 (Folia's builds for it are still marked beta). Newer versions follow when
Folia ships them.

## Set up

1. Download the Folia jar for your Minecraft version from papermc.io and put it in a server folder.
2. Put `craftwire-paper-<version>.jar` in `plugins/`. Your plugin needs `folia-supported: true` in its `plugin.yml`,
   or Folia does not load it.
3. Start the server yourself, or with `server_process {action:"start", serverDir}` (it finds `folia-*.jar` like
   `paper-*.jar`).

## What differs from Paper

Every tool works on both. Where Folia changes what a tool does:

| Tool | On Folia |
|---|---|
| `server_command` | Console commands run on the global region; `asPlayer` runs on that player's region thread. Folia has no `/scoreboard` and disables a few other vanilla commands. |
| `server_eval` | Runs on the global region, which may only touch server-wide state. Pass `at {world?, x, z}` to run where a block or entity is (its chunk is loaded first), or `asPlayer` to run on a player's thread. Touching a block, entity or player elsewhere fails with `WRONG_THREAD` and a hint. |
| `bot_spawn`, `bot_action` | Unchanged. Each bot ticks and acts on its own region thread and keeps doing so when it walks into another region. |
| `world_query`, `world_edit`, `world_render` | Unchanged. Work is split by chunk and runs on the thread that owns each chunk; snapshots are saved and restored chunk by chunk. A schematic (`save_schematic`, `paste_schematic`) is one structure, so its box must lie in one region. |
| `wait_for` | Block conditions are checked on the block's region, player conditions on the player's thread. |
| `server_info` | Adds `folia: true`, `regions` (the TPS `[5s, 15s, 1m, 5m, 15m]` of the region at each world's spawn and at each player) and `slowestRegion`. `tps` and `mspt` are the global region's. |
| `profile` | Samples every region thread. `thread` is `Folia Region Scheduler Thread #*` and `threads` lists the busy samples per thread. Tick times come from every region, so the average is lower than on Paper; look at `max` and `slowest`. |
| `plugin_manage` | `enable` and `disable` are refused with `UNSUPPORTED`: Folia cannot stop a plugin while the server runs. Use `plugin_deploy`, which restarts the server. |
| `exceptions` | A Folia thread violation gets `folia: {owner, touched, fix}`: the first frame of your code, what it touched (`block`, `chunk`, `entity`, `player`, `world`, or `scheduler` for the Bukkit scheduler Folia does not have) and the scheduler to use instead. |

Extension tools (`craftwire-api`) run on the global region on Folia. A tool that touches blocks or entities has to
schedule that work itself.

## Find Folia bugs in your plugin

> *"Start my Folia test server, deploy my plugin, spawn a bot at spawn and one 5000 blocks away, have both run
> /arena join, and tell me which exceptions my plugin logged."*

1. Spawn bots in different regions: regions are far apart (`location {x: 5000, z: 5000}` is safely another one).
2. Run the plugin's features with `bot_action` (commands, menus, items) on both bots, or write a scenario that does.
3. Call `exceptions`. Each `folia` entry names the line (`owner`) and the scheduler to use (`fix`):

```jsonc
{ "type": "java.lang.IllegalStateException",
  "message": "Thread failed main thread check: Cannot modify world asynchronously, …",
  "origin": "com.example.arena.ArenaReset.lambda$run$0(ArenaReset.java:31)",
  "folia": { "owner": "com.example.arena.ArenaReset.lambda$run$0(ArenaReset.java:31)", "touched": "block",
             "fix": "Change the block from its region: Bukkit.getRegionScheduler().run(plugin, location, task -> ...)." } }
```

The usual fixes:

- `Bukkit.getScheduler()` → `Bukkit.getGlobalRegionScheduler()` for server-wide work, `Bukkit.getAsyncScheduler()`
  for work off the game threads.
- Blocks, chunks, and entities that are not players → `Bukkit.getRegionScheduler().run(plugin, location, task -> …)`.
- A player or another entity you hold → `entity.getScheduler().run(plugin, task -> …, null)`.
- `teleport` → `teleportAsync`.
