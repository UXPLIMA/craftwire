# M5 acceptance: client_process (headless client)

Date: 2026-10-06 · Hub 0.4.2-dev (branch `feat/m5-client-process`) · Windows 11, Java 26 on PATH, JAVA_HOME = JDK 21

## Manual run (throwaway Paper 26.2 server in the session scratchpad, separate CRAFTWIRE_HOME)

| Step | Result |
|---|---|
| `client_process start` with `JAVA_HOME` pointing at JDK 21 | First attempt: `JAVA_TOO_OLD` naming JDK 21 → fixed: Java candidates are tried in order (JAVA_HOME, then PATH) and the first new enough one is used |
| First start, empty cache | Fabric's profile has no sha1 for the loader → fixed: sha1 from the Maven `.sha1` file, kept for offline starts |
| First start after the fixes | 241 MB downloaded; client joined the server started by `server_process` (no `server` argument); ready in 9.2 s; `list` on the server shows the player |
| Window | Process has no visible window (`MainWindowHandle` 0) |
| `screenshot` | Rendered 1280x720 frame of the world |
| `gui_read` on a menu opened with `server_eval` | Title and items read correctly |
| `client_process stop` | Quit through `client.quit`: `forced:false`, exit code 0 |
| Second start | 0 bytes downloaded, ready in 9.0 s; plain-text log lines in `logTail` (Mojang's XML log config left out) |

## Automated

- Hub unit tests: launcher pieces (rules, libraries, assets, Fabric merge, Maven sha1, arguments, offline UUID, instance folder), downloads (sha1, retries, `.part`), `ClientManager` (ready, crash, join failure, stop, shutdown, default server, online mode), the tool.
- Client game tests now run with `-Dcraftwire.hidden=true`: every pixel check passes with a hidden window.
- E2E `hub/test-e2e/client-process.e2e.test.ts`: real download, hidden client joins a real Paper server, screenshot, plugin menu via `/cwfixture menu`, clean stop. Runs on CI (Linux, under `xvfb-run` started by the hub itself).
