# Craftwire protocol v1

Transport: WebSocket `ws://127.0.0.1:<port>/` (port from `~/.craftwire/hub.json`). Framing: JSON-RPC 2.0, one message per text frame.

1. Agent → hub, first message: request `hello` with id `0`.
   params: `token`, `agentKind` ("client"|"server"), `agentVersion`, `protocolVersion` (1), `mcVersion`, `instanceName`. Server agents (0.3.0+) also send `serverDir` (the server's working directory) and `pid` (its JVM process id); the hub uses them to recognise servers it started.
2. Hub → agent: response id `0` with `result.instanceId`, or `error` with `data.code` (`UNAUTHORIZED`, `PROTOCOL_MISMATCH`) followed by close.
3. Hub → agent: requests `{id, method, params}`. Params may include `operationId`.
4. Agent → hub: responses `{id, result}` or `{id, error: {code: -32000, message, data: {code, hint}}}`.
5. Agent → hub: notifications `{method: "event", params: {type, time, data}}`.
6. Tools (not agents) may instead open a connection with request `status` `{token}`: the hub answers `{hubVersion, protocolVersion, instances[], rejected[]}` (`rejected` = the last 20 refused hellos: `{time, code, agentKind, agentVersion, instanceName, protocolVersion}`) and closes with 1000. A wrong token gets `UNAUTHORIZED` and close 4001. `craftwire doctor` uses this.

Event types (M1): `chat` `{text, kind: "chat"|"game", sender?}`, `hud` `{element: "actionbar", text}`, `screen` `{open, title, type}`.

Client methods (M1): `player.state`, `chat.send`, `hud.read`, `gui.read`, `gui.action`, `screenshot`, `camera`, `input`, `client.settings`.

Event types (M2): `log` `{level, logger, thread, message, thrown?}` (both agents; replayed from a 1000-line backlog on every connect), `player` `{action: "join"|"quit", name, uuid}` and `chat` `{text, kind: "chat", sender}` from the server agent.

Server methods (M2, `agentKind: "server"`):
- `server.command` `{command, asPlayer?, collectMs?}` → `{command, success, output[], sender?, note?}`
- `server.eval` `{code, timeoutMs?, reset?, at?: {world?, x, z}}` → `{result, output}`
- `world.query` `{action: block|region|entities|players|find_block, world?, …}`
- `world.edit` `{action: set_blocks|fill|snapshot|restore|save_schematic|paste_schematic, world?, …}` → results carry `snapshotId` when a snapshot was taken
- `server.info` `{}` → `{name, version, minecraftVersion, tps[3], mspt, memory, players, worlds[], plugins[]}`
- `plugin.manage` `{action: list|info|enable|disable, name?}`

Server methods (M4, bots; `allow-bots` gates all three):
- `bot.spawn` `{count?, namePrefix?, names?, location?: {world?, x, y, z, yaw?, pitch?}}` → `{bots: [{name, uuid, world, x, y, z}]}`
- `bot.remove` `{name}` or `{all: true}` → `{removed: [names]}`
- `bot.action` `{bot, action, …}`. Actions: chat, command, messages, look, move_to, state, give, select_hotbar, gui_read, gui_click, gui_close, use, attack (parameters as in the `bot_action` tool).
`world.query players` rows carry `bot: true` for bots.

M6 (protocol version unchanged: an older agent answers these methods with `UNKNOWN_METHOD`, whose hint says to update it):

Event types:
- `tools` `{tools: [{name, namespace, description, inputSchema}]}` (both agents): the extension tools plugins and mods registered through `craftwire-api`. The full list, sent on every connect and whenever it changes; the hub drops an instance's tools when it disconnects.

Methods, both agents:
- `ext.call` `{tool, args}` → the tool's JSON result. Errors: `EXTENSION_NOT_FOUND`, `EXTENSION_FAILED` (a bug in the tool, logged with its stack), or the tool's own `ToolException` code.

Client methods:
- `world.open` `{name, create?: {type?: normal|flat|void, seed?, gameMode?, difficulty?, cheats?}}` → `{name, created}` once loading has been started (`opening: true` when that world is already loading). `NOT_READY` while the client is still starting; `WORLD_NOT_FOUND`, `WORLD_EXISTS`, `ALREADY_IN_WORLD`.

Server methods:
- `events` `{action: summary|query|listeners|watch, type?, types?, player?, since?, limit?, cancelledOnly?}` (`record-events` gates it)
- `wait` `{condition: block|player_near|inventory|event|message|expr, timeoutMs, …}` → `{matched: true, condition, elapsedMs, value}` or, at the timeout, `{matched: false, condition, elapsedMs, last}`. Tick conditions are checked every server tick; `event` and `message` count only what happens after the call.
- `world.render` `{x1, z1, x2, z2, world?, view?: top|slice|side, y?, facing?, y1?, y2?, scale?, grid?, players?, generate?}` → `{mime, data (base64 PNG), width, height, view, region, scale, origin {x, y}, orientation, unloadedColumns?, players?}`
- `bot.action command` now sends the command packet: the result adds `cancelled`, `unknown` and `rewrittenTo`.

M7 (protocol version unchanged; older agents answer `UNKNOWN_METHOD`):

Methods, both agents:
- `profile.run` `{durationMs? (1000-60000, 10000), intervalMs? (5-50, 10), top? (1-50, 15)}` samples the game thread (`Server thread` / `Render thread`) → `{thread, durationMs, intervalMs, samples, idleSamples, busyPercent, truncatedStacks, owners: [{owner, kind, samples, percent}], entryPoints: [{owner, kind, method, event?, task?, calledFrom?, percent}], hotMethods: [{method, owner, kind, percent}], ticks?: {count, msptAvg, p50, p95, max, over50ms, slowest: [{tick, ms, owners}]}, fps?: {avg, min}}`. `kind` is `plugin`, `mod`, `minecraft`, `server`, `loader`, `java`, `craftwire` or `library`. Ends with `CANCELLED` when the agent shuts down while sampling.
- `trace.run` `{method, durationMs?, minMs?, stackDepth? (1-32, 8), limit? (1-200, 50)}` uses Flight Recorder method tracing (Java 25+, else `UNSUPPORTED`); `method` is up to 5 filters separated by `;` (`pkg.Class::method`, `pkg.Class`, `@pkg.Annotation`), else `INVALID_PARAMS` → `{filter, durationMs, methods: [{method, owner, invocations, avgMs, maxMs, totalMs}], slowest: [{method, owner, ms, thread, stack: [{method, line, owner}]}], callers: [{method, owner, count}], calls, truncated, hint?}`. Stops after 100000 calls (`truncated: true`).

Client methods:
- `client.eval` `{code, timeoutMs?, reset?}` runs JavaScript (GraalJS) on the render thread → `{result, output}`. Errors: `EVAL_DISABLED` (`allow-eval=false` in `config/craftwire.properties` or `-Dcraftwire.allowEval=false`), `DOWNLOAD_FAILED` (GraalJS could not be fetched or failed its sha256 check), `EVAL_UNAVAILABLE`, `TIMEOUT`, `EVAL_ERROR`.

Server methods:
- `bot.action move_to` finds a path by default: `{x, y, z, tolerance?, sprint?, timeoutMs? (30000), path? (true), maxFall? (3), openDoors? (true), partial?}` → `{reached, reason: arrived|no_path|blocked|height|stuck|timeout|died, x, y, z, distance, path?: {nodes, length, complete}, replans?, closest?: {x, y, z}}`. `TOO_FAR` beyond 256 blocks.
- New actions: `break_block {block: {x, y, z}, face?}` → `{broken, block, ticks, reason?, cancelledBy?}` (`OUT_OF_REACH`); `jump` → `{jumped, reason?}`; `sneak {on?}`; `sprint {on?}`; `drop {all?}` → `{dropped: item|null, cancelled?, held?}` (`NOTHING_HELD`); `swap_hands`. `state` adds `digging`, `sneaking`, `sprinting`.
