# Craftwire next: bots at scale, full GUI input, Java and Bedrock protocol bots, remote use

Status: approved by the user on 2026-11-03 (choices recorded in the decision artifact
https://claude.ai/artifact/FZpXFp5yiQTvgVrqGp1FtV, database document `decisions/main`). Branch: `next`.
Project rules: `CLAUDE.md`. Everything in this document is in scope now; there is no phased priority.

## 1. Why

- Users cannot run many bots: CPU and RAM run out.
- Bots can only left, right and shift click; number keys, drag, double click, Q and F are impossible, so many plugin
  menus cannot be tested.
- No Bedrock support at all (a teammate built a Bedrock bot elsewhere; SoulFire supports Bedrock).
- People want to run everything headless on a remote machine or container and drive it over MCP.
- Resource-pack servers (ItemsAdder, Oraxen, Nexo) break with our bots.
- SoulFire (github.com/soulfiremc-com/SoulFire, AGPL-3.0, formerly ServerWrecker) now ships an MCP server and Bedrock
  support: it is a direct competitor.

## 2. What we measured (2026-11-03)

Paper 26.2, view-distance 10, 4 GB heap, 32-thread machine, craftwire-paper 0.10.1.

| Scenario | MSPT avg / p95 | Heap after GC | Loaded chunks |
|---|---|---|---|
| No bots | 0.4 | 252 MB | 0 |
| 50 plugin bots at spawn | 3.8 / 4.9 | 450 MB | 625 |
| 50 plugin bots spread out | 32.6 / 90.5 | 1110 MB | 5349 |
| Same, per-bot view 3, simulation 2, send 2, affectsSpawning false | 12.8 / 18.5 and falling | 1146 MB | 2925 and falling |
| 200 plugin bots (50 spread tuned + 150 at spawn) | 14.5 / 17.9, TPS 20 | 1281 MB | 3027 |
| 1 hidden Fabric client | n/a | 1.65 GB RSS, 3.2 GB committed, 0.4 cores idle | n/a |

Conclusions: a plugin bot costs about 1 MB; the cost is the world each spread-out bot makes the server simulate
(chunks, ticking, mob spawns) and, separately, every real client. Pathfinding and walking were not measurable at
6 concurrent walkers. `bot_spawn` caps at 20 per call and returns a UUID per bot, which is slow and token-heavy.

## 3. Product decisions

| Topic | Decision |
|---|---|
| Bot layers | Three kinds: `plugin` (in-server fake player), `java` (external, vanilla codecs), `bedrock` (external, own protocol). |
| Tool surface | Hybrid: one common `bot_*` tool set with `kind`, plus a few kind-specific tools (e.g. `bot_form`) listed only when that kind is connected. Unsupported actions fail with `UNSUPPORTED_FOR_KIND` and a hint. |
| Targets | External bots may connect to any address. No allowlist (user decision: open source, not our job to police use). |
| Positioning | QA for plugins and servers plus controlled load testing with profiling (what SoulFire lacks: results tied to profile, scenarios and reports). |
| Java protocol | Vanilla packet codecs from the Minecraft server jar of the target version, loaded by the bot runner. |
| Bedrock protocol | Written by us, in Java, inside `bedrock-protocol/`: RakNet, encryption, login, Xbox auth chain, packet codecs. Latest Bedrock protocol version only. |
| Minecraft Java versions | 26.2, 26.3 and 26.4 (26.4 added on this branch). |
| No rewrite | Hub, Fabric mod, build engine, capture, scenarios and profiler stay; new modules are written fresh; the plugin bot is rewritten on `bot-core`. No Rust. |

## 4. Architecture

```
AI client ──MCP──> hub (TypeScript)
                    ├─ WebSocket JSON-RPC ─> agent-paper   (plugin bots, server tools)
                    ├─ WebSocket JSON-RPC ─> agent-fabric  (client tools)
                    └─ WebSocket JSON-RPC ─> botrunner     (java + bedrock bots), one or more, local or remote
                                               ├─ java/     vanilla codecs, per-version classloader
                                               ├─ bedrock/  bedrock-protocol
                                               └─ shared world cache, behaviors, pathfinder (bot-core)
```

- The bot runner is a new agent kind: `hello` with `agentKind: "botrunner"`. It reuses `agent-core` (connection, RPC,
  `AgentError`). The hub starts a local runner on demand (`botrunner_process`, like `client_process`) or accepts
  runners that connect from other machines with the agent token.
- `bot-core` is plain Java (no Minecraft classes). It defines the bot model and everything shared by the three
  kinds; each kind implements a small set of interfaces (`BotBody`, `WorldView`, `InventoryView`, `ClickSink`, ...).
- Several runners may connect; the hub spreads a spawn across them (`runner` argument or round robin) and merges
  results.

## 5. bot-core

- Bot model: name, kind, state (position, health, food, game mode, held slot, effects), inbox (chat, system, action
  bar), HUD copy (sidebar, tab header/footer and entries, boss bars, title, action bar), open screen, cursor item.
- GUI click vocabulary (shared by all kinds and the Fabric client): `left`, `right`, `shift`, `number` (key 1-9),
  `offhand` (F), `drop` (Q), `drop_stack` (Ctrl+Q), `middle`, `double`, `drag` (`button`: left/right/middle, `slots`),
  `outside` (click outside the window). `gui_read` returns `carried` (the cursor item).
- High-level helpers built on the vocabulary: `move_item {from, to, count}`, `equip {slot|item, to}`,
  `transfer {from: container|inventory, item?, count?}`.
- Special screens: anvil rename, sign edit (front/back), book read/write/sign, merchant trade select, button screens
  (enchanting table, stonecutter, loom, lectern, cartography), beacon effects, recipe-book place, creative inventory,
  Paper Dialog API screens (buttons and inputs), clickable chat components (run_command, suggest_command, open_url
  reported not followed), hover text of chat and items.
- Pathfinder: moved from `agent-paper/.../bot/path` onto `WorldView`; search budgeted per tick (node and time
  budget), spread over ticks, with a per-target path cache.
- Behaviors (server-side, no MCP call per step): `wander`, `follow`, `patrol`, `chat` (rate), `command_loop`,
  `menu_click`, `idle`. Driven by `bot_behavior {bots|group, type, params}`.
- Script behaviors: JavaScript on the existing script engine with a `bots` API (`bots.each`, tick and message events).
- Groups and name patterns: `group` on spawn; `bots: "Load*"` or `group: "load"` wherever a bot is named.
- Client settings a bot can present: locale, client brand, simulated ping (tab latency and delayed handling), skin
  (texture URL), main hand, chat visibility, skin parts.

## 6. Plugin bot (agent-paper) rewrite

- Profiles: `light` by default (view 3, simulation 2, send 2, `affectsSpawning(false)`, no advancements or stats),
  `realistic` (server defaults, advancements and stats on). Per spawn: `profile`.
- Connection subclass whose `send` drops packets before any pipeline work, keeping only chat, system, action bar,
  teleport, resource-pack, screen and HUD packets; HUD and tab tracking only while someone reads them (`hud_read`
  or an active wait/scenario subscribed to HUD).
- Advancements and stats replaced by no-op implementations in `light`.
- Idle bots do sparse work (chunk position updates every 10 ticks when not moving).
- Optional `hideBots`: bots do not see or list each other (pure load tests).
- Paper: one global bot tick loop instead of one scheduler task per bot; Folia keeps per-entity schedulers.
- Spawn queue: up to 1000 per call, joined at a configurable rate per tick, `spread {radius}` places bots on random
  safe surface points; compact results (`{spawned: 150, names: "Load1..Load150"}`, details on request);
  `bot_remove` returns a count unless asked for names.
- Resource packs: answers ACCEPTED, DOWNLOADED, SUCCESSFULLY_LOADED by default; `resourcePack` on spawn sets
  `accept|decline|fail_download|...`. With `verify` (default on) the runner downloads each offered pack once (cached
  by hash), checks SHA-1 and size and reports 404, timeout or hash mismatch. `bot_action resource_packs` lists offers
  (url, hash, required, prompt) and the answers given.

## 7. External Java bot (botrunner/java)

- Codecs: the runner downloads the vanilla server jar of the target version (checksum from `versions/<mc>.properties`)
  and loads it in its own classloader; registries bootstrap once per version and are shared by all bots of that
  version. Protocol version is detected from a status ping.
- Networking: one shared Netty event loop group (NIO; epoll/io_uring when available), virtual threads for bot logic.
- World modes: `shared` (chunk and entity cache shared by all bots on the same server and dimension) and `none`
  (chunks are not decoded; connect, chat, commands, menus only).
- Movement: our own client physics (gravity, collisions, step, jump, sprint, swim, ladders) against the shared world;
  the `bot-core` pathfinder.
- Features: Velocity/BungeeCord join and server switch, login plugins (register/login prompts), anti-bot and captcha
  flows, packet plugins see real packets, resource packs (real download and hash), network simulation (latency,
  jitter, loss per bot), join storms (N joins per second), menus with the full vocabulary.
- Auth: offline; Microsoft account by device code (the user's own account, token cached in `~/.craftwire/accounts`).

## 8. Bedrock (bedrock-protocol + botrunner/bedrock)

Own implementation, latest protocol version only.

- `raknet/`: offline handshake (unconnected ping/pong, open connection request/reply 1 and 2), MTU discovery,
  connection request and accepted, reliable/ordered/sequenced frames, split packets, ACK/NAK with resend, keep-alive.
- `codec/`: varints, little-endian types, network NBT, item and block descriptors, the batch wrapper, compression
  negotiation (`RequestNetworkSettings` / `NetworkSettings`, zlib and snappy).
- `crypto/`: ECDH key agreement and the session encryption with packet checksums (`ServerToClientHandshake`,
  `ClientToServerHandshake`).
- `auth/`: offline self-signed login chain (Geyser/Floodgate offline, BDS with online mode off) and the Xbox Live
  chain via Microsoft device code (Microsoft -> Xbox user token -> XSTS -> Minecraft chain), cached tokens.
- `packet/`: only the packets the bot uses (about 30-40); unknown packets are skipped by length inside the batch.
  StartGame, item registry, play status, resource-pack stack/info/response, text, command request/output,
  player list, scoreboard and display objective, boss event, set title, modal form request/response, server settings
  form, PlayerAuthInput, move player and corrections, level chunk / sub-chunk request and response, update block,
  inventory content and slot, ItemStackRequest/Response, container open/close, interact, inventory transaction (use
  item, attack), animate, NPC dialogue, respawn, disconnect.
- Bot features (parity with Java): join and spawn, chat and commands, HUD, forms (simple, modal, custom; read and
  answer), server-authoritative movement with PlayerAuthInput and correction handling, block break and place, entity
  interact and attack, inventory and containers through ItemStackRequest mapped from the shared click vocabulary,
  resource packs, NPC dialogues.
- JSON UI: phase one at protocol level (form data plus the server pack's `ui/*.json` parsed to map custom form
  layouts to buttons); phase two later: driving a licensed real Bedrock client (screenshots and input). Craftwire never
  downloads pirated clients or bypasses DRM.
- Targets: Geyser + Floodgate on Paper, and BDS.

## 9. Fabric client

- Same click vocabulary in `gui_action` (number, offhand, drop, drop_stack, middle, double, multi-slot drag with
  button, outside, carried item in `gui_read`), and the special screens of section 5 where the client lacks them.
- Lite headless mode: no frame rendered unless a screenshot, record or camera call needs one; sound off; no-op
  handlers for advancement and stats packets; low render distance. Target under 1 GB RSS.
- Resource packs: `client_process {resourcePacks: accept|decline|prompt}` and `wait_for {condition:"resource_pack"}`.
- Microsoft account sign-in by device code for online-mode servers.
- Several hidden clients at once (2-4, separate processes, distinct names and game dirs).
- Mod profiles: named sets of mod jars (e.g. Sodium, Iris) for `client_process`.

## 10. Load testing and reports

- `load_test {ramp: [10, 50, 100, 200], stepSeconds, behavior, profile, kind}` spawns in steps, runs the behavior,
  samples TPS, MSPT, heap, chunks, entities and a `profile` per step, and returns JSON plus a shareable HTML report
  (curve, per-plugin time, slowest ticks).
- `profile_diff`: compares two profile results (before/after a change).

## 11. Remote, containers and teams

- `docker/compose.yml` with separate services: hub, paper, client (software rendering for screenshots), botrunner.
- Web panel served by the hub (`hub/src/panel`): instances and health, bots on a `world_render` map, logs, latest
  screenshots, live client view (MJPEG), session replay (each tool call with its arguments and result, step by step).
- Per-person tokens with tool-group permissions (read-only, bots, dev) on `craftwire serve`; audit log of every call
  (who, when, what).
- Distributed load: one `load_test` split across several runners, merged into one report.
- CI: an official GitHub Action (scenarios and short load tests in Docker) and a PR comment with the report.

## 12. Extras

- Record and replay: turn a session's steps (agent or human) into a `.cwtest.json` scenario.
- Velocity agent (`craftwire-velocity`): proxy commands, logs, server list, cross-server bot tests.
- Dupe and exploit hunting: bots try odd click and packet sequences on the user's own server and report inventory
  inconsistencies.
- Localization tour: bots open the plugin's menus in each locale and list untranslated strings.

## 13. Minecraft 26.4

Add `versions/26.4.properties`, `mc_versions=26.2,26.3,26.4`, compat projects for Fabric and Paper where APIs differ,
and the bot runner's vanilla jar entry. Verify the exact Paper build, Fabric API and loader versions when writing.

## 14. Order of writing

All of it now; the writing order only keeps dependencies satisfied:
1. `bot-core` (model, vocabulary, pathfinder move, behaviors, groups).
2. Plugin bot rewrite in `agent-paper` (profiles, connection, spawn queue, resource packs, screens).
3. Hub bot tools (hybrid surface, compact results, groups, behaviors, load_test, profile_diff).
4. Fabric client: click vocabulary, screens, lite mode, resource packs, Microsoft sign-in, mod profiles.
5. `botrunner` skeleton (agent kind, process management in the hub, shared world cache).
6. Java protocol bot.
7. `bedrock-protocol` and the Bedrock bot.
8. 26.4 support.
9. Docker, panel, tokens and audit, CI action.
10. Extras (record and replay, Velocity agent, dupe hunting, localization tour).
11. Docs: `protocol/PROTOCOL.md`, `docs/tools.md`, README, skills.

Then: compile everything, fix the build; then tests (approach to be given by the user); then full trials.

## 15. Things to verify while writing

- 26.4 Paper dev bundle, Fabric API and loader versions.
- Latest Bedrock protocol number and the exact current login and Xbox auth flow.
- Paper per-player chunk APIs on 26.x (`setViewDistance`, `setSimulationDistance`, `setSendViewDistance`,
  `setAffectsSpawning`) and the Dialog API surface.
- Vanilla `ContainerInput` names on 26.x for SWAP, THROW, CLONE, QUICK_CRAFT, PICKUP_ALL.
- Vanilla server jar layout for the bot runner: the bundler format (`META-INF/versions`, `META-INF/libraries`) and
  whether 26.x jars ship unobfuscated; if not, load Mojang mappings and remap once per version into a cache.
