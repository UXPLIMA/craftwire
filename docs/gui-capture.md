# GUI and lore on a transparent background

Store pages and promo videos often show a plugin's menu, or an item's lore tooltip, as a panel floating over
gameplay. Cutting those out of a normal screenshot is hard (the tooltip's background is see-through), and cutting
them out of a video is worse. `screenshot` and `record` can give you the menu and/or the tooltip on a transparent
background instead, ready to drop into an editor.

The player's own screen does not change: the layer is drawn a second time onto a separate, transparent canvas.

## Layers

| `layer` | what is in the picture |
|---|---|
| `world` (default) | the normal game frame |
| `gui` | the open screen only: the container, its slots and items, titles and buttons. No world, no HUD, no darkening or blur behind the menu. |
| `tooltip` | only the tooltip (item name and lore) being shown |
| `gui+tooltip` | both, as the player sees them, minus the world |

`split: true` (with `gui+tooltip`) writes the menu and the tooltip as two files from the same frame(s):
`<name>.gui.<ext>` and `<name>.tooltip.<ext>`, so you can place them as separate panels.

`guiScale` (1-8) sets the GUI scale for this capture only and puts the user's setting back afterwards. The mouse
stays over the item it was hovering, so its tooltip stays shown. A window too small for the scale gets the largest
scale that fits.

## Images

```jsonc
gui_action {action:"hover", slot:13}          // show the item's lore
screenshot {layer:"gui+tooltip", split:true, guiScale:4, savePath:"shots/kit.png"}
```

The PNGs keep their alpha channel. Transparent borders are trimmed to the content plus 8 px; `trim:false` keeps the
full window size (useful when several shots must line up). `.jpg` has no transparency and is refused.

## Videos

```jsonc
record {action:"start", layer:"gui+tooltip", savePath:"videos/kit.mov", durationMs:8000, wait:true}
```

Move the hover with `gui_action` while it records and the tooltip follows. The `savePath` extension picks the format:

| extension | format | open it in |
|---|---|---|
| `.mov` | ProRes 4444 with alpha | Premiere Pro, DaVinci Resolve, After Effects, Final Cut Pro |
| `.webm` | VP9 with alpha | web pages, OBS, small files |
| `.mp4` | refused: MP4 has no transparency | — |

Videos keep the full window size (the tooltip moves with the mouse, so a fixed crop would cut it off). Sound is
recorded as for normal videos (AAC in `.mov`, Opus in `.webm`); pass `audio:false` for a silent panel. Camera motions
do not apply: there is no world in a GUI layer. If your ffmpeg lacks the encoder (`prores_ks` or `libvpx-vp9`), the
recording is refused before it starts with `FFMPEG_FAILED` naming it; the other format, or a full ffmpeg build,
works.

## Crisp, large panels

Start a hidden 4K client and capture at a large GUI scale:

```jsonc
client_process {action:"start", windowSize:{width:3840, height:2160}}
screenshot {layer:"gui", guiScale:6, savePath:"shots/menu.png"}
```

## Errors

| code | when |
|---|---|
| `NO_SCREEN` | `gui` or `gui+tooltip` and no screen is open: open the menu first. |
| `NO_TOOLTIP` | `tooltip` (or `split`) and no tooltip is shown: hover an item first (`gui_action {action:"hover", slot}`). A recording checks this only at the start for `layer:"tooltip"`; a tooltip that comes and goes during a recording is drawn while it is there. |
| `INVALID_PARAMS` | `.jpg`/`.mp4` with a transparent layer, `split` without `gui+tooltip`, `camera` with a transparent layer, `guiScale` outside 1-8. |
| `FFMPEG_FAILED` | the user's ffmpeg has no `prores_ks` (`.mov`) or `libvpx-vp9` (`.webm`). |
