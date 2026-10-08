# M8 — Video recording and camera motion

Status: approved by the user on 2026-10-08 ("start, work until it is done, all decisions are yours"). Decisions
taken with the user in chat (2026-10-07): live gameplay and camera moves together; MP4 through the user's ffmpeg,
no fallback format; real-time recording (a frame-exact "render mode" is a later release); game audio, switchable;
quality must be adjustable, from "best quality" to "fast, clean, light". Everything else is delegated and recorded
here. Nothing is released or published to a registry without asking.

Purpose: promo and showcase videos of plugins and mods, made by an AI without a person at the keyboard. The video
is for people, not for the model (a model sees frames, not video; `screenshot` covers that).

Workstreams, in build order:

1. Camera motion: `camera` actions `path` and `orbit`
2. Video: frame capture, pacing, ffmpeg encoding (`record` tool)
3. Audio: OpenAL loopback capture, muxed into the MP4
4. Hub tool, docs, skill, CI

## 1. Camera motion

`CameraOverride` gains a time-based motion next to the fixed pose. `get()` returns the motion's pose at
`System.nanoTime() - start` when a motion plays, so the pose is evaluated every rendered frame from real time: a
slow frame never makes the camera stutter, it only skips ahead. `set` / `clear` / `freecam_*` / `reset` end a
motion. When a motion ends the camera holds its last pose (it stays overridden until `reset`).

New `camera` actions (both return the usual camera description plus `motion: {kind, durationMs}`):

- `path`: `keyframes: [{t, x, y, z, yaw?, pitch?}]`, `t` in ms from the start, strictly increasing, the first
  `t` is the start (shifted to 0); 2–64 keyframes. `interpolation: "smooth"` (default; uniform Catmull-Rom per
  component, end points duplicated) | `"linear"`. `ease: "inOut"` (default; smoothstep time warp over the whole
  path, so the camera starts and stops gently) | `"none"`. `lookAt: {x, y, z}` (optional): the camera aims at that
  point from wherever it is; then keyframe yaw/pitch are optional, otherwise required. Yaw is unwrapped between
  keyframes so 170° → -170° turns 20°, not 340°. Pitch is clamped to [-90, 90].
- `orbit`: `center {x,y,z}`, `radius` (> 0), `height` (camera height above the center, default `radius / 2`),
  `startAngle` (degrees; default: where the camera is now, seen from the center), `degrees` (default 360;
  negative turns the other way), `durationMs`, `ease` (default `"none"`, a constant speed). The camera always
  looks at the center. Angle θ puts the camera at `center + (-sin θ · r, height, cos θ · r)`, the same yaw
  convention as the game (0 = south, +Z).

FOV is not animated (the option is an integer, it would step visibly). Ruling recorded in the ledger.

The math is pure Java (`CameraPath`, `CameraOrbit`, no Minecraft types) and unit tested.

## 2. Video

### Tool

`record` (client): `action: "start" | "stop" | "status"`.

`start`:

| param | default | meaning |
|---|---|---|
| `savePath` | required | MP4 file; relative to the hub's working directory (like `screenshot`); directories are created |
| `preset` | `"high"` | `max`, `high`, `balanced`, `light` (table below) |
| `fps` | preset | 10–120 |
| `resolution` | preset | `source`, `2160p`, `1440p`, `1080p`, `720p`, `480p`; only scales down, keeps the aspect ratio |
| `codec` | `"h264"` | `h264` (plays everywhere) or `h265` (about 40% smaller, slower to encode, not every player/preview plays it) |
| `crf` | preset | 0–51, overrides the preset's quality (lower = better, bigger) |
| `speed` | preset | encoder preset `ultrafast` … `veryslow`, overrides the preset's speed |
| `audio` | `true` | record the game's sound |
| `audioBitrate` | preset | AAC kbit/s, 64–320 |
| `hud` | `false` | `false` hides the HUD like F1; open screens (menus) are still drawn |
| `chat` | `true` | `false` leaves chat lines out |
| `durationMs` | — | stop by itself after this long; defaults to the camera motion's length when `camera` is given |
| `maxSeconds` | 120 | safety stop, at most 600; a forgotten recording must not fill the disk |
| `camera` | — | a `path` or `orbit` (same fields as the camera tool, plus `action`) that starts on the first frame |
| `waitForTerrain` | `true` | before the first frame, wait (≤ 5 s) until the visible terrain is built, after placing the camera |
| `wait` | `false` | return only when the video is finished (needs `durationMs` or `camera`), with the `stop` result |

Presets:

| preset | crf | speed | fps | resolution | audio | use |
|---|---|---|---|---|---|---|
| `max` | 16 | `slow` | 60 | source | 256k | final promo cut, editing; needs a strong CPU in real time |
| `high` | 20 | `medium` | 30 | source | 192k | good default |
| `balanced` | 23 | `veryfast` | 30 | source | 160k | quick, small, light on the CPU; also the choice for 60 fps on weaker machines |
| `light` | 28 | `veryfast` | 30 | 720p | 96k | small files for Discord and chat |

`stop` returns `{savedPath, reason, durationMs, fps, width, height, frames, duplicatedFrames, droppedFrames,
audio, codec, preset, crf, speed, sizeBytes, encodeMs}`. `reason`: `requested`, `duration`, `maxSeconds`,
`resized`, `quit`. `status` returns `{recording, elapsedMs, frames, duplicatedFrames, droppedFrames,
queuedFrames, savePath}` and, after a recording finished by itself, `last` (its stop result) — so `stop` after an
automatic stop returns that result instead of `NOT_RECORDING`.

Errors: `FFMPEG_NOT_FOUND` (hint: the install command for the OS), `ALREADY_RECORDING`, `NOT_RECORDING`,
`RECORD_FAILED` (ffmpeg's exit code and last stderr lines), `AUDIO_UNAVAILABLE` (hint: `audio:false`),
`INVALID_PARAMS`, `NOT_IN_WORLD` is not raised: menus and the title screen can be recorded too.

### Capture

- Hook: per version (`compat/<mc>` mixin on `Minecraft.renderFrame`, right after `GameRenderer.render`, before the
  frame is shown): the main render target then holds the finished frame, GUI included. The `GameRenderer.render`
  descriptor differs between 26.2 and 26.3, hence per-version mixins calling one common `Recorder.onFrame()`.
- Readback: `ClientCompat.readPixels(target, consumer)` per version (26.3 moved the GPU API to
  `com.mojang.renderpearl.api`): `copyTextureToBuffer` into a pooled `MAP_READ | COPY_DST` buffer, the callback
  runs on the render thread a frame or two later with the RGBA rows, bottom-up. Nothing waits on the GPU.
- The callback copies the pixels into a pooled `byte[]` and queues it for the writer thread. The queue holds at
  most 256 MB of frames (≥ 2). When it is full (ffmpeg cannot keep up), the frame is dropped and counted; the
  writer repeats the previous frame in its place, so the timing of the video stays right.
- Pacing (`FramePacer`, pure): the video clock is the wall clock from the first frame. A rendered frame at time
  `t` covers the output frames `k` with `k / fps ≤ t` not yet emitted: none (the game renders faster than `fps`:
  no readback at all), one, or several (the game was slow: the frame is repeated, counted as duplicated). On stop
  the last frame is repeated up to `round(T · fps)` frames. A 10 s recording is a 10 s video.
- The window size is read on the first frame. A different size later stops the recording (`reason:
  "resized"`), keeping what was recorded.

### Encoding

- ffmpeg is found in this order: `CRAFTWIRE_FFMPEG`, `PATH`, then the usual install places the game's environment
  may not have on its `PATH` (Windows: `%LOCALAPPDATA%\Microsoft\WinGet\Links`, WinGet `Gyan.FFmpeg*` packages;
  macOS: `/opt/homebrew/bin`, `/usr/local/bin`). Found = `ffmpeg -version` runs. Not found → `FFMPEG_NOT_FOUND`
  with `winget install Gyan.FFmpeg` / `brew install ffmpeg` / `sudo apt install ffmpeg`.
- Video: `-f rawvideo -pix_fmt rgba -s WxH -framerate fps -i -` → `-vf vflip[,scale=w:h:flags=lanczos]`,
  libx264 (`-preset`, `-crf`) or libx265 (`-tag:v hvc1`), `-pix_fmt yuv420p`, `-movflags +faststart`. Output
  width and height are rounded down to even numbers (yuv420p needs that).
- Without audio ffmpeg writes a temp file next to `savePath`, moved into place at the end. With audio the video goes
  to a temp file, the sound to a temp WAV, and a second ffmpeg run muxes them (`-c:v copy -c:a aac`). Temp files
  are removed on success and on failure.
- The game quitting during a recording finishes the file (`reason: "quit"`, at most 15 s of waiting for ffmpeg).

### What changes in the game while recording

HUD/chat hidden as asked, the Craftwire indicator hidden (like during a screenshot), restored at the end.

## 3. Audio

OpenAL Soft's loopback device (`ALC_SOFT_loopback`), which Minecraft's bundled OpenAL Soft supports:

- Start: `Library.openDeviceOrFallback` returns a loopback device while the tap is armed (mixin), and
  `Library.createAttributes` adds the loopback format (stereo, 16-bit, 48 kHz) to the context attributes; then
  `SoundEngine.reload()` reopens the sound system on it. Sounds that were playing restart; music may restart.
- A `Craftwire audio` thread renders 10 ms blocks (`alcRenderSamplesSOFT`) paced by the same wall clock as the
  video, into a WAV file. This is exactly what a real audio backend thread does, so it is safe next to the
  game's AL calls.
- While the loopback device is open nothing reaches the speakers: a hidden test client stays silent, and a
  visible game is muted while it records (noted in the docs).
- `SoundEngine.shouldChangeDevice` answers false while recording, so a device list change cannot switch the game
  back to a speaker mid-recording.
- A master volume of 0 (clients started by `client_process` and the game tests are muted) would record silence:
  it is raised to 100% for the recording and restored after. Other volume sliders are left as they are.
- Stop: the audio thread stops, the tap is disarmed, `SoundEngine.reload()` returns to the real device.
- Both 26.2 and 26.3 use the same `com.mojang.blaze3d.audio.Library` (checked with javap), so the audio mixins are
  common code.

## 4. Hub, docs, skill, CI

- Hub `record` tool (zod schema mirrors the table; `savePath` resolved to absolute; timeout: 30 s, or
  `durationMs + 120 s` with `wait`). Camera tool schema gets `path` and `orbit`.
- `docs/video.md`; README tool list; `minecraft-promo-shots` skill gets a video section (recipes: orbit a build,
  fly-through path, record a GUI, presets for Discord vs. editing).
- CI: the client game test job installs ffmpeg (`apt-get install ffmpeg`).
- Version 0.8.0.

## Testing

- Unit (agent-fabric `src/test`): `CameraPathTest` (keyframes hit exactly, smooth vs linear midpoint, ease
  endpoints, yaw unwrapping, lookAt, validation errors), `CameraOrbitTest` (start angle, quarter turn position,
  always faces the center, negative degrees), `FramePacerTest` (faster game → skips, slower → duplicates, final
  padding, exact frame count), `EncoderArgsTest` (presets, overrides, even sizes, scale only down, h265 args,
  mux args), `FfmpegLocatorTest` (env var wins, missing → error with OS hint), `RecordParamsTest` (validation,
  defaults from camera duration, `wait` without a duration refused).
- Game tests (26.2 and 26.3, real game, real ffmpeg): record 2 s with audio, then ffprobe the file: H.264,
  the window size, ~2 s, ~30 fps, one AAC stream; `light` preset → 720p-or-smaller and smaller than `high`;
  camera `orbit` moves the camera (pose differs at two times and faces the center); `record start` twice →
  `ALREADY_RECORDING`; `stop` with nothing recording → `NOT_RECORDING`; `wait:true` returns the finished result.
- Hub: tool schema tests; e2e smoke where the e2e suite already drives a client.
