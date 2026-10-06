# M1 acceptance run — uxmBuilders promo shots

**Date:** 2026-10-05 · **Hub:** craftwire 0.1.0 (local `hub/dist/cli.js`) · **Agent:** craftwire-agent-fabric 0.1.0
**Client:** the user's Modrinth "Fabric 26.2" profile (Fabric API 0.161.0, Sodium 0.9.2, Iris 1.11.4 with a shader pack)
**Server:** the user's local Paper 26.2 test server with uxmBuilders 1.0.0, WorldEdit, uxmEssentials

## Result: PASS, with one agent defect worked around

The human only started the game and joined the server. Everything else was done through Craftwire tools.

| Shot | File | How |
|---|---|---|
| b-01 hero with bossbar | `b-01-hero-bossbar.png` | fixed camera, `screenshot {hud:true}` triggered at 85 % from `hud_read` bossbar progress |
| b-02a/b/c/d progress series | `b-02a-25pct.png` … `b-02d-complete.png` | same fixed camera; a loop polled `hud_read` and fired `screenshot {hud:false}` at 25/50/75 % and after the bossbar disappeared |
| b-03 ghost preview | `b-03-preview.png` | `/builders preview plains_house`, spectator viewpoint |
| b-04 crew menu + tooltip | `b-04-crew-tooltip.png` | `/builders crew` → `wait_for screen_open` → `gui_read` → `gui_action hover` (Mason) → `screenshot` |
| b-05 shop menu + tooltip | `b-05-shop.png` | `/builders shop` → `gui_action hover` (Oak Cottage) → `screenshot` |

All shots were captured at 3840×2054 (window 1920×1080 at 200 % display scaling) and saved with `savePath`.

### Scene setup, also done through tools
- `client_settings {windowSize:1920×1080}`.
- Flattened two areas with `/fill`. Placed `minecraft:village/plains/houses/plains_medium_house_1` with `/place template`.
- Package created entirely in game: `//pos1`, `//pos2`, `//replace minecraft:jigsaw minecraft:air`, `/builders admin save plains_house`, `/builders admin package create plains_house plains_house.schem 2500`, `/builders admin package edit plains_house display <gold>Oak Cottage</gold>`.
- Blueprint given with `/builders admin give`. The build started by aiming with `minecraft:tp … yaw pitch` and `input {keys:["use"]}`. The plugin answered "Construction started! … Total blocks: 527".
- Time and weather frozen with `gamerule advance_time/advance_weather false` and restored afterwards.

No M2 server tool was strictly required. Player commands through `chat` were enough.

## Defects and follow-ups

1. **Camera override renders wrong under Sodium (must fix).** With `camera set/frame_area`, Sodium still culls chunk sections from the player's position. Terrain appeared cut away and the subject was missing.
   - Workaround used: `gamemode spectator` plus `minecraft:tp x y z yaw pitch` as the camera.
   - Fix options: Sodium compatibility (feed the override position to its render section manager), or have the `camera` tool fall back to moving a spectator player when Sodium is present.
   - **Fixed in 0.4.1.** Vanilla had the same fault; the gametests checked only the camera position, not the picture. `Camera.update()` builds its culling frustum from the pose right after `alignWithEntity`, and vanilla and Sodium both cull terrain with that frustum. The override was applied at the end of `update()`, so culling still followed the player. It is now applied right after `alignWithEntity`. `CameraRenderChecks` checks real frames, and CI runs it with and without Sodium 0.9.2.
2. **Chat lines in `hud:true` shots.** Command feedback ("Teleported …") shows up in HUD shots. Add `client_settings {chatVisible}` or a `screenshot {chat:false}` option.
3. **Skill notes.**
   - The `chat` tool strips one leading `/`, so WorldEdit commands must be sent as `//pos1`.
   - Server plugins often override `/tp`; use `minecraft:tp`.
   - Gamerules are snake_case in 26.x (`advance_time`).
   - Add these to the `craftwire` and `minecraft-promo-shots` skills.
4. **Large saves.** A full-resolution 4K PNG is about 17 MB. Consider an optional `saveFormat`/quality setting.
5. **Not Craftwire (server setup):** Builders shows "Balance: 0 $" after `eco give`. The uxmEssentials economy is not the Vault provider that Builders reads. Fix this on the server before the final marketing shots.
6. **Framing:** the house fills only about 15 % of the series frames. The 4K sources leave room to crop. For the final set, place the camera about 30 % closer.
