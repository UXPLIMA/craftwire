# Using Craftwire with other AI clients

Craftwire's hub is a standard MCP server over stdio (`npx -y craftwire`). Any MCP client can run it. Claude Code gets it as a plugin; for the others one command writes the config:

```
npx craftwire setup            # lists the clients found on this computer
npx craftwire setup cursor     # or: antigravity, codex, gemini, windsurf, vscode, claude-desktop
npx craftwire setup codex --dry-run   # shows the change, writes nothing
```

`setup` adds or updates only the `craftwire` entry, keeps every other server and setting in the file, and saves the old file as `<file>.bak` first. It refuses files it cannot parse as plain JSON (for example JSON with comments) rather than rewrite them; add the entry by hand then. For Codex and Gemini CLI it also copies Craftwire's skills (the how-to notes for the dev loop, mods and promo shots) into the client's skills folder.

Restart the client afterwards, then ask it to run `list_instances`. The game side is the same for every client: the agent mod or the Paper plugin from [Releases](https://github.com/uxplima/craftwire/releases), see the [README](../README.md).

## One AI client at a time

Agents (the mod and the plugin) connect to the hub written to `~/.craftwire/hub.json`. When a second client starts its own hub while one is running, the new hub takes a free port and rewrites `hub.json`, and agents that reconnect follow the newest hub. Use one AI client with Craftwire at a time; close the other or remove `craftwire` from its config.

## Manual setup

The entries below are what `setup` writes. Use `craftwire@<version>` to match your agent mod and plugin (`npx craftwire --version`), or plain `craftwire` for the latest.

On Windows, write the command as `"command": "cmd", "args": ["/c", "npx", "-y", "craftwire"]`. Several clients start commands without a shell and cannot find `npx.cmd` otherwise.

### Antigravity

`~/.gemini/config/mcp_config.json` (older versions: `~/.gemini/antigravity/mcp_config.json`). In the app: **Manage MCP Servers** → **View raw config**.

```json
{
  "mcpServers": {
    "craftwire": { "command": "npx", "args": ["-y", "craftwire"] }
  }
}
```

### Codex

`~/.codex/config.toml` (or `$CODEX_HOME/config.toml`), used by the Codex CLI and the IDE extension:

```toml
[mcp_servers.craftwire]
command = "npx"
args = ["-y", "craftwire"]
startup_timeout_sec = 60
```

The first `npx` run downloads the package, which can take longer than Codex's default 10 s start timeout. Or: `codex mcp add craftwire -- npx -y craftwire`. Skills go to `~/.codex/skills/<name>/SKILL.md`.

### Gemini CLI

`~/.gemini/settings.json`:

```json
{
  "mcpServers": {
    "craftwire": { "command": "npx", "args": ["-y", "craftwire"] }
  }
}
```

Skills go to `~/.gemini/skills/<name>/SKILL.md`.

### Cursor

`~/.cursor/mcp.json` (all projects) or `.cursor/mcp.json` in a project:

```json
{
  "mcpServers": {
    "craftwire": { "command": "npx", "args": ["-y", "craftwire"] }
  }
}
```

### Windsurf

`~/.codeium/windsurf/mcp_config.json` (Windows: `%USERPROFILE%\.codeium\windsurf\mcp_config.json`). Same `mcpServers` form as Cursor.

### VS Code (Copilot agent mode)

User `mcp.json`: `%APPDATA%\Code\User\mcp.json` on Windows, `~/Library/Application Support/Code/User/mcp.json` on macOS, `~/.config/Code/User/mcp.json` on Linux. Or `.vscode/mcp.json` in a workspace. The key is `servers`, not `mcpServers`:

```json
{
  "servers": {
    "craftwire": { "type": "stdio", "command": "npx", "args": ["-y", "craftwire"] }
  }
}
```

### Claude Desktop

`%APPDATA%\Claude\claude_desktop_config.json` on Windows, `~/Library/Application Support/Claude/claude_desktop_config.json` on macOS. Same `mcpServers` form as Cursor.

### Claude Code

Use the plugin; it brings the hub, the skills and updates:

```
/plugin marketplace add uxplima/craftwire
/plugin install craftwire@uxplima
```

### Anything else

Any client that runs stdio MCP servers works: command `npx`, arguments `-y craftwire`. Clients that only take remote (HTTPS) MCP servers, such as ChatGPT, cannot reach the hub: it listens on 127.0.0.1 only, by design.
