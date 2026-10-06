# craftwire

The MCP server (hub) of **Craftwire**: it lets Claude Code and other MCP clients **see and drive Minecraft**. That covers screenshots, a free camera, reading and clicking GUIs, chat, input and the HUD. It also drives **Paper servers** (console commands, JavaScript against the Bukkit API, world reads and edits, logs, plugin control) and runs a plugin dev loop and server-side bots.

Minecraft 26.2 · Fabric client · Paper server · MIT · by [UXPLIMA](https://github.com/uxplima)

## Use it

The easiest way is the Claude Code plugin, which starts this hub for you:

```
/plugin marketplace add uxplima/craftwire
/plugin install craftwire@uxplima
```

Any other MCP client can run it over stdio:

```json
{ "mcpServers": { "craftwire": { "command": "npx", "args": ["-y", "craftwire"] } } }
```

Then install the game side from [Releases](https://github.com/uxplima/craftwire/releases):
- `craftwire-agent-fabric-<version>.jar` in your Fabric profile's `mods/` folder
- `craftwire-paper-<version>.jar` in a Paper server's `plugins/` folder

Both connect to the hub on their own: the hub listens on `127.0.0.1` only and writes a token to `~/.craftwire/hub.json`.

Troubleshooting: `npx craftwire doctor` (add `--server <folder>` to check a server folder too).

Docs, tools and the protocol: https://github.com/uxplima/craftwire
