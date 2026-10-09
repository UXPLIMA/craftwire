# M9 — Folia support

Status: requested by the user on 2026-10-09 ("add Folia support if it makes sense"; purposes: test my plugins on
Folia with bots and scenarios, and drive a Folia server). All decisions are delegated ("every decision is yours").
Nothing is released or published to a registry without the user's npm step.

Why it makes sense: UXPLIMA's plugins advertise Folia support; Folia bugs are wrong-thread accesses that only show
with players in different regions, which bots and scenarios can reproduce and `exceptions` can explain. Folia has
26.2 builds (beta, Fill API project `folia`) and a 26.2 dev bundle; there is no Folia 26.3 yet, so Folia is
supported on 26.2 and follows when Folia ships a newer version.

## Approach

One plugin jar for Paper and Folia (`folia-supported: true`). Every piece of work runs on the thread that owns the
data, through the schedulers Paper and Folia share:

| work | scheduler |
|---|---|
| console commands, plugin management, server info, event listener registration | global region |
| a block, a box or a chunk | region of that chunk (boxes split per chunk, already the case) |
| a player or a bot (actions, `asPlayer` commands, state, HUD, GUI) | that entity's scheduler (follows it across regions) |

On Paper all three are the main thread, so behaviour there does not change. `Sync` gains `entity(Entity, work)`
(a retired entity fails with `ENTITY_GONE`) and `repeat` helpers; `Bukkit.getScheduler()` (unsupported on Folia) is
no longer used anywhere. `Sync.folia()` tells the two apart (`io.papermc.paper.threadedregions.RegionizedServer`).

## Bots

- Each bot ticks from its own entity scheduler (`runAtFixedRate`, period 1), not from one global timer.
- Spawning places the fake `ServerPlayer` on the region thread that owns the spawn chunk; removal runs on the bot's
  scheduler. The exact Folia internals (how `PlayerList.placeNewPlayer` and the region's player lists work on Folia)
  are verified with javap on the Folia server jar and with the bot ITs on a real Folia server.
- All bot actions run on the bot's scheduler; actions that wait across ticks keep doing so on that scheduler.

## Tools on Folia

- `server_command`: console on the global region; `asPlayer` on that player's scheduler.
- `server_eval`: global by default; `at {world,x,z}` (exists) or new `asPlayer` picks the owner. A Folia
  thread-check failure inside the script comes back with a hint to pass `at` / `asPlayer`.
- `world_query` / `world_edit` / `world_render`: per chunk on its region. Players and markers are read per player.
  A snapshot spanning several regions is taken region by region (`regions` count in the result on Folia).
- `wait_for`: block conditions on the block's region, player conditions on the player's scheduler, the rest global.
- `events`: the recorder accepts events from many region threads at once (thread-safe).
- `server_info`: on Folia adds `folia: true` and per-region TPS/MSPT for the regions that hold players and the
  spawn, plus the slowest; `tps` stays the global value.
- `profile`: samples every tick thread (Folia region threads and the global region thread) and adds
  `threads: [{name, samples}]`; on Paper unchanged.
- `plugin_manage enable/disable`: Folia cannot enable or disable plugins at runtime; refused with `UNSUPPORTED` and
  a hint to use `plugin_deploy` (restart).
- Extension tools (`craftwire-api`): still run on the global region; documented. A location/player-bound variant is
  left for later (no user asked for it).

## Folia thread violations in `exceptions`

The hub labels a grouped exception as a Folia thread violation when its message or stack shows Folia's thread
checks (`TickThread`, "off owning thread", "Thread failed main thread check", "Cannot … asynchronously" on a Folia
server). The group gets `folia: {owner: <first plugin frame>, touched: block|entity|world|chunk|player|unknown,
fix: <one sentence>}`, e.g. "use the region scheduler for this block" / "use the entity's scheduler".

## Testing

- The Paper ITs run on Folia with `-Pserver=folia` (versions with `folia_build`), the same test classes; tests that
  must differ ask `ItEnv.folia()`. The harness stops at once when the plugin fails to enable.
- New Folia ITs: bots in two far-apart regions act at the same time; a box across regions is edited and restored;
  the fixture plugin's wrong-thread command produces a labelled thread violation; `plugin_manage` refuses.
- CI: `paper-it` gains a Folia 26.2 job. Hub unit tests for the labelling.
- Version 0.9.0.
