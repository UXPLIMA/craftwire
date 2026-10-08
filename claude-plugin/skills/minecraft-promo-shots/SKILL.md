---
name: minecraft-promo-shots
description: Use when producing marketing/promo screenshots or videos of a Minecraft plugin or mod with craftwire (forum covers, store pages, showcase images, trailers, feature clips) — shot planning, world setup, camera framing, HUD and menu captures, progress series, camera moves and video presets.
---

# Promo shots and videos with Craftwire

## Prepare the scene (server commands via chat)
- `/time set noon`, `/weather clear`, `/gamerule doDaylightCycle false` (restore after).
- Clear clutter from the hotbar if it will be visible; `client_settings {fov:70, guiScale:3}`.

## Shot types
1. **Hero world shot** — `camera {action:"frame_area", area:{min,max}, pitch:25}` around the subject; `screenshot {hud:false, savePath:"shots/hero.png"}`. Try 2–3 yaw values (e.g. 35, 135, 225) and keep the best.
2. **Progress series** (something being built/changed) — set the camera once with `camera set`, then repeatedly: `wait_for` (chat/hud pattern for the milestone) → `screenshot {hud:false}`. Never move the camera between frames.
3. **Menu with tooltip** — open the menu, `gui_action hover` on the most impressive item, `screenshot {hud:true, chat:false}` (menus need the HUD layer; chat:false keeps command feedback out). Crop later; the GUI is centred.
4. **HUD feature** (bossbar/actionbar) — `screenshot {hud:true, chat:false}` while it is shown; confirm with `hud_read` first.

## Quality checklist
- Look at every returned image before moving on; retake if a chat message, crosshair or half-loaded chunk is visible.
- Save full-resolution files with descriptive names (`shots/b-02a-25pct.png`).
- `camera reset` and restore any gamerules you changed.

## Videos (`record`, see craftwire://docs/video)
Needs ffmpeg on the game's computer (`FFMPEG_NOT_FOUND` gives the install command; tell the user, do not install it yourself).

1. **Size the window first**: the video has the window's resolution. `client_settings {windowSize:{width:1920, height:1080}}`, or a hidden client started with `width`/`height`.
2. **Pick the preset for where it goes**: `max` for an editor or the final trailer (60 fps, needs a strong CPU), `high` by default, `balanced` for 60 fps on an average PC, `light` for Discord/chat (720p, small). Override single values (`fps`, `crf`, `resolution`, `codec`) only when asked.
3. **Plan the camera before recording**: look at the frame with `screenshot {camera:{...}}` at the start and end poses of a move.
   - Showcase a build: `camera:{action:"orbit", center, radius, height, durationMs:10000-15000}` — slow is better; one turn of a 30-block build in 12 s reads well.
   - Fly-through: `camera:{action:"path", lookAt:<subject>, keyframes:[3-5 poses]}`, about 3-4 s between keyframes, `ease:"inOut"` (default).
   - Gameplay or a menu tour: no camera move; record from the player's view (`hud:true, chat:false` when the HUD matters), drive the actions (bots, `gui_action`) while it records, then `record stop`.
4. **One call for a move**: `record {action:"start", savePath:"videos/<feature>.mp4", wait:true, camera:{...}}` returns the finished file. With live actions, start without `wait`, act, then `record stop`.
5. **Check the result**: `durationMs` as planned, `droppedFrames` 0 (else retry with a faster preset), `audio` true when sound matters. Take a `screenshot` at a key moment if you need to see what the video shows; you cannot watch the video.
6. Keep clips short and named by feature (`videos/shop-buy.mp4`, `videos/castle-orbit.mp4`); several short clips edit better than one long take.
