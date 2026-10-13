# GUI capture with a transparent background — design

Date: 2026-10-13 · Part of 0.10.0 (branch `feat/m10-build`) · Approved in chat 2026-10-13

## Why

Content creators show their plugin's menus in videos and on store pages: a chest GUI, an item's lore tooltip, often
placed as floating panels over gameplay footage. Today they record the whole screen and cut the GUI out by hand; the
tooltip's background is translucent, so a clean cut is close to impossible, and a video of it is worse. Craftwire
should hand them the GUI (and/or the tooltip) on a transparent background, as an image or as a video, ready to drop
into an editor.

## What the user gets

- `screenshot` and `record` take `layer`:
  - `"world"` (default): what they do today.
  - `"gui"`: the open screen only (the container, its slots and items, titles, buttons), no world, no menu
    darkening or blur behind it.
  - `"tooltip"`: only the tooltip (item name and lore) being shown.
  - `"gui+tooltip"`: both, as the player sees them, minus the world.
- `split: true` (with `"gui+tooltip"`) writes the GUI and the tooltip as two files from the same frame(s):
  `<name>.gui.<ext>` and `<name>.tooltip.<ext>`, so they can be placed as separate panels.
- `guiScale` (1-8) applies a GUI scale only for the capture (restored afterwards). With a large window
  (`client_process` `windowSize` 3840x2160) this gives crisp, large panels.
- The player's own screen does not change during a capture or a recording; the layer is drawn to a separate canvas.

### Images

PNG with alpha. Transparent borders are trimmed to the drawn content plus 8 px (`trim: false` keeps the full frame).
The image returned to the AI client is the same picture, scaled to `maxSize` as today. `.jpg` is refused for a
transparent layer (`INVALID_PARAMS`, hint: use `.png`).

### Videos

The `savePath` extension picks the format:

| extension | format | for |
|---|---|---|
| `.mov` | ProRes 4444 with alpha (`prores_ks`, `yuva444p10le`) | Premiere, DaVinci Resolve, After Effects, Final Cut |
| `.webm` | VP9 with alpha (`libvpx-vp9`, `yuva420p`) | web pages, OBS, small files |
| `.mp4` | refused for a transparent layer (`INVALID_PARAMS`, hint: `.mov` or `.webm`) | — |

Video frames keep the full window size (no trim): the tooltip follows the mouse, and a fixed crop would cut it off.
Sound is recorded and muxed as for world recordings (`aac` in `.mov`, `opus` in `.webm`). Presets keep their frame
rate; `crf`/`speed` do not apply to ProRes, and the VP9 encoder uses `crf` with `-b:v 0`. Camera motions
(`camera` orbit/path) are refused with a transparent layer, as there is no world to move through.

## How it works

### The transparent canvas (client)

Every frame in which a capture or a recording of a transparent layer is active, after the game has drawn its normal
frame, the GUI is drawn once more into an offscreen render target of the window's size:

1. Clear the target to `(0,0,0,0)`.
2. Draw the GUI render state of this frame (the strata the layer asks for: screen, tooltip, or both) into it. The
   screen's background pass (menu darkening and blur) is skipped for this draw only.
3. Read the target back (RGBA). Vanilla GUI pipelines blend colour with `SRC_ALPHA, ONE_MINUS_SRC_ALPHA` and alpha with
   `ONE, ONE_MINUS_SRC_ALPHA`, so the result holds premultiplied colour with correct coverage; colour is divided by
   alpha on readback (straight alpha, which PNG, ProRes and VP9 expect).

`split` draws twice into two targets (screen strata, tooltip strata).

**Fallback (if a GUI pipeline in 26.2 or 26.3 does not blend alpha as above):** difference matting. Draw the layer over
opaque black and over opaque white; `alpha = 1 - (white - black)`, `colour = black / alpha`. Exact for any blending,
at twice the draw cost. The first implementation task is a spike that settles which of the two is used; the
difference matte is kept either way as the reference in the tests.

Version-specific code (GUI renderer access, the render target, readback) lives in the existing compat projects
(`agent-fabric/compat/26.2`, `26.3`), next to `FrameReader`.

### Encoding

`FfmpegArgs` gets the two alpha formats; frames are already sent to ffmpeg as raw RGBA, bottom-up. PNG writing uses
`ImageIO` with an ARGB image.

### Hub

`screenshot` and `record` schemas gain `layer`, `split`, `guiScale`, `trim`, validated in the hub before anything
reaches the client. Descriptions stay short (the enum names say most of it); the details go to a new doc topic
`craftwire://docs/gui-capture` (`docs/gui-capture.md`). Budgets from M10 still hold: the whole tool list at most
28,000 characters, `--profile build` at most 10,500, any one tool at most 1,800. If the new arguments push a budget
over, other descriptions are shortened in the same change.

### Errors

| code | when |
|---|---|
| `NO_SCREEN` | `layer` is `gui` or `gui+tooltip` and no screen is open (hint: open one with `server_command`/`gui_action`, or the plugin's command). |
| `NO_TOOLTIP` | `layer` is `tooltip` (or `split`) and no tooltip is shown (hint: `gui_action {action:"hover", slot}` first). For a recording this is checked only at the start when `layer` is `tooltip`; a tooltip that comes and goes during a recording is simply drawn when it is there. |
| `INVALID_PARAMS` | `.jpg`/`.mp4` with a transparent layer, `split` without `gui+tooltip`, `camera` motion with a transparent layer, `guiScale` outside 1-8. |
| `FFMPEG_FAILED` | the user's ffmpeg lacks `prores_ks` or `libvpx-vp9` (message names the encoder; hint: the other format, or a full ffmpeg build). |

## Docs and skill

- `docs/gui-capture.md`: the layers, formats, which editor takes which file, a hover-then-capture example, the
  4K + `guiScale` recipe.
- The `minecraft-promo-shots` skill: a section on GUI and lore panels.
- README: one short item under "Record promo videos" with an example image (a menu and its lore on transparency,
  shown over a checkerboard).

## Testing

- Client game tests (26.2 and 26.3, with and without Sodium, as the existing capture tests):
  - `gui` layer of a chest screen: pixels outside the container have alpha 0; slot borders are opaque; nothing of the
    world shows (a red block placed behind the screen never appears).
  - `tooltip` layer while an item is hovered: the tooltip background is translucent (alpha strictly between 0 and
    255), its text opaque; everything else alpha 0.
  - The transparent-canvas result matches the difference matte within a small tolerance (both are produced in the
    test).
  - The player's frame is unchanged by a capture (the main render target still shows the world behind the screen).
  - `split` writes two files; `trim` crops to content plus 8 px; `guiScale` is restored afterwards.
- Video: a short `.mov` and `.webm` recording of the `gui` layer; `ffprobe` reports `yuva444p10le` / alpha for VP9, and
  a decoded frame has alpha 0 in a corner and 255 on a slot border. `.mp4` with a transparent layer fails with
  `INVALID_PARAMS`.
- Hub: schema validation and error mapping; the tool budget tests.
- Acceptance: on a throwaway server with a plugin menu (the test fixture's menu, with lore), capture `gui+tooltip`
  with `split`, record 5 s of hovering across items to `.mov` and `.webm`, open the results over a checkerboard and in
  a video player that shows alpha; check by eye that edges, text shadows and the translucent tooltip look right.

## Out of scope

- Drawing a GUI that is not open (rendering a menu from data without the screen).
- 3D placement or perspective of the panels (that is the editor's job).
- HUD-only layers (hotbar, scoreboard); they can follow later through the same canvas.
