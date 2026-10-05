# M3 manual run — dev loop on a real Paper server

**Date:** 2026-10-06 · **Hub:** craftwire 0.3.0 (local `hub/dist/cli.js`, driven over stdio by a scratch MCP client) · **Plugin:** Craftwire 0.2.0 → 0.3.0
**Server:** the user's local Paper 26.2 (build 129) server with uxmBuilders, uxmEssentials, uxmSupplyRush, WorldEdit, WorldGuard, VaultUnlocked and PlaceholderAPI. `start.bat` uses Aikar's flags with 6 GB.
**Java:** `java` on PATH is Oracle's `javapath` launcher for JDK 26.0.1.

## Result: PASS (after two fixes found by this run)

| # | Call | Outcome |
|---|---|---|
| 1 | `craftwire doctor --server C:/Users/pc/Desktop/server` | Node 24.9, Java 26, server jar with flags from `start.bat`, and the EULA all check out. Warnings: plugin 0.2.0 ≠ hub 0.3.0, and a hub older than 0.3.0 is running (see fix 1). |
| 2 | `server_process {action:"status"}` (hub 0.3.0, server still on plugin 0.2.0) | `servers: []`, `external: []`. The 0.2.0 plugin sends no `serverDir`, so the hub cannot recognise the server (see note 1). |
| 3 | Server stopped once from its own console | Clean shutdown, worlds saved |
| 4 | `plugin_deploy {jar: craftwire-paper-0.3.0.jar, serverDir}` | 0.2.0 jar moved to `plugins/.craftwire-backup/`, 0.3.0 installed. The server started under the hub with the `start.bat` flags and was ready in 10.5 s. `loaded` returned `{name:"Craftwire", version:"0.3.0", enabled:true}`, and `problems` held only the production warning. |
| 5 | `list_instances` vs `server_process status` | The pids differed (launcher vs JVM; see fix 2). After the fix both report the JVM pid (41720). |
| 6 | `server_process {action:"start"}` (fresh hub) | Ready in 8.6 s, `agent: server-1`, pid matches the agent |
| 7 | `server_process {action:"restart"}` | `Stopping server` → `Saving worlds` → `All dimensions are saved`, then `Done (8.213s)`, `agent: server-2` |
| 8 | `logs {level:"WARN"}` | Plugin warnings only. uxmSupplyRush: no LuckPerms, a newer release is available. uxmEssentials: a legacy `commands` directory. uxmBuilders: no economy provider. |

## Fixes made during the run

1. **Doctor and old hubs.** A 0.2.0 hub closes the new `status` request with 4003, and doctor reported "no hub answers". It now reports "a hub older than 0.3.0 is running" and suggests a restart.
2. **Java launchers.** On Windows, `java` on PATH is Oracle's `javapath\java.exe`, which starts the real JVM as a child. The hub reported the launcher's pid, and a forced stop would have killed only the launcher and left the JVM running. Status now takes the JVM pid from the agent, and a forced stop kills the whole process tree.
3. Earlier in M3, the jar swap gained a retry while Windows still holds the old jar (`FILE_LOCKED` after 30 s).

## Notes

1. **Upgrading from 0.2.0.** Agents before 0.3.0 do not send `serverDir`/`pid`, so `takeOver` cannot find a 0.2.0 server. Stop it by hand once, then use `plugin_deploy {jar}`. From 0.3.0 on, `takeOver` works; the fake-agent tests cover it.
2. **Unclean stop, operator error.** Between steps 5 and 6 the scratch driver was stopped by killing its whole process tree, which also killed the server's JVM without a `stop`. Nothing was lost: the server had been up about 2.5 minutes, all chunks were saved at startup, and no player joined. Through Claude Code the hub normally exits on stdin EOF and stops its servers gracefully. A tree kill bypasses that, so stop the server with `server_process {action:"stop"}` before killing the hub.
3. The Windows console still shows the em dash in the production warning as `?`/`�`. The bytes are correct UTF-8 (`E2 80 94`) end to end.
