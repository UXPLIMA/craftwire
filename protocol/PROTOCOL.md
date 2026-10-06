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
