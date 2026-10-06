<img src="https://raw.githubusercontent.com/uxplima/craftwire/main/docs/images/banner.jpg" alt="Craftwire: let AI agents see and drive Minecraft" width="100%">

# craftwire

This package is the MCP server (hub) of **Craftwire**. It lets AI agents **see and drive Minecraft**:
- On a **Fabric client**: screenshots, a free camera, reading and clicking GUIs, chat, input and the HUD.
- On a **Paper server**: console commands, JavaScript against the Bukkit API, world reads and edits, logs and plugin control.
- A plugin dev loop and server-side bots.

Minecraft 26.2 · Fabric client · Paper server · MIT · by [UXPLIMA](https://github.com/uxplima)

## Set up your AI client

```
npx craftwire setup            # lists the clients found on this computer
npx craftwire setup codex      # or: gemini, antigravity, cursor, windsurf, vscode, claude-desktop
```

`setup` adds the `craftwire` entry to the client's MCP config. It leaves the other entries alone and backs the file up as `.bak` first. For Codex and Gemini CLI it also installs Craftwire's skills. `--dry-run` shows the change without writing it.

**Claude Code** uses the plugin instead:

```
/plugin marketplace add uxplima/craftwire
/plugin install craftwire@uxplima
```

**Any other MCP client** runs it over stdio:

```json
{ "mcpServers": { "craftwire": { "command": "npx", "args": ["-y", "craftwire"] } } }
```

On Windows, use `"command": "cmd", "args": ["/c", "npx", "-y", "craftwire"]`.

## Add the game side

Get both jars from [Releases](https://github.com/uxplima/craftwire/releases):
- `craftwire-agent-fabric-<version>.jar`: put it in your Fabric profile's `mods/` folder, with Fabric API.
- `craftwire-paper-<version>.jar`: put it in a Paper server's `plugins/` folder.

Both connect to the hub on their own. The hub listens on `127.0.0.1` only and writes a token to `~/.craftwire/hub.json`. Use one AI client with Craftwire at a time.

No game open? The `client_process` tool starts a hidden client the hub runs itself. It downloads Minecraft and Fabric from their official servers on first use, then joins your dev server, which needs `online-mode=false`.

Troubleshooting: `npx craftwire doctor`. Add `--server <folder>` to check a server folder too.

Docs, tools and the protocol: https://github.com/uxplima/craftwire
