# Velocity proxies

The Craftwire Velocity plugin (`craftwire-velocity-<version>.jar`, in the npm package's `agent/` folder) connects a
Velocity proxy to the hub, so the AI can see the proxy as it sees a Paper server: its log and exceptions, its
backend servers, who is where, and its commands.

## Setup

1. Put the jar in the proxy's `plugins/` folder. Velocity runs it on Java 25 (Craftwire's agents need it).
2. Start the proxy on the machine the hub runs on (or give it the hub's `hub.json` through `CRAFTWIRE_HOME`). It
   connects by itself; `list_instances` shows it as kind `proxy`. `-Dcraftwire.name=network` names it.
3. Put the Paper servers behind it as usual; with the Craftwire plugin on them too, every server shows up as its
   own instance.

## The proxy tool

`proxy` is listed while a proxy is connected:

- `info`: version, port, online mode, the try order (where a joining player lands first) and the servers.
- `servers`: each backend pinged: online, latency, players, version and motd (`ping: false` skips the ping).
- `players {server?}`: who is on which server, with ping and protocol version.
- `send {player, server}`: move a player; a refusal comes back with the backend's reason.
- `kick {player, reason?}`.
- `command {command}`: run a proxy command (`velocity info`, `glist`, a proxy plugin's) as a console with every
  permission, and get its output.

The proxy also reports each join, server switch, kick from a backend and leave as an event: `wait_for {condition:
"server_switch", pattern: "Bob lobby -> games"}` waits for one.

## Cross-server tests

Java bots from a bot runner join through the proxy like players:

```json
{
  "name": "lobby to games",
  "setup": [{ "tool": "bot_spawn", "args": { "kind": "java", "names": ["Tester"], "address": "127.0.0.1:25577" } }],
  "steps": [
    { "bot": "Tester", "command": "server games" },
    { "wait": { "condition": "server_switch", "pattern": "Tester .*-> games", "timeoutMs": 15000 } },
    { "expect": { "tool": "proxy", "args": { "action": "players", "server": "games" }, "path": "players[*].name", "equals": "Tester" } }
  ],
  "cleanup": [{ "tool": "bot_remove", "args": { "bot": "Tester" } }]
}
```

Plugin bots (kind `plugin`) live inside one Paper server and never pass through the proxy; use Java bots for
anything that involves the proxy. With `player-info-forwarding-mode` set to modern, the backends must trust the
proxy's forwarding secret as they would for real players; offline-mode bots need `online-mode = false` on the proxy.
