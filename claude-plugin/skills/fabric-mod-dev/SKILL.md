---
name: fabric-mod-dev
description: Use when developing or debugging a Fabric client mod with the craftwire MCP tools — run the mod in a dev client that the AI can see and drive, check its screens, HUD and logs after each change, and automate the checks with client game tests.
---

# Fabric mod dev loop with Craftwire

Craftwire sees a Minecraft client only through the **Craftwire Agent** mod. Load it next to the mod you are building, and `screenshot`, `gui_read`, `gui_action`, `input`, `hud_read`, `player_state` and `logs` all work on your dev client.

## Setup (once per mod project)
1. Get the agent jar `craftwire-agent-fabric-<version>.jar` for the same Minecraft version. Use the agent version that matches the hub: `list_instances` shows both, and the hub refuses agents with another protocol version.
2. Put it on the dev client's runtime classpath in the mod's `build.gradle`. Minecraft 26.x is unobfuscated, so a plain `runtimeOnly` dependency works:
   ```groovy
   dependencies {
       runtimeOnly files("libs/craftwire-agent-fabric-<version>.jar")   // dev runs only; not shipped with the mod
   }
   ```
   Add other mods the same way to test compatibility, e.g. Sodium. Fabric API must be on the classpath too: the agent needs it.
3. Start the dev client in the background: `./gradlew runClient` (Windows: `.\gradlew.bat runClient`). Wait until `list_instances` shows a `client` instance, then open or create a world. Single-player is fine.

## Without a Gradle dev client
Testing the mod on a server (multiplayer behaviour, a plugin it talks to)? Build the mod jar and let the hub run the client: `client_process {action:"start", mods:["<absolute path to the built jar>"]}`. It joins the server started by `server_process` (`online-mode=false`) with a hidden window; restart it with `stop` + `start` after each build.

## The loop
1. Edit the mod's code.
2. Restart the dev client: stop the `runClient` task, run it again, and wait for `list_instances`. Fabric has no safe hot reload for most changes. A build error ends the task: read its output, fix the `file:line`, and start again.
3. Check what the player sees:
   - `screenshot`, plus `hud_read` for the action bar, titles and boss bars.
   - Screens: open them with `input` (a key) or `chat {action:"command"}`, then `wait_for {condition:"screen_open"}` → `gui_read` → `gui_action`.
   - `logs {level:"WARN"}` for the client log, including mixin and loading errors.
   - `player_state` for position, health and inventory.
4. Repeat.

## Automate it
For checks that should run on every change, write Fabric **client game tests** (`fabricApi { configureTests { enableClientGameTests = true } }`; `./gradlew runClientGameTest`). They start a client, create a world, and run your test class headless in CI with `xvfb-run`. Assert on real frames when the look matters: take a screenshot in the test, and check pixel colours of a known marker block.

## Notes
- F8 in the client pauses AI control: every call then fails with `PAUSED_BY_USER` until the user presses F8 again.
- The camera tools render from any pose without moving the player (`camera set`, `screenshot {camera}`), with or without Sodium.
- To test against the user's real profile instead, build the mod jar and copy it into the profile's `mods/` folder while the game is closed: Windows locks the jars of a running game. Then ask the user to start the game.
- Server side as well? Use the `paper-plugin-dev` skill for the plugin half; one hub sees both.
