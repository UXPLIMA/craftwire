---
name: paper-plugin-dev
description: Use when developing or debugging a Paper/Bukkit plugin with the craftwire MCP tools — start a local test server, build and redeploy the plugin after each change, read its compiler errors and logs, and check the result in game.
---

# Paper plugin dev loop with Craftwire

## Setup
1. `server_process {action:"status"}` — servers this hub runs are under `servers`; servers started elsewhere are under `external`.
2. No server yet: `server_process {action:"start", serverDir:"<folder with the Paper jar>"}`. JVM flags come from its start.bat/start.sh. The first start can take minutes.
   - `EULA_NOT_ACCEPTED`: stop and ask the user to read the EULA and set `eula=true` themselves. Never edit eula.txt.
   - `JAVA_TOO_OLD` / `JAVA_NOT_FOUND`: Paper 26.x needs Java 25+; pass `java`.
   - `PORT_IN_USE` / `WORLD_LOCKED`: another server is running; check `status`.
3. The start result has `agent` when the Craftwire plugin connected. If `warning` says it is missing: `plugin_deploy {jar:"<craftwire-paper jar>"}`.

## The loop
1. Edit the plugin code.
2. `plugin_deploy {projectDir:"<project root>"}` — builds (tests skipped), installs the jar, restarts the server, and returns `loaded` (enabled, version) and `problems` (WARN/ERROR lines naming the plugin).
   - `BUILD_FAILED`: fix each `details.errors[]` entry (`file:line`) and deploy again; if `errors` is empty, read `details.outputTail`.
   - `AMBIGUOUS_JAR`: multi-module project; pass `jarGlob`, e.g. `"my-plugin/build/libs/*-all.jar"`.
   - `NOT_MANAGED`: the user started this server outside Craftwire. Ask before passing `takeOver:true` — it stops their server.
     If the message says something listens on the server's port, it runs without the Craftwire plugin: ask the user to stop it from its console, then deploy again.
3. Check: `logs {level:"WARN"}`, `server_command` for the plugin's commands, `server_eval` to inspect state (`plugin('Name')`), and the client tools (`screenshot`, `gui_read`) for anything a player sees.
   - No client needed for most checks: `bot_spawn`, then `bot_action` to run the plugin's commands, click its menus (`gui_read`/`gui_click`), use items or blocks, and read what the plugin sent (`messages`). Remove bots afterwards.
4. Repeat. Keep `restart:true` (default): Paper cannot reload plugins safely.

## Notes
- `buildCommand` runs any build in projectDir. On Windows write wrappers with a path: `.\gradlew.bat shadowJar` (a bare `gradlew.bat` is not found from the hub's shell). `javaHome` sets the JDK for the build.
- Replaced jars are kept in `plugins/.craftwire-backup/`.
- `restart:false` on a running server stages the jar in `plugins/update/`; it loads on the next start.
- A server started by `server_process` stops when the hub exits (the AI client closes). `server_process {action:"status", tail:100}` shows its console.
- `npx craftwire doctor --server <dir>` checks Node, Java, the hub, the EULA and the plugin.
