# Craftwire protocol v1

Transport: WebSocket `ws://127.0.0.1:<port>/` (port from `~/.craftwire/hub.json`). Framing: JSON-RPC 2.0, one message per text frame.

1. Agent → hub, first message: request `hello` with id `0`.
   params: `token`, `agentKind` ("client"|"server"), `agentVersion`, `protocolVersion` (1), `mcVersion`, `instanceName`.
2. Hub → agent: response id `0` with `result.instanceId`, or `error` with `data.code` (`UNAUTHORIZED`, `PROTOCOL_MISMATCH`) followed by close.
3. Hub → agent: requests `{id, method, params}`. Params may include `operationId`.
4. Agent → hub: responses `{id, result}` or `{id, error: {code: -32000, message, data: {code, hint}}}`.
5. Agent → hub: notifications `{method: "event", params: {type, time, data}}`.

Event types (M1): `chat` `{text, kind: "chat"|"game", sender?}`, `hud` `{element: "actionbar", text}`, `screen` `{open, title, type}`.

Client methods (M1): `player.state`, `chat.send`, `hud.read`, `gui.read`, `gui.action`, `screenshot`, `camera`, `input`, `client.settings`.
