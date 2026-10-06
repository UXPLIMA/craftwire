---
name: craftwire
description: Use when driving Minecraft or a Paper server through the craftwire MCP tools (screenshot, camera, gui_*, input, chat, server_command, server_eval, world_query, world_edit, logs, wait_for, server_process, plugin_deploy, bot_spawn, bot_action) — covers the reliable order of calls, menus, server scripting, safe world edits and recovering from errors.
---

# Driving Minecraft with Craftwire

## Always start here
1. `list_instances` — if empty, the player must start Minecraft with the Craftwire Agent mod (and join a world/server). Never guess an `instance`; pass it only when several are listed.
2. `player_state` — where the player is, what they hold, what they look at.

## Opening and using a menu (plugin GUIs)
1. `chat {action:"command", text:"/builders crew"}`
2. `wait_for {condition:"screen_open", timeoutMs:5000}` — menus open a tick or more later.
3. `gui_read` — slots are numbered; use the `slot` field, not the visual position.
4. `gui_action {action:"hover", slot:N}` then `screenshot` to capture the tooltip.
5. `gui_action {action:"close"}` when done.
If `gui_action` returns `SLOT_OUT_OF_RANGE` or `NO_SCREEN_OPEN`, the screen changed: `gui_read` again.

## Screenshots
- `hud:false` hides the HUD (and the Craftwire indicator); open menus still render.
- `savePath` writes the full-resolution PNG (relative paths are relative to the current project); the image you see is downscaled to `maxSize`.
- For a fixed angle use `camera {action:"set", …}` once, then take several screenshots; `camera {action:"reset"}` afterwards.
- The camera is render-only and should stay within render distance of the player.

## Server (Paper + Craftwire plugin)
- `server_info` first: TPS, plugins, worlds.
- `server_command` returns the console feedback. Raise `collectMs` (max 5000) for plugins that answer late. Use the `minecraft:` prefix when a plugin overrides a vanilla command (e.g. `minecraft:tp`); gamerules are snake_case in 26.x (`advance_time`).
- `server_eval` for anything without a command: `server`, `player(name)`, `plugin(name)`, `loc(x,y,z)`, `Java.type(...)`, `print(...)`. Scripts run on the server thread with a 5 s default timeout, so keep loops small. Keep values on `globalThis`; top-level `let/const` cannot be re-declared on the next run.
- `world_query` before editing. `world_edit` edits over 32768 blocks return a `snapshotId`; `world_edit {action:"restore", id}` undoes them. Take an explicit `snapshot` before any risky change.
- `logs {level:"WARN"}` after (re)enabling a plugin; stack traces arrive folded into one entry.

## Dev loop (local server)
- `server_process {action:"start"|"stop"|"restart"|"status", serverDir}` runs a local Paper server under the hub; `start` returns when `Done (` was printed and the Craftwire plugin connected.
- `plugin_deploy {projectDir}` (or `{jar}`) builds, installs and restarts, then reports `loaded` and `problems`. See the `paper-plugin-dev` skill for the full loop.
- Never accept the EULA for the user. Ask before `takeOver:true` — it stops a server the user started.

## Player commands through `chat`
- `chat {action:"command"}` strips one leading `/`, so WorldEdit commands keep their double slash: send `//pos1`.

## Bots (server-side fake players)
- `bot_spawn {count}` (or `names`), then drive with `bot_action`. Bots are real players to plugins: permissions, join/quit, chat and click events all fire.
- Menus: `bot_action {action:"command", command:"shop"}` → `gui_read` → `gui_click {slot}`; the click result already includes the menu after the plugin reacted. Read replies with `messages`.
- Walking is straight-line (`move_to`): it hops one-block steps, and reports `stuck` at walls. Give waypoints for longer routes, or `server_command "minecraft:tp Bot1 x y z"`.
- Bots skip the whitelist and bans, and leave no player files behind. They are not op: give permissions with the server's permission plugin, or `minecraft:op` if the test needs it.
- Always `bot_remove {all:true}` when done.

## Errors
Every error has `code`, `message`, `hint` — follow the hint. `PAUSED_BY_USER` means the human pressed F8: stop and ask them. Use `operationId` on actions you may retry (clicks, commands) so a retry never runs twice.
