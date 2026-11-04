# Craftwire protocol v1

Transport: WebSocket `ws://127.0.0.1:<port>/` (port from `~/.craftwire/hub.json`). Framing: JSON-RPC 2.0, one message per text frame.

1. Agent → hub, first message: request `hello` with id `0`.
   params: `token`, `agentKind` ("client"|"server", and on next "botrunner"|"proxy"), `agentVersion`, `protocolVersion` (1), `mcVersion`, `instanceName`. Server agents (0.3.0+) also send `serverDir` (the server's working directory) and `pid` (its JVM process id); the hub uses them to recognise servers it started.
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

Server methods (M4, bots; `allow-bots` gates all three; `bot.spawn` and `bot.remove` changed shape in Next, see "Bot methods" below):
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
- `trace.run` `{method, durationMs?, minMs?, stackDepth? (1-32, 8), limit? (1-200, 50)}` uses Flight Recorder method tracing (Java 25+, else `UNSUPPORTED`); `method` is up to 5 filters separated by `;` (`pkg.Class::method`, `pkg.Class`, `@pkg.Annotation`), else `INVALID_PARAMS` → `{filter, durationMs, methods: [{method, owner, invocations, avgMs, maxMs, totalMs}], slowest: [{method, owner, ms, thread, stack: [{method, line, owner}]}], callers: [{method, owner, count}], calls, truncated, hint?}`. Stops after 100000 calls (`truncated: true`). One trace at a time per agent: `ALREADY_RUNNING` while another runs.

Client methods:
- `client.eval` `{code, timeoutMs?, reset?}` runs JavaScript (GraalJS) on the render thread → `{result, output}`. Errors: `EVAL_DISABLED` (`allow-eval=false` in `config/craftwire.properties` or `-Dcraftwire.allowEval=false`), `DOWNLOAD_FAILED` (GraalJS could not be fetched or failed its sha256 check), `EVAL_UNAVAILABLE`, `TIMEOUT`, `EVAL_ERROR`.

Server methods:
- `bot.action move_to` finds a path by default: `{x, y, z, tolerance?, sprint?, timeoutMs? (30000), path? (true), maxFall? (3), openDoors? (true), partial?}` → `{reached, reason: arrived|no_path|blocked|height|stuck|timeout|died, x, y, z, distance, path?: {nodes, length, complete}, replans?, closest?: {x, y, z}}`. `TOO_FAR` beyond 256 blocks.
- New actions: `break_block {block: {x, y, z}, face?}` → `{broken, block, ticks, reason?, cancelledBy?}` (`OUT_OF_REACH`); `jump` → `{jumped, reason?}`; `sneak {on?}`; `sprint {on?}`; `drop {all?}` → `{dropped: item|null, cancelled?, held?}` (`NOTHING_HELD`); `swap_hands`. `state` adds `digging`, `sneaking`, `sprinting`.

M8 (protocol version unchanged; older agents answer `UNKNOWN_METHOD`, and `camera` answers `INVALID_PARAMS` for the new actions):

Client methods:
- `record` `{action: "start"|"stop"|"status", ...}`. `start` `{savePath (.mp4, absolute), preset? (max|high|balanced|light, high), fps? (10-120), resolution? (source|2160p|1440p|1080p|720p|480p), codec? (h264|h265), crf? (0-51), speed? (x264 preset), audio? (true), audioBitrate? (64-320), hud? (false), chat? (true), durationMs? (>= 100), maxSeconds? (1-600, 120), camera? ({action: path|orbit, ...} as `camera`), waitForTerrain? (true), wait? (false)}` → `{recording, savePath, width, height, fps, preset, audio, durationMs?, maxSeconds}`, or with `wait` the `stop` result. `stop` → `{savedPath, reason: requested|duration|maxSeconds|resized|quit, durationMs, fps, width, height, frames, duplicatedFrames, droppedFrames, audio, codec, preset, crf, speed, sizeBytes, encodeMs, note?}`; after a recording ended by itself, its result. `status` → `{recording, state?: preparing|recording|finishing, savePath?, elapsedMs?, frames?, duplicatedFrames?, droppedFrames?, queuedFrames?, last?, lastError?: {code, message}}`. Errors: `FFMPEG_NOT_FOUND`, `ALREADY_RECORDING`, `NOT_RECORDING`, `RECORD_STARTING`, `AUDIO_UNAVAILABLE`, `RECORD_FAILED`, `INVALID_PARAMS`.
- `camera` new actions: `path` `{keyframes: [{t, x, y, z, yaw?, pitch?}] (2-64), interpolation? (smooth|linear), ease? (inOut|none, inOut), lookAt? {x, y, z}}`, `orbit` `{center {x, y, z}, radius, height? (radius / 2), startAngle?, degrees? (360), durationMs, ease? (none)}`. The reply adds `motion: {kind, durationMs}` while a move plays or holds its last pose.

M9 (protocol version unchanged; the same plugin runs on Paper and Folia):

Server methods:
- `server.eval` adds `asPlayer?` (run on that online player's thread; `PLAYER_NOT_FOUND`). `at` loads its chunk first. On Folia a script touching a block, entity or player off its thread fails with `WRONG_THREAD` (also `server.command`, `wait`, `world.edit`).
- `server.info` on Folia adds `folia: true`, `regions: [{world, chunkX, chunkZ, tps: [5s, 15s, 1m, 5m, 15m], at: spawn|player, player?}]`, `slowestRegion?`.
- `profile.run` on Folia samples every region thread: `thread` is `Folia Region Scheduler Thread #*`, plus `threads: [{name, samples}]`.
- `plugin.manage enable|disable` on Folia → `UNSUPPORTED`.
- `bot.*` on a bot that left between the lookup and the action → `BOT_NOT_FOUND`.

Next (protocol version unchanged; older agents answer `UNKNOWN_METHOD` for the new methods):

Hello:
- `agentKind` is also `"botrunner"` (the external bot runner) or `"proxy"` (the Velocity plugin). A bot runner adds `botKinds` (`["java", "bedrock"]`, the kinds it runs) and `maxBots`; the hub splits a protocol-bot spawn over the runners of that kind by their free capacity. The proxy sends `serverDir` and `pid` like a server, and as `mcVersion` the newest Minecraft version it accepts.

Bot methods (Paper agent for `kind: plugin`, bot runners for `java` and `bedrock`; same shapes on both):
- `bot.spawn` `{kind?, count?, namePrefix?, names?, group?, location?, spread?: {radius}, perTick?, profile?: light|realistic, settings?: {locale?, brand?, ping?, skin?, mainHand?, chat?, skinParts?, viewDistance?}, resourcePack?, verifyPacks?, hideBots?, detail?, seed?}`; protocol bots add `address`, `version?`, `auth?: offline|microsoft`, `session?` / `xbox?` (the account's tokens, put in by the hub from `account`; never logged), `worldMode?: shared|none`, `netsim?: {latencyMs?, jitterMs?, lossPercent?}` → `{spawned, names (compact, "Bot1..Bot50"), failed?: [{name, error}], ms, bots? (with detail)}`.
- `bot.remove` `{bot | name | bots (names or a pattern) | group | all: true, names?}` → `{removed, names?}`.
- `bot.list` `{bot?, bots?, group?, limit?}` → `{count, joining, bots: [{name, kind, uuid, group?, world, x, y, z}]}`.
- `bot.action` `{bot | bots | group, action, …}`: one bot answers with the action's result; a selector answers `{bots, failed, results: [{bot, result | error}]}`. Actions: chat, command, messages, look, move_to, stop, state, give, select_hotbar, settings, resource_packs, gui_read, gui_click (`click`: left|right|shift|number (`key`)|offhand|drop|drop_stack|middle|double|drag (`slots`, `button`)|outside), gui_close, move_item, transfer, equip, use, attack, hud_read, break_block, jump, sneak, sprint, drop, swap_hands, anvil_rename, sign_edit, book_edit, trade_select, menu_button, beacon, recipe_place, creative_set, dialog_answer, chat_click. A kind that cannot do one answers `UNSUPPORTED_FOR_KIND`.
- `bot.behavior` `{bot | bots | group, action: set|stop|status, type?: wander|follow|patrol|chat|command_loop|menu_click|idle|script, params?, seed?}`.
- `bot.form` `{bot, action: read|answer|close, button?, values?}` (Bedrock bots; Java bots answer `UNSUPPORTED_FOR_KIND`).
- `world.query entities` rows of dropped items add `item` (namespaced id) and `count`.

Bot runner methods:
- `runner.stats` `{}` → `{bots, maxBots, tickMs, heapMb, worldCache}`; `load_test` reads it each step to show how the bots were spread over runners.

Client events:
- `resource_pack` `{id, status: accepted|downloaded|successfully_loaded|declined|failed_download|invalid_url|failed_reload|discarded, final}`; `final: true` once the pack loaded or failed.

Proxy methods (`agentKind: "proxy"`):
- `proxy.info` `{}` → `{name, version, vendor, port, onlineMode, players, tryOrder: [server names], servers: [{name, address, players}]}`
- `proxy.servers` `{ping? (true), timeoutMs? (3000)}` → `{count, servers: [{name, address, players, online?, latencyMs?, version?, protocol?, onlinePlayers?, maxPlayers?, motd?, error?}]}`
- `proxy.players` `{server?, limit? (200)}` → `{count, players: [{name, uuid, server?, pingMs, protocol, version, onlineMode}]}`
- `proxy.send` `{player, server}` → `{player, server, status: success|already_connected|connection_in_progress|connection_cancelled|server_disconnected, successful, reason?, hint?}`
- `proxy.kick` `{player, reason?}` → `{player, kicked: true}`
- `proxy.command` `{command, collectMs? (300)}` runs a proxy command as a console with every permission → `{command, success, output[]}`; `UNKNOWN_COMMAND` when nothing handled it.
- Errors: `PLAYER_NOT_FOUND`, `SERVER_NOT_FOUND`, `INVALID_PARAMS`.

Proxy events:
- `proxy` `{action: join|switch|kick|leave, name, server?, from?, reason?, duringJoin?, text}` (`text` is what `wait_for {condition: "server_switch", pattern}` matches, e.g. `Bob lobby -> games`).
- `log` as from the other agents.
