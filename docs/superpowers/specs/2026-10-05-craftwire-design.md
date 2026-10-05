# Craftwire — Design Spec

**Status:** Approved in brainstorming, 2026-10-05
**Owner:** UXPLIMA
**License:** MIT

## 1. Purpose

Craftwire is a free, open-source Model Context Protocol (MCP) integration that lets AI agents such as Claude Code see and drive Minecraft: take screenshots, aim the camera, read and click GUIs, run server commands and scripts, edit the world, manage a dev server, build and redeploy plugins, and simulate players. Target quality bar: the Roblox Studio MCP.

UXPLIMA uses it for its own plugin development and marketing material. The public uses it for the same kinds of work.

### Success criteria

1. **Acceptance scenario:** in a single session, with no human input after setup, Claude produces the full uxmBuilders promo shot set. That covers creating a package from a structure, giving a blueprint, a ghost-preview screenshot, a build-progress series from one fixed camera, and crew/shop menu screenshots with tooltips open.
2. Setup for a new user takes three steps: install the Claude Code plugin, drop the mod into `mods/`, drop the plugin into `plugins/`. No manual port, token or config editing.
3. No component exposes a network port beyond `127.0.0.1`. Nothing in the agents can crash the game or server.

### Non-goals (v1)

- Minecraft versions other than 26.x. Loaders other than Fabric (client) and Paper/Folia (server).
- Remote (non-localhost) hubs.
- Downloading or installing third-party plugins (`install_plugin`).
- Config or file editing tools. Claude Code already has file tools.

## 2. Architecture

```
Claude Code ──stdio(MCP)──► craftwire hub (Node/TypeScript, npm "craftwire")
                              ├─ WebSocket server on 127.0.0.1 ◄── Craftwire Agent (Fabric client mod)
                              │                                ◄── Craftwire (Paper server plugin)
                              ├─ Server process manager (spawn/stop Paper, capture stdout)
                              └─ Build runner (Gradle/Maven)
```

- **Hub:** the only MCP server. It owns the tool registry, routes calls to agents, buffers agent events, and manages the server process and builds. It keeps working when no game is running, so tools like `server_process start` still function.
- **Agents:** thin executors inside the game or server. They **connect out** to the hub and open no ports. They reconnect automatically when the hub restarts.
- **Bots:** server-side fake players created by the Paper agent, comparable to Carpet's `/player`. Mineflayer was rejected because its protocol data lagged at 26.1 when 26.2 was current, and it needs `online-mode=false`.

### Repository layout (monorepo `uxplima/craftwire`)

```
craftwire/
├─ protocol/          JSON Schema message definitions (single source) + codegen to TS and Java
├─ hub/               TypeScript MCP server (npm package "craftwire", bin "craftwire")
├─ agent-core/        Java: WebSocket client, JSON-RPC, handshake, reconnect, op-id cache
├─ agent-fabric/      Java: Fabric client mod "Craftwire Agent" (depends on agent-core)
├─ agent-paper/       Java: Paper plugin "Craftwire" (depends on agent-core, Folia-aware)
├─ test-fixtures/     Test plugin that opens known GUIs/HUD elements for E2E tests
├─ claude-plugin/     Claude Code plugin: .mcp.json + skills
└─ docs/
```

Java side: a Gradle multi-project build on Java 25. `agent-core` uses only `java.net.http.WebSocket` and Gson, both already present in Minecraft and Paper.
Hub: Node ≥ 20. Dependencies are limited to `@modelcontextprotocol/sdk`, `ws` and `zod`.

## 3. Protocol

- Transport: WebSocket, `ws://127.0.0.1:<port>`. The hub tries port 47821 first and falls back to a random free port. The chosen port is written to `hub.json` (see §5).
- Framing: JSON-RPC 2.0. The hub sends requests to agents. Agents send responses, plus notifications for events.
- **Handshake (first message from agent):** `hello { token, agentKind: "client"|"server", agentVersion, protocolVersion, mcVersion, instanceName }`. The hub replies `welcome { instanceId }` or closes with a reason. Incompatible `protocolVersion` majors are rejected with an actionable message.
- **Event notifications (agent → hub):** `log`, `chat`, `hud` (bossbar/actionbar/title changes), `screen` (GUI opened/closed), `player` (join/quit), `process` (server ready). The hub keeps a ring buffer per instance (default 5,000 entries) that `logs`, `chat` and `wait_for` read from.
- **Idempotency:** any request may carry `operationId`. Agents cache results by `operationId` for 5 minutes. A repeated request returns the cached result and does not re-execute.
- **Threading:** client actions run on the render thread. Server actions run on the main thread, or on the owning region or entity scheduler under Folia. Agents never block those threads waiting on the network.
- **Screenshots:** the client agent encodes the image. It returns a downscaled image to the model (default long edge 1600 px, PNG; JPEG when large) and, when `savePath` is given, also writes the full-resolution PNG to disk.

## 4. Tools

All tools are exposed by the hub as `mcp__craftwire__<name>`. When exactly one matching instance is connected, `instance` is optional. Every tool returns either a result or a structured error (§6).

### Hub
| Tool | Purpose | Key params |
|---|---|---|
| `list_instances` | Connected clients and servers with versions and state | — |
| `wait_for` | Block until a condition holds or the timeout expires | `condition: log_match \| chat_match \| screen_open \| screen_closed \| player_join \| hud_match`, `pattern`, `timeoutMs` |
| `get_request_status` | Status of an `operationId` (queued, running, done, failed) | `operationId` |

### Client (Fabric agent)
| Tool | Purpose | Key params |
|---|---|---|
| `screenshot` | Capture the frame | `hud` (bool), `maxSize`, `savePath`, optional `camera {x,y,z,yaw,pitch,fov}` applied for this capture only |
| `camera` | Control the view | `action: set \| look_at \| frame_entity \| frame_area \| freecam_on \| freecam_off \| reset`, coordinates/target |
| `gui_read` | Describe the open screen | Returns title, type, slots `[index, item, count, name, lore[], enchanted]`, hovered tooltip, widgets (buttons, text fields) |
| `gui_action` | Interact with the open screen | `action: hover \| click \| right_click \| shift_click \| drag \| type \| close`, `slot` or `widget` or `x,y` |
| `input` | Keys and mouse | `keys[]`, `mode: press \| hold \| release`, `durationMs`, `mouse {dx,dy,scroll}`, `hotbar` |
| `chat` | Send or read chat | `action: send \| command \| read`, `text`, `since`, `contains`, `limit` |
| `hud_read` | Read HUD state | Returns bossbars, actionbar, title/subtitle, scoreboard sidebar, tab list |
| `player_state` | Local player snapshot | Returns position, rotation, health, inventory, held item, target block/entity, dimension |
| `client_settings` | Adjust rendering | `guiScale`, `fov`, `renderDistance`, `hideHud`, `windowSize` |

### Server (Paper agent)
| Tool | Purpose | Key params |
|---|---|---|
| `server_command` | Run a command and capture its output | `command`, `asPlayer?` (an online player's name: the command is dispatched with that player as the sender instead of the console) |
| `server_eval` | Run JavaScript (GraalJS) with Bukkit/Paper API access | `code`, `timeoutMs` (default 5000), `at {world,x,z}` (Folia region), `reset` |
| `world_query` | Read the world | `action: block \| region \| entities \| players \| find_block`, bounds/filters |
| `world_edit` | Write the world | `action: set_blocks \| fill \| save_schematic \| paste_schematic \| snapshot \| restore`, bounds/data |
| `server_info` | Health and metadata | Returns TPS, MSPT, memory, version, plugin list |
| `logs` | Server or client logs | `instance`, `level`, `contains`, `since`, `limit`; stack traces grouped |
| `plugin_manage` | Plugin state | `action: list \| info \| enable \| disable`, `name` |
| `bot_spawn` / `bot_remove` | Server-side fake players | `count`, `namePrefix`, `location` / `name \| all` |
| `bot_action` | Drive a bot | `bot`, `action: chat \| command \| move_to \| look \| use \| attack \| gui_read \| gui_click \| give \| select_hotbar`, args |

### Dev loop (hub)
| Tool | Purpose | Key params |
|---|---|---|
| `server_process` | Manage a local Paper server | `action: start \| stop \| restart \| status`, `serverDir`, `jvmArgs`; "ready" means the `Done (` log line was seen and the agent connected |
| `plugin_deploy` | Build and redeploy a plugin | `projectDir`, `buildCommand?` (Gradle/Maven auto-detected), `jarGlob?`, `serverDir`, `restart` (default true); parses compiler errors into `file:line: message` |

### Script environment (`server_eval`)
- Engine: GraalJS (ES2023), loaded through Paper's library loader so the plugin jar stays small. It runs in interpreter mode on non-Graal JVMs and the engine warning is suppressed.
- Globals: `server`, `player(name)`, `plugin(name)`, `loc(x, y, z, world?)`, `Java.type(...)`, `print(...)`.
- Return value is JSON-serialised. Java objects are summarised as `{class, toString}`. Output from `print` is captured.
- Bindings persist across calls per hub session until `reset: true`.
- A watchdog cancels the context when `timeoutMs` elapses. An infinite loop never hangs the server.

## 5. Security

- The hub binds `127.0.0.1` only. Agents open no ports.
- On first run the hub creates a 256-bit random token and writes `~/.craftwire/hub.json` (`{port, token}`) with user-only permissions. Agents on the same machine read this file automatically. The token is compared in constant time.
- WebSocket upgrades that carry an `Origin` header are rejected, which blocks browser-initiated connections to localhost.
- Client agent: an on-screen "⚡ Craftwire connected" indicator, hidden during captures. **F8** is a kill switch that pauses all AI control until pressed again.
- Paper agent config, all enabled by default for dev use:
  ```yaml
  allow-eval: true
  allow-world-edit: true
  allow-bots: true
  max-edit-volume: 1000000
  ```
  A startup console warning reads "Craftwire is active — do not run on production servers". A disabled capability returns `PERMISSION_DISABLED`.
- `world_edit` takes an automatic snapshot before any edit larger than 32,768 blocks, so the edit can be restored.
- Audit log: every tool call (time, tool, params, outcome) is written to `~/.craftwire/logs/`.

## 6. Error handling

Errors use the shape `{ code, message, hint }`. `hint` names the next corrective action.

| Code | When |
|---|---|
| `NO_INSTANCE` / `AMBIGUOUS_INSTANCE` | No matching agent, or several matching agents and no `instance` given |
| `AGENT_DISCONNECTED` | The agent dropped mid-request. Retry with the same `operationId` |
| `TIMEOUT` | Request or `wait_for` exceeded its timeout |
| `NO_SCREEN_OPEN` / `SLOT_OUT_OF_RANGE` / `WIDGET_NOT_FOUND` | GUI preconditions failed |
| `PERMISSION_DISABLED` | Capability turned off in config |
| `EVAL_ERROR` | JS error, with line, column and stack |
| `SERVER_NOT_RUNNING` / `BUILD_FAILED` | Dev-loop failures. `BUILD_FAILED` carries parsed compiler errors |
| `PROTOCOL_MISMATCH` | Agent and hub versions are incompatible. The message names which side to update |

Other rules:
- Agents catch every handler exception and return it as an error. Nothing propagates into the game loop.
- Reconnect uses exponential backoff from 1 s to 30 s.
- When an agent disconnects, all of its pending requests fail fast with `AGENT_DISCONNECTED`.

## 7. Testing

| Layer | Approach |
|---|---|
| Protocol | Schemas generate TS and Java types. Contract tests validate fixtures on both sides |
| Hub | Vitest with in-memory fake agents. Each tool is exercised through a real MCP client (SDK) |
| Paper agent | Integration tests against a real Paper server launched by Gradle (run-paper): command capture, eval (incl. timeout), world edit and snapshot/restore, bots |
| Fabric agent | Fabric client gametest API runs a real client in CI (xvfb): `gui_read`, `gui_action`, `screenshot`, `camera`, `hud_read` against GUIs from `test-fixtures` |
| End-to-end | Server + client + hub. The fixture plugin opens a chest GUI, then `gui_read`, hover tooltip, click, screenshot non-empty, and `wait_for` are asserted |
| Acceptance | Manual: the uxmBuilders promo scenario (§1) |

All automated layers run on GitHub Actions for every push.

## 8. Distribution

| Component | Channel | User action |
|---|---|---|
| Hub | npm `craftwire` | None. Started by the Claude plugin via `npx -y craftwire@^1` |
| Claude Code plugin | GitHub marketplace in `uxplima/craftwire` | `/plugin marketplace add uxplima/craftwire`, then `/plugin install craftwire` |
| Craftwire Agent (mod) | Modrinth + GitHub Releases | Install into the Fabric profile (requires Fabric API) |
| Craftwire (plugin) | Hangar + Modrinth + GitHub Releases | Drop into `plugins/` |

- `craftwire doctor` CLI: checks Node version, `hub.json`, connected agents and version compatibility, and prints fixes.
- Skills shipped with the Claude plugin: `craftwire` (tool workflows), `minecraft-promo-shots`, `paper-plugin-dev`, `fabric-mod-dev`.
- Versioning: lockstep SemVer across all components, plus an integer `protocolVersion`. A pushed tag triggers Actions to build and publish to npm, Modrinth and Hangar.
- Docs: a README with a 30-second quickstart and GIF, a tool reference generated from schemas, plus `CONTRIBUTING.md`, `SECURITY.md` and `CODE_OF_CONDUCT.md`.

## 9. Milestones

All four are in v1 and land in order. Each one is usable on its own.

1. **M1 — Core + client:** protocol, hub skeleton, `agent-core`, Fabric agent, client tools, `list_instances`, `wait_for`, `get_request_status`. Unblocks the Builders screenshots.
2. **M2 — Server agent:** `server_command`, `server_eval`, `world_query`, `world_edit`, `server_info`, `logs`, `plugin_manage`.
3. **M3 — Dev loop:** `server_process`, `plugin_deploy`, `craftwire doctor`.
4. **M4 — Bots:** `bot_spawn`, `bot_remove`, `bot_action`.

Packaging (Claude plugin, skills, CI publishing) is finished alongside M1 and grows with each milestone.

## 10. Open risks

- **Fabric client gametest in CI:** headless GL via xvfb can be flaky. Fallback: run the client E2E suite on a self-hosted Windows runner.
- **GraalJS size and first-run download:** Paper's library loader needs network access on first start. This is documented, and a "fat" jar variant is offered for offline servers.
- **Fake players on Paper 26.x:** relies on internal (Mojang-mapped) server classes, so it may need small fixes on each Minecraft update. This is isolated in one package of `agent-paper`.
- **Freecam and camera override:** must not desync the server-side player position. Camera overrides are client-only render changes.
