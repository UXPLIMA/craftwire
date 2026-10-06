# client_process: a headless Minecraft client run by the hub

Date: 2026-10-06 · Status: approved design (technical decisions delegated to the implementer by the user)

## Goal

The AI can test the visual side of a plugin or mod without the user opening the game: the hub downloads and starts a Minecraft 26.2 client with the Craftwire agent mod, joins it to a server, and every client tool (`screenshot`, `gui_read`, `gui_action`, `input`, `hud_read`, `player_state`, `camera`, …) then works on it. This is the client twin of `server_process`.

Non-goals: Microsoft account login (offline mode only, added later on demand), copying the user's own launcher profiles, distributing any Mojang file, remote access (separate spec).

## Constraints

- **No third-party runtime projects.** The launcher is our own code in the hub (TypeScript). Files come only from official sources: Mojang (`piston-meta.mojang.com`, `libraries.minecraft.net`, `resources.download.minecraft.net`) and Fabric (`meta.fabricmc.net`, `maven.fabricmc.net`).
- Every downloaded file is verified against the sha1 its source publishes; partial downloads never become cache entries.
- The user's `.minecraft` folder and launcher profiles are never read or written.
- The user's `server.properties` is never changed by this feature.
- Offline mode: the player name is local; the server must run `online-mode=false`.
- Minecraft version, Fabric Loader and Fabric API versions are the ones the agent is built for (`gradle.properties`: `minecraft_version`, `loader_version`, `fabric_api_version`).

## Tool: `client_process`

Actions `start`, `stop`, `status`, mirroring `server_process`.

`start` arguments:

| Argument | Default | Meaning |
|---|---|---|
| `server` | the address of the single running server started by `server_process` | `host:port` to join |
| `username` | `Craftwire` | Offline player name, `^[A-Za-z0-9_]{3,16}$` |
| `mods` | `[]` | Extra mod jars (absolute paths) loaded next to Fabric API and the agent |
| `visible` | `false` | Show the game window |
| `windowSize` | `{width:1280,height:720}` | Window (and screenshot) size |
| `java` | as `server_process`: `JAVA_HOME`, then `java` on PATH; must be ≥ the version JSON's `javaVersion.majorVersion` (25) | Java executable |
| `sounds` | `false` | Also download sound assets (~360 MB) |
| `timeoutMs` | 300 000 | How long `start` waits for "ready" (download time not counted) |

`start` returns `{instance, username, server, pid, gameDir, downloadedBytes, readyMs}` once **ready** = the agent of this client is connected to the hub and the player is in a world. When no `server` is given and no managed server runs, the client starts at the title screen and `start` returns when the agent connects.

`stop {username?}` asks the agent to quit cleanly (new agent method `client.quit`, which calls `Minecraft.stop()`), then kills the process after 10 s. `status` lists managed clients with state (`downloading` with bytes done/total, `starting`, `running`, `stopped`, `crashed`), pid and the log tail. All managed clients stop when the hub exits.

Several clients may run at once, one per `username`; a second `start` with a running username returns `ALREADY_RUNNING`.

## Architecture

New hub folder `hub/src/client/`, one responsibility per file:

- `pins.ts`: the versions to run (Minecraft, Loader, Fabric API + its sha1). A unit test checks they equal `gradle.properties`.
- `download.ts`: `fetchVerified(url, sha1, dest, size?)`: streams to `dest.part`, checks size and sha1, renames atomically; skips files already present with the right sha1; bounded concurrency (8) and 3 retries with backoff. Reports progress through a callback.
- `mojang.ts`: reads the version manifest → version JSON (sha1 from the manifest) → evaluates `rules` (os name `windows|osx|linux`, `arch`, `features`) → the library list, client jar, asset index and logging config. Asset objects are fetched by hash into `assets/objects/xx/hash`; `.ogg` objects are skipped unless `sounds:true`.
- `fabric.ts`: fetches the Loader profile JSON for the pinned versions and merges it over the Mojang version (Fabric's `mainClass`, its libraries resolved from Maven coordinates with their published sha1, its JVM arguments appended). Fabric API is fetched from `maven.fabricmc.net` against the pinned sha1.
- `launch-args.ts`: pure function from (merged version, paths, options) to `{java, args}`: substitutes `${auth_player_name}`, `${version_name}`, `${game_directory}`, `${assets_root}`, `${assets_index_name}`, `${auth_uuid}` (offline UUID v3 of `OfflinePlayer:<name>`), `${auth_access_token}` (`0`), `${clientid}`/`${auth_xuid}` (empty), `${version_type}`, `${natives_directory}`, `${launcher_name}` (`craftwire`), `${launcher_version}`, `${classpath}` (OS separator), resolution and quick-play features (`--quickPlayMultiplayer host:port`), the logging argument, `-Xmx2G`, and `-Dcraftwire.hidden=true` unless `visible`.
- `instance.ts`: per-username game dir `~/.craftwire/client/instances/<username>/` with `mods/` (Fabric API, the agent, extra mods: copied fresh on each start) and a first-run `options.txt`: `pauseOnLostFocus:false`, `tutorialStep:none`, `onboardAccessibility:false`, `soundCategory_master:0.0`, `narrator:0`, `renderDistance:8`, `inactivityFpsLimit:minimized` (`afk` would throttle a client that never gets input). Keys the user later changed are kept.
- `client-manager.ts`: the process lifecycle (spawn, log ring buffer, ready wait, stop, crash detection with the tail of `logs/latest.log` and any new `crash-reports/` file), structured like `dev/server-manager.ts`.
- `tools/client-process-tools.ts`: the MCP tool.

Shared cache under `~/.craftwire/client/`: `versions/`, `libraries/`, `assets/`. The agent jar ships inside the npm package (`hub/agent/craftwire-agent-fabric-<version>.jar`, copied by `prepack` from `agent-fabric/build/libs`; `prepack` fails if it is missing), so the client agent always matches the hub.

### Linking the process to its agent

The agent's hello gains `gameDir` (client agents, like `serverDir` for server agents). The manager knows each instance's game dir, so it maps the connecting agent to its process without relying on pids (on Windows `java` on PATH can be a launcher stub whose pid differs from the JVM's).

### Agent changes (agent-fabric)

- Hidden window: when the system property `craftwire.hidden` is `true`, a mixin sets the GLFW hint `GLFW_VISIBLE=false` right before the window is created. Rendering still goes to the main render target, which `screenshot` reads.
- `client.quit` method: `Minecraft.getInstance().stop()` on the render thread.
- Hello includes `gameDir` (`FabricLoader.getGameDir()`).

### Display on Linux

When `DISPLAY` and `WAYLAND_DISPLAY` are both unset: if `xvfb-run` exists, the hub wraps the command with `xvfb-run -a`; otherwise `start` fails with `NO_DISPLAY` and a hint to install Xvfb. Windows and macOS need nothing.

### Server address default and online mode

If `server` is omitted and exactly one `server_process` server is running, its `server-ip`/`server-port` from `server.properties` are used (`127.0.0.1` when `server-ip` is empty). If that server has `online-mode=true`, `start` fails with `ONLINE_MODE_SERVER` before downloading anything: "offline clients cannot join; set online-mode=false on this dev server". For an explicit `server` the hub does not know the mode; a refused login surfaces as `JOIN_FAILED` with the disconnect reason.

## Errors

| Code | When |
|---|---|
| `JAVA_TOO_OLD` | Java below the version JSON's major version |
| `DOWNLOAD_FAILED` | A file failed after retries (URL and HTTP status in the message) |
| `CHECKSUM_MISMATCH` | A downloaded file's sha1 differs (file discarded) |
| `NO_DISPLAY` | Linux without a display or `xvfb-run` |
| `ONLINE_MODE_SERVER` | Default server runs online mode |
| `NO_SERVER` | `server` omitted while several managed servers run |
| `ALREADY_RUNNING` | A client with that username runs |
| `CLIENT_CRASHED` | The process exited before ready; log tail and crash report path attached |
| `JOIN_FAILED` | The agent connected but the client was disconnected from the server; reason attached |
| `TIMEOUT` | Not ready within `timeoutMs` (process left running; `status` shows it) |

## Testing

- Unit (vitest, no network): rule evaluation per OS/arch/feature with excerpts of the real 26.2 JSON as fixtures; Fabric merge; Maven coordinate → path; argument substitution (all placeholders, quick play, hidden flag, Windows classpath separator); offline UUID matches the vanilla value for a known name; options.txt merge keeps user keys; pins equal `gradle.properties`.
- Download (vitest, local HTTP server): good file, sha1 mismatch discarded, partial file never cached, retry after a 500, already-cached file not refetched.
- Manager (vitest, fake java script): ready on agent hello with matching `gameDir`, crash before ready → `CLIENT_CRASHED` with tail, stop escalation to kill, hub exit stops clients.
- Agent (client gametest): `craftwire.hidden=true` → window not visible and `screenshot` still returns a rendered frame.
- E2E (hub `test:e2e`, CI on Linux with Xvfb): real download (cached between CI runs by `actions/cache` keyed on the pins), start a Paper server, `client_process start`, `screenshot` returns a non-blank frame, `gui_read` after `/cwfixture menu`, `stop`.
- Spike first (throwaway): a hidden-window client keeps rendering at a normal frame rate and runs without sound assets. If hidden windows stop rendering, fall back to an off-screen position with `visible:true` semantics and record the ruling.

## Docs

README: a "Let the AI run its own client" section; `docs/clients.md` untouched; skills `craftwire`, `paper-plugin-dev` and `fabric-mod-dev` learn `client_process` (and the `mods` argument for mod testing). A note: you must own Minecraft Java Edition; offline mode is for local testing, the same model as Fabric's development client.
