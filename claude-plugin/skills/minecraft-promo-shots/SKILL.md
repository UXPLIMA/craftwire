---
name: minecraft-promo-shots
description: Use when producing marketing/promo screenshots of a Minecraft plugin or mod with craftwire (forum covers, store pages, showcase images) — shot planning, world setup, camera framing, HUD and menu captures, progress series.
---

# Promo shots with Craftwire

## Prepare the scene (server commands via chat)
- `/time set noon`, `/weather clear`, `/gamerule doDaylightCycle false` (restore after).
- Clear clutter from the hotbar if it will be visible; `client_settings {fov:70, guiScale:3}`.

## Shot types
1. **Hero world shot** — `camera {action:"frame_area", area:{min,max}, pitch:25}` around the subject; `screenshot {hud:false, savePath:"shots/hero.png"}`. Try 2–3 yaw values (e.g. 35, 135, 225) and keep the best.
2. **Progress series** (something being built/changed) — set the camera once with `camera set`, then repeatedly: `wait_for` (chat/hud pattern for the milestone) → `screenshot {hud:false}`. Never move the camera between frames.
3. **Menu with tooltip** — open the menu, `gui_action hover` on the most impressive item, `screenshot {hud:true}` (menus need the HUD layer). Crop later; the GUI is centred.
4. **HUD feature** (bossbar/actionbar) — `screenshot {hud:true}` while it is shown; confirm with `hud_read` first.

## Quality checklist
- Look at every returned image before moving on; retake if a chat message, crosshair or half-loaded chunk is visible.
- Save full-resolution files with descriptive names (`shots/b-02a-25pct.png`).
- `camera reset` and restore any gamerules you changed.
