---
name: craftwire
description: Use when driving Minecraft through the craftwire MCP tools (screenshot, camera, gui_read, gui_action, input, chat, hud_read, player_state, wait_for) — covers the reliable order of calls, menu automation and recovering from errors.
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

## Errors
Every error has `code`, `message`, `hint` — follow the hint. `PAUSED_BY_USER` means the human pressed F8: stop and ask them. Use `operationId` on actions you may retry (clicks, commands) so a retry never runs twice.
