# Craftwire

Let AI agents (Claude Code and any MCP client) **see and drive Minecraft**: screenshots, a free camera, reading and clicking GUIs, chat and commands, HUD reading and input — and **drive a Paper server**: console commands, JavaScript against the Bukkit API, world reads and edits with undo snapshots, logs and plugin control. A plugin dev loop and bots follow in the next milestones.

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

> Windows: if the MCP server does not start, edit `.mcp.json` in the plugin to use `"command": "cmd", "args": ["/c", "npx", "-y", "craftwire@0.2.0"]`.

## Paper server

Drop `craftwire-paper-<version>.jar` into the server's `plugins/` folder and start it; it connects to the hub on its own.

- The first start downloads GraalJS (for `server_eval`) through Paper's library loader, so it needs network access once.
- `plugins/Craftwire/config.yml` has four switches: `allow-eval`, `allow-world-edit`, `allow-bots` and `max-edit-volume`. A disabled capability answers `PERMISSION_DISABLED`.
- **Development servers only.** Anyone who can run the hub on the machine gets full control of the server; the plugin logs a warning on every start.

Ask Claude: *"what's the TPS, and which plugins logged errors since startup?"*.

## How it works

`craftwire` (npm) is an MCP server over stdio. It listens on `127.0.0.1` only and writes its port and a random token to `~/.craftwire/hub.json`. The mod and the plugin read that file and connect out to the hub — the game and the server open no ports. Every tool call is logged to `~/.craftwire/logs/`.

## Tools

- Hub: `list_instances` · `wait_for` · `get_request_status` · `logs`
- Client (M1): `screenshot` · `camera` · `gui_read` · `gui_action` · `input` · `chat` · `hud_read` · `player_state` · `client_settings`
- Server (M2): `server_command` · `server_eval` · `world_query` · `world_edit` · `server_info` · `plugin_manage`

## Development

- Hub: `cd hub && npm install && npm test`
- Agents: `./gradlew :agent-core:test :agent-fabric:test :agent-fabric:runClientGameTest`
- Paper plugin: `./gradlew :agent-paper:test :agent-paper:integrationTest` (downloads Paper 26.2 and runs the plugin in a real server)
- Dev client with the mod: `./gradlew :agent-fabric:runClient`
