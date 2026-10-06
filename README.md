# Craftwire

Let AI agents (Claude Code and any MCP client) **see and drive Minecraft**: screenshots, a free camera, reading and clicking GUIs, chat and commands, HUD reading and input — and **drive a Paper server**: console commands, JavaScript against the Bukkit API, world reads and edits with undo snapshots, logs and plugin control. It also runs a local server and builds and redeploys your plugin in one step (dev loop). Server-side bots stand in for players when you test plugins.

By [UXPLIMA](https://github.com/uxplima) · MIT licensed · Minecraft 26.2 (Fabric client, Paper server)

## Quick start (Claude Code)

1. Install the plugin:
   ```
   /plugin marketplace add uxplima/craftwire
   /plugin install craftwire@uxplima
   ```
2. Install **Craftwire Agent** (Fabric mod, requires Fabric API) into your Minecraft 26.2 profile.
3. Start Minecraft and join a world. A green **⚡ Craftwire connected** appears top-left. **F8** pauses AI control at any time.

Ask Claude: *"take a screenshot of what I'm looking at"*.

> Windows: if the MCP server does not start, edit `.mcp.json` in the plugin to use `"command": "cmd", "args": ["/c", "npx", "-y", "craftwire@0.4.0"]`.

## Paper server

Drop `craftwire-paper-<version>.jar` into the server's `plugins/` folder and start it; it connects to the hub on its own.

- The first start downloads GraalJS (for `server_eval`) through Paper's library loader, so it needs network access once.
- `plugins/Craftwire/config.yml` has four switches: `allow-eval`, `allow-world-edit`, `allow-bots` and `max-edit-volume`. A disabled capability answers `PERMISSION_DISABLED`.
- **Development servers only.** Anyone who can run the hub on the machine gets full control of the server; the plugin logs a warning on every start.

Ask Claude: *"what's the TPS, and which plugins logged errors since startup?"*.

## Dev loop

Point Claude at a server folder and a plugin project:

- `server_process` starts, stops and restarts a local Paper server. JVM flags come from its start script. It never accepts the EULA for you.
- `plugin_deploy` builds the project (Gradle or Maven, tests skipped) or takes a ready jar, swaps it into `plugins/` (old jar kept in `plugins/.craftwire-backup/`), restarts the server and reports whether the plugin enabled and what it logged. Compiler errors come back as `file:line`.

Ask Claude: *"build my plugin, deploy it to ~/servers/test and tell me what broke"*.

Something not connecting? Run `npx craftwire doctor` (add `--server <folder>` to check a server folder too).

## Bots

`bot_spawn` puts fake players on a Paper server running the Craftwire plugin. They join like real players, so plugins see join, chat, command, click and damage events from them.

`bot_action` makes a bot chat, run commands (and returns the replies it received), walk to a point, look, use items and blocks, attack, and read and click plugin menus. `bot_remove` logs them out.

Ask Claude: *"spawn two bots, have one open /shop and buy the first item, and tell me what the plugin answered"*.

## How it works

`craftwire` (npm) is an MCP server over stdio. It listens on `127.0.0.1` only and writes its port and a random token to `~/.craftwire/hub.json`. The mod and the plugin read that file and connect out to the hub — the game and the server open no ports. Every tool call is logged to `~/.craftwire/logs/`.

## Tools

- Hub: `list_instances` · `wait_for` · `get_request_status` · `logs`
- Client (M1): `screenshot` · `camera` · `gui_read` · `gui_action` · `input` · `chat` · `hud_read` · `player_state` · `client_settings`
- Server (M2): `server_command` · `server_eval` · `world_query` · `world_edit` · `server_info` · `plugin_manage`
- Dev loop (M3): `server_process` · `plugin_deploy` · CLI `craftwire doctor`
- Bots (M4): `bot_spawn` · `bot_action` · `bot_remove`

## Development

- Hub: `cd hub && npm install && npm test`
- Agents: `./gradlew :agent-core:test :agent-fabric:test :agent-fabric:runClientGameTest`
- Paper plugin: `./gradlew :agent-paper:test :agent-paper:integrationTest` (downloads Paper 26.2 and runs the plugin in a real server)
- Dev-loop E2E (real Paper server): `./gradlew :agent-paper:build :test-fixtures:build`, then `cd hub && npm run test:e2e`
- Dev client with the mod: `./gradlew :agent-fabric:runClient`
