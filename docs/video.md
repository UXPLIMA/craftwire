# Videos

`record` films the game client: the frame the player sees (or a moving camera, without moving the player) and the
game's sound, encoded to MP4 by ffmpeg on the same computer. It is made for promo and showcase videos of plugins and
mods. The model itself cannot watch a video; use `screenshot` for what the AI needs to see.

## What you need

ffmpeg, installed once:

| System | Install |
|---|---|
| Windows | `winget install Gyan.FFmpeg` |
| macOS | `brew install ffmpeg` |
| Linux | `sudo apt install ffmpeg` (or your distribution's package) |

Craftwire looks for it on `PATH` and in the usual install folders, so a game started before the install still finds
it. To use a specific build, set `CRAFTWIRE_FFMPEG` to the executable before starting the game. Without ffmpeg,
`record` answers `FFMPEG_NOT_FOUND` with the install command for your system.

## Quick start

A 10-second clip, stopped by hand:

```jsonc
record {action:"start", savePath:"videos/shop.mp4"}
// ... open the menu, click around, let the bots play ...
record {action:"stop"}
```

A turn around a build, returned when it is done:

```jsonc
record {action:"start", savePath:"videos/castle.mp4", wait:true,
        camera:{action:"orbit", center:{x:120, y:70, z:-40}, radius:30, height:12, durationMs:12000}}
```

`stop` (and a `wait:true` start) returns the result:

```jsonc
{ "savedPath": "C:\\work\\videos\\castle.mp4", "reason": "duration", "durationMs": 12000, "fps": 30,
  "width": 1920, "height": 1080, "frames": 360, "duplicatedFrames": 0, "droppedFrames": 0,
  "audio": true, "codec": "h264", "preset": "high", "crf": 20, "speed": "medium",
  "sizeBytes": 18734211, "encodeMs": 640 }
```

## Quality: presets and settings

Pick a preset for the job, and override any single value.

| preset | quality (crf) | encoder speed | fps | resolution | sound | for |
|---|---|---|---|---|---|---|
| `max` | 16 | `slow` | 60 | as the window | 256 kbit/s | the final cut, editing; needs a strong CPU |
| `high` (default) | 20 | `medium` | 30 | as the window | 192 kbit/s | a good default |
| `balanced` | 23 | `veryfast` | 30 | as the window | 160 kbit/s | quick and light on the CPU; 60 fps on weaker PCs |
| `light` | 28 | `veryfast` | 30 | 720p | 96 kbit/s | small files for Discord and chat |

| setting | values | meaning |
|---|---|---|
| `fps` | 10–120 | frames per second |
| `resolution` | `source`, `2160p`, `1440p`, `1080p`, `720p`, `480p` | output height; only scales down, keeps the aspect ratio |
| `codec` | `h264`, `h265` | H.264 plays everywhere; H.265 is about 40% smaller but slower to encode, and some players and previews do not play it |
| `crf` | 0–51 | quality; lower is better and bigger (18 looks lossless to most people, 28 is small) |
| `speed` | `ultrafast` … `veryslow` | encoder effort; slower gives a smaller file at the same quality, but needs more CPU |
| `audio` | `true` / `false` | record the game's sound |
| `audioBitrate` | 64–320 | AAC kbit/s |

The resolution comes from the game window. For a 1080p or 4K video, size the window first:
`client_settings {windowSize:{width:1920, height:1080}}`, or start a hidden client with
`client_process {action:"start", width:3840, height:2160}`.

Recording is real time: each frame is encoded while the game runs. If the encoder cannot keep up, frames repeat the
one before (`droppedFrames` in the result, with a note) so the video keeps its real length. Then use a faster preset,
a lower fps or resolution. If the game renders fewer frames than `fps`, frames repeat as well
(`duplicatedFrames`); the camera still moves smoothly, it is placed from the clock every frame.

## What is in the picture

- `hud:false` (default) hides the HUD like F1: hotbar, crosshair, chat. Open menus are still drawn, so a menu
  tour records as it looks. `hud:true` keeps the HUD; add `chat:false` to keep chat lines out.
- The Craftwire indicator is never in the video.
- Before the first frame the recorder waits (up to 5 s) until the terrain in view is built, after placing the
  camera. `waitForTerrain:false` starts at once.

## Camera moves

The same moves work on their own (`camera` tool) and inside `record start` (`camera` field), where they start with
the first frame and set the video's length unless `durationMs` is given. The player does not move; keep the camera
within render distance of the player, or the far chunks are not drawn.

**orbit**: circles a point, always facing it.

| field | default | |
|---|---|---|
| `center` | required | `{x,y,z}` |
| `radius` | required | blocks |
| `height` | `radius / 2` | camera height above the center |
| `startAngle` | where the camera is now | degrees; 0 is south of the center, 90 west |
| `degrees` | 360 | how far to turn; negative turns the other way |
| `durationMs` | required | |
| `ease` | `none` | `inOut` starts and stops gently |

**path**: flies through keyframes.

```jsonc
camera {action:"path", lookAt:{x:120, y:68, z:-40}, keyframes:[
  {t:0,    x:80,  y:90, z:-80},
  {t:4000, x:150, y:80, z:-70},
  {t:9000, x:160, y:75, z:0}
]}
```

| field | default | |
|---|---|---|
| `keyframes` | required | 2–64 of `{t, x, y, z, yaw, pitch}`; `t` in ms, increasing; yaw/pitch optional with `lookAt` |
| `interpolation` | `smooth` | `smooth` curves through the keyframes, `linear` goes straight |
| `ease` | `inOut` | starts and stops gently (keyframe times then shift a little); `none` keeps every `t` exact |
| `lookAt` | — | `{x,y,z}` the camera keeps aiming at |

A move holds its last pose when it ends. `camera set` or `camera reset` stops it. After a recording with a `camera`
move the camera goes back to what it was before.

## Sound

The game's own mix is recorded: blocks, mobs, menus, music, as the player would hear it at the camera.

- While recording with sound the game is silent on the speakers: the sound engine plays into the recording
  instead. Sounds that were playing when the recording started restart; music may restart.
- A muted game (master volume 0, as hidden `client_process` clients are) is turned up to 100% for the recording,
  heard by nobody, and set back afterwards.
- `audio:false` leaves the speakers alone.

## Ending a recording

| `reason` | when |
|---|---|
| `requested` | `record stop` |
| `duration` | `durationMs`, or the camera move's length, has passed |
| `maxSeconds` | the safety limit (default 120 s, at most 600 s) |
| `resized` | the window changed size; what was recorded is kept |
| `quit` | the game closed; the file is finished first (up to 15 s) |

`record status` shows a running recording, or the last result. `stop` after a recording ended by itself returns that
result.

## Errors

| code | meaning |
|---|---|
| `FFMPEG_NOT_FOUND` | install ffmpeg, or set `CRAFTWIRE_FFMPEG` |
| `ALREADY_RECORDING` | one recording at a time; the previous one may still be finishing |
| `NOT_RECORDING` | nothing to stop |
| `RECORD_STARTING` | the recording is waiting for terrain; stop again in a moment |
| `AUDIO_UNAVAILABLE` | the sound could not be routed to the recording; record with `audio:false` |
| `RECORD_FAILED` | ffmpeg failed; the message has its output (for example `h265` with an ffmpeg built without libx265) |

## Limits

- Real time only. A frame-exact "render mode" that slows the game down for perfectly smooth 4K is not there yet.
- The field of view is not animated.
- One client records at a time per game; several clients can each record their own.
