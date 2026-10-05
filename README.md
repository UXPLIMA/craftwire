# Craftwire

Let AI agents (Claude Code and any MCP client) **see and drive Minecraft**: screenshots, a free camera, reading and clicking GUIs, chat and commands, HUD reading and input. Server control, scripting, a plugin dev loop and bots follow in the next milestones.

By [UXPLIMA](https://github.com/uxplima) · MIT licensed · Minecraft 26.2 (Fabric)

## Quick start (Claude Code)

1. Install the plugin:
   ```
   /plugin marketplace add uxplima/craftwire
   /plugin install craftwire@uxplima
   ```
2. Install **Craftwire Agent** (Fabric mod, requires Fabric API) into your Minecraft 26.2 profile.
3. Start Minecraft and join a world. A green **⚡ Craftwire connected** appears top-left. **F8** pauses AI control at any time.

Ask Claude: *"take a screenshot of what I'm looking at"*.

> Windows: if the MCP server does not start, edit `.mcp.json` in the plugin to use `"command": "cmd", "args": ["/c", "npx", "-y", "craftwire@0.1.0"]`.

## How it works

`craftwire` (npm) is an MCP server over stdio. It listens on `127.0.0.1` only and writes its port and a random token to `~/.craftwire/hub.json`. The mod reads that file and connects out to the hub — the game opens no ports. Every tool call is logged to `~/.craftwire/logs/`.

## Tools (M1)

`list_instances` · `wait_for` · `get_request_status` · `screenshot` · `camera` · `gui_read` · `gui_action` · `input` · `chat` · `hud_read` · `player_state` · `client_settings`

## Development

- Hub: `cd hub && npm install && npm test`
- Agents: `./gradlew :agent-core:test :agent-fabric:test :agent-fabric:runClientGameTest`
- Dev client with the mod: `./gradlew :agent-fabric:runClient`
