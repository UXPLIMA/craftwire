# Tool reference

The full reference for every Craftwire tool. The tool descriptions an MCP client loads are kept short to save tokens;
this page (`craftwire://docs/tools`) has the details. Every tool also takes `instance` (id or name from
list_instances; needed only when several instances match) and `operationId` (an idempotency key: a retry with the
same id returns the first result).

Some tools list only their common fields; the others described here (a sign's `lines`, `rotation` of a schematic, a
client's `windowSize`, …) are accepted and checked all the same. Seven tools are off in a new session to save tokens:
bot_behavior, load_test, dupe_hunt, locale_tour, scenario_record, profile_diff and bedrock_client.
`more_tools {enable: [names] | "all"}`
turns them on for the session, and a hub started with `--tools all` (or `--tools ...,extras`) has them on from the
start. Scenarios and `craftwire load-test` turn on what they use by themselves.

## Hub

### list_instances

List connected Minecraft clients and servers (id, name, versions, and `tools` that plugins or mods added through the Craftwire API). Call this first; other tools take `instance` from here.

### more_tools

Turn on the extra tools this session keeps off: `enable` takes their names or `"all"`. They join the tool list (the
client is told the list changed) and stay on until the session ends. Listed only while some are off.

### get_request_status

Status of a request sent with an operationId: unknown, running, done (with result) or failed (with error). Results are kept for 5 minutes.

### wait_for

Block until something happens or the timeout expires; only what happens after the call counts. Events from any instance: chat_match, hud_match (actionbar), screen_open, screen_closed, log_match, player_join, resource_pack (a client's server resource pack loaded or failed; pattern on the status), server_switch (a player moved between a Velocity proxy's servers; pattern on text like `Bob lobby -> games`) (`pattern` is a case-insensitive regex on the event text). Checked on the Paper server every tick: block {x,y,z,world?, is|isNot: id or state like oak_door[open=true]; properties left out match anything}, player_near {player, x,y,z, radius=1.5} (bots too), inventory {player, item, count=1 (at least) | atMost}, event {type: Bukkit event (also a plugin's own), player?, pattern?: regex on the event's JSON e.g. "cancelled":true}, message {player: bot, pattern} (chat, system line or action bar the bot receives), expr {js: server_eval script that must become truthy}. Returns which condition held, the value that met it and elapsedMs; a server condition's TIMEOUT carries the last value seen.

### logs

Recent log lines from a Paper server (the default when one is connected) or a client, oldest first. Stack-trace lines printed separately are folded into the line above (`stack`). Filter by minimum `level`, `contains` (case-insensitive, also searches stacks) and `since` (epoch ms). Up to 1000 lines logged before the agent connected are replayed.

### exceptions

Exceptions the connected server and clients logged, grouped into distinct bugs (same root cause and own stack frames), newest first: type, message, count, firstSeen/lastSeen, origin (first frame outside Minecraft/Paper/Fabric — where to look), plugin when Paper names one, instances, and on Folia `folia` {owner, touched, fix} when the bug is a thread violation (plugin code touching a block, entity or player off its region's thread, or the Bukkit scheduler Folia lacks). Counted since the hub started, beyond the log buffer. Pass id for one bug's full stack trace and the distinct messages it had. Use after a test step to check nothing broke (since: the step's start time).

- `id`: One bug's id from the list: returns its full stack.
- `since`: Only bugs seen at or after this time (epoch ms).

## Client

### screenshot

Capture the game frame and return it as an image. hud:false hides the HUD (F1); chat:false keeps the HUD but leaves chat lines (e.g. command feedback) out. maxSize is the long edge of the returned image. savePath (relative to the current directory) also writes the full-resolution image: PNG, or JPEG when it ends in .jpg/.jpeg (a tenth of the size). camera {x,y,z,yaw,pitch,fov} applies only for this capture.

### record

Record a video (MP4, H.264/H.265) of the game frame with its sound, in real time, encoded by the user's ffmpeg (FFMPEG_NOT_FOUND says how to install it). start: savePath (.mp4, relative to the current directory); preset max (best, CPU-heavy, 60 fps) | high (default) | balanced (fast, small; good for 60 fps on weaker PCs) | light (720p, small files for Discord); fps, resolution (source/2160p/1440p/1080p/720p/480p, only scales down), codec h264|h265, crf (0-51, lower = better), speed (x264 preset ultrafast..veryslow) and audioBitrate override the preset. audio:false records without sound (with sound, the game is silent on the speakers while it records). hud:false (default) hides the HUD like F1, open menus stay visible; chat:false hides chat lines. durationMs stops by itself; camera {action:'path'|'orbit', ...camera tool fields} starts with the first frame and sets the length; wait:true returns the finished video. maxSeconds (default 120, max 600) is the safety stop. stop finishes the file and returns {savedPath, durationMs, frames, duplicatedFrames, droppedFrames, sizeBytes, ...}; status shows progress or the last result.

### camera

Control the render camera without moving the player (client-side only). set: x,y,z,yaw,pitch. look_at: aim at `target`. frame_area: fit `area` (min/max corners) in view. frame_entity: fit `entity` (UUID or name). freecam_on/freecam_off. reset returns to the player's eyes. Moves that play over time (for videos, see record): path flies through `keyframes` [{t ms, x,y,z, yaw,pitch}] (interpolation smooth|linear, ease inOut|none, optional `lookAt` point instead of yaw/pitch); orbit circles `center` at `radius` (height above it, startAngle, degrees, durationMs, ease), always facing it. A move holds its last pose when it ends; set/reset stop it. Keep the camera within render distance of the player.

- `keyframes`: path: timed keyframes; t in ms from the start
- `interpolation`: path: smooth curves (default) or straight lines
- `ease`: start and stop gently (path default) or keep a constant speed (orbit default)
- `lookAt`: path: keep aiming at this point; keyframe yaw/pitch are then optional
- `center`: orbit: the point to circle and face
- `height`: orbit: camera height above the center (default radius / 2)
- `startAngle`: orbit: degrees, 0 = south of the center (default: where the camera is now)
- `degrees`: orbit: how far to turn (default 360; negative turns the other way)

### gui_read

Describe the open screen: title, type, slots (slot, item id, count, name, lore, enchanted, container), hovered slot tooltip, and widgets (buttons/text fields with index and text). Returns {open:false} when no screen is open.

### gui_action

Interact with the open screen. hover/click/right_click/shift_click take `slot` (from gui_read). drag moves `slot` → `toSlot`. click_widget takes `widget` (index or visible text). type sends `text` to the focused field (or `widget`). close closes the screen. Returns the fresh gui_read state.

### input

Simulate gameplay input (no screen open). keys: forward, back, left, right, jump, sneak, sprint, attack, use, pick, drop, inventory, swap_hands, chat, command, player_list, perspective, hide_gui, screenshot. mode press (default, optional durationMs) | hold | release. look adds yaw/pitch degrees. hotbar selects slot 1-9.

### chat

send: say `text` in chat. command: run `text` as a command (leading / optional). read: recent chat and actionbar messages from the hub buffer, optionally filtered by `contains` (case-insensitive) and `since` (epoch ms).

### hud_read

Read HUD state as plain text: bossbars (name, progress, color), actionbar, title/subtitle; sidebar {title, entries:[{name, value, shown}]} with lines as drawn (team prefix + name + suffix, game order, hidden # holders left out; shown is the number text or null when hidden); tab {header, footer, players:[{name, display, ping, gameMode, score?}]}; belowName {title, entries}; tabList (display names).

### player_state

Local player snapshot: position, rotation, health, game mode, dimension, selected hotbar slot, inventory, and the block/entity under the crosshair.

### client_settings

Adjust rendering settings and return the current values: guiScale (0=auto..8), fov (30-110), renderDistance (2-32), hideHud, windowSize {width,height}. Call with no arguments to just read them.

## Server

### server_command

Run a server command as the console and return its feedback lines. The leading / is optional. Feedback a plugin sends up to collectMs after the command returns is included. Plugin commands run as the real console, which plugins such as LuckPerms require; their replies are read from the console log, so a line something else logs at the same moment can show up too. Vanilla commands return exactly their own feedback. asPlayer runs it as that online player instead (feedback then goes to the player's chat; on Folia it runs on that player's region thread). Use the minecraft: prefix when a plugin overrides a vanilla command. Folia has no /scoreboard and a few other vanilla commands.

### server_eval

Run JavaScript (GraalJS) on the server thread with full Bukkit/Paper API access. On Folia there is no single server thread: the script runs on the global region (server-wide state only) unless `at` puts it on the region of a position or `asPlayer` on a player's thread; touching a block, entity or player elsewhere fails with WRONG_THREAD. Globals: server, player(name), plugin(name), loc(x,y,z,world?), Java.type('fully.qualified.Class'), print(...). The last expression is returned as JSON (Java objects as {class,toString}); print output comes back in `output`. Globals persist until reset:true, a timeout, or a hub reconnect; store them on globalThis (top-level let/const cannot be re-declared on the next run).

- `at`: Run on the region that owns this position (Folia; its chunk is loaded first). Not needed on Paper.
- `asPlayer`: Run on this online player's thread (Folia: the player's region). Not needed on Paper.

### world_query

Read the world. block: one block at x,y,z. region: every block in min..max (inclusive, at most 32768) as palette + runs ([palette index, count] pairs in order x fastest, then z, then y) + counts; encoding:'indices' returns one palette index per block in `blocks` instead. entities: entities in min..max, optional type (e.g. 'zombie'). players: online players. find_block: positions of `block` (an id like 'chest' or a state like 'oak_stairs[facing=east]') in min..max, up to limit.

- `world`: World name. Defaults to the main world.

### world_edit

Change the world. set_blocks: blocks [{x,y,z,block}] (up to 10000). fill: min..max with block, only where `replace` matches if given. snapshot: save min..max and return an id; restore: put snapshot `id` back. save_schematic: save min..max as structure `name`; paste_schematic: place it with its origin at `at`, optionally rotated/mirrored around that point. Edits over 32768 blocks take a snapshot first and return its snapshotId. Block states use Minecraft syntax, e.g. 'oak_stairs[facing=east]'.

- `world`: World name. Defaults to the main world.
- `rotation` (paste_schematic): none, clockwise_90, clockwise_180 or counterclockwise_90 around `at`.
- `mirror` (paste_schematic): none, left_right or front_back.
- `includeEntities` (save_schematic, paste_schematic): save and place the entities in the area too (default false).
- `physics`: let placed blocks update their neighbours (sand falls, water flows); default false, as WorldEdit does.

### world_render

Draw a region of a Paper world as an image, without any client: top (the surface as a map draws it: north up, x right, z down; higher blocks lighter, water by depth), slice {y} (the blocks at one height, e.g. a floor plan; air is light grey), or side {facing, y1?, y2?} (a front view looking toward facing; nearer blocks brighter). Up to 512x512 blocks; scale is pixels per block (1-8, default fits about 1024 px). grid draws a line every N blocks with coordinates in the margins, so you can name positions. Players and bots are marked with their names. Chunks that were never generated are not generated (checkered) unless generate:true. savePath also writes the PNG (relative to the current directory).

- `world`: World name. Defaults to the main world.
- `y`: slice: the height to cut at.
- `facing`: side: the direction you look.

### server_info

Server health and metadata: TPS (1/5/15 min), MSPT, memory, versions, online players, worlds and plugins. On Folia also folia:true, regions (the TPS [5s, 15s, 1m, 5m, 15m] of the region at each world's spawn and at each player) and slowestRegion; tps/mspt are then the global region's.

### plugin_manage

list: installed plugins with version and state. info: details for `name` (version, authors, depends, commands). enable/disable: toggle `name` at runtime (not Craftwire itself). Toggling is for quick checks; restart the server for a clean state. Folia cannot toggle plugins (UNSUPPORTED): use plugin_deploy.

### events

What Bukkit events fired on the server, recorded at MONITOR priority so `cancelled` is the final outcome. summary {since?}: counts per type (and how many ended cancelled). query {type?, player?, since?, cancelledOnly?, limit?}: recent events with their values (getters: numbers, text, enums, blocks as type@x,y,z, items as type xN, players by name). listeners {type}: which plugins listen to an event, at what priority, and whether they skip cancelled events — check this when a listener seems not to run. watch {types}: also record busy events that are skipped by default (EntityMoveEvent, BlockPhysicsEvent, tick events…). A plugin's own events are recorded from their first call. Use since = the time a test step started.

- `type`: Event type: simple (PlayerInteractEvent) or full class name. query: a substring of the simple name also matches.
- `types`: watch: event types to start recording.
- `player`: query: only events about this player (bots included).
- `since`: Epoch ms; only events at or after it.

## Dev loop

### server_process

Start, stop, restart or inspect a local Paper server run by the hub. start waits for the console's `Done (` line and, when the Craftwire plugin is in plugins/, for the plugin to connect (result `agent`). JVM flags and the jar come from the folder's start.bat/start.sh unless jvmArgs/jar are given. The EULA is never accepted for you: on EULA_NOT_ACCEPTED ask the user. A server started outside Craftwire (status → external) is stopped or restarted only with takeOver:true — ask the user first. Servers started here stop when the hub exits.

- `serverDir`: Server folder (contains the Paper jar). Optional when only one server is known.
- `jvmArgs`: JVM flags, replacing the start script's.
- `jar`: Server jar, relative to serverDir.
- `java`: Java executable. Paper 26.x needs Java 25+.
- `takeOver`: Allow stopping a server that was started outside Craftwire.
- `timeoutMs`: How long start/restart waits for the server to be ready.
- `tail`: status: console lines to include per server.

### plugin_deploy

Build a Paper plugin project (Gradle wrapper `build -x test` or Maven `package -DskipTests`, auto-detected) or take a ready jar, install it into the server's plugins/ (older jars of the same plugin move to plugins/.craftwire-backup/), restart the server (starting it if it is down) and report `loaded` (enabled, version) plus `problems` (its WARN/ERROR log lines). A failed build returns BUILD_FAILED with details.errors as file:line. restart:false on a running server stages the jar in plugins/update/ for the next start.

- `projectDir`: Plugin project root to build.
- `jar`: A ready plugin jar to install instead of building.
- `buildCommand`: Shell command run in projectDir instead of the detected build, e.g. '.\gradlew.bat shadowJar' on Windows (a bare gradlew.bat is not found from the hub's shell).
- `jarGlob`: Which built jar, relative to projectDir, e.g. 'build/libs/*-all.jar'. Needed when several plugins are built.
- `javaHome`: JAVA_HOME for the build.
- `serverDir`: Server folder. Optional when only one server is known.
- `takeOver`: Allow stopping a server that was started outside Craftwire (ask the user first).
- `timeoutMs`: How long the restart waits for the server.

### client_process

Start, stop or inspect a headless Minecraft client run by the hub (offline mode, hidden window), so client tools work without the user opening the game. start downloads Minecraft + Fabric on first use (about 150 MB per version, cached in ~/.craftwire/client; status shows progress). The version is the one `server` reports (asked with the server-list ping), else the newest supported; `version` overrides, starts the client with the Craftwire agent, joins `server` (default: the server started by server_process, which must run online-mode=false) or opens a singleplayer `world` (creating it with `create`), and returns once the player is in the world; `instance` in the result is the client for screenshot, gui_read, input and the rest. mods adds extra mod jars (the mod you are developing, Sodium, …). Several clients can run with different usernames. Clients stop when the hub exits.

- `server`: host:port to join. Default: the single server started by server_process; none → title screen.
- `world`: Open a singleplayer world instead of joining a server: the client tools then work in it, with no server needed.
- `version`: Minecraft version (26.2, 26.3). Default: the server's, else the newest.
- `username`: Offline player name (default Craftwire). stop: which client, when several run.
- `mods`: Absolute paths of extra mod jars, loaded next to Fabric API and the agent.
- `visible`: Show the game window (default hidden).
- `windowSize`: Window and screenshot size (default 1280x720).
- `java`: Java executable; Minecraft 26.x needs Java 25+. Default: JAVA_HOME, then java on PATH.
- `sounds`: Also download the sound assets (~360 MB). Off by default; the game runs fine without them.
- `timeoutMs`: How long start waits for the player to be in the world (downloads not counted).
- `tail`: status: log lines per client.
- `lite`: a small heap and no frames unless something is captured, to run several clients at once.
- `resourcePacks`: what the client does with a server pack: accept, decline, or prompt (vanilla's screen, the default).
- `account`: start with a Microsoft account signed in with `sign_in` (a device code: open the URL it returns and enter
  the code); `sign_out {name}` forgets one. Needed for online-mode servers.
- `mod_profile {name, mods}` saves a set of mods (jar paths or `modrinth:<slug>`, downloaded once); `start
  {modProfile}` loads it.

### scenario_run

Run test scenarios: steps (tool calls and checks) the hub runs in order, stopping at the first failure and reporting what was expected, what was found, and the context at that moment (bots' messages and HUD, events, new exceptions, warnings). Cleanup always runs. Pass `scenario` (inline) or `files` (*.cwtest.json files or folders, relative to the hub's working directory). A scenario: {name, vars?, bots?: [names], setup?: [steps], steps: [steps], cleanup?: [steps] (always runs), timeoutMs?}. Steps: {tool, args} (any Craftwire tool); {bot, command} / {bot, chat} / {bot, action, …} (bot_action); {command} (server_command); {wait: {…wait_for}}; {sleep: ms}. Any of these may add save: "name" (use the result later as ${name.path}), expect: {path, equals|matches|contains|exists|lessThan|greaterThan|length} (or a list), within: ms (retry until it holds) and expectError: "CODE". Checks: {expect: {tool, args, path, equals…}}, {expect_message: {bot, matches}}, {expect_hud: {bot, path?, matches|equals|contains}}, {expect_block: {x,y,z, is|isNot}}, {expect_event: {type, player?, matches?}} (these wait up to `within`, default 5000 ms, and look at what happened since the last action), {expect_no_exceptions: {}}. Paths: a.b[0].c, [-1] for the last element, [*] for any element. ${start} is the scenario's start time.

### scenario_record

Turn the calls of an MCP session into a scenario that repeats them: `session` (default: this one; the web panel's replay tab lists the others), `from`/`to` (call positions as the panel numbers them), `name`. Bot actions become `{bot, command|chat|action}` steps, server commands `{command}`, waits `{wait}`, other changes `{tool, args}`; failed calls, environment tools (server_process, plugin_deploy, client_process, load_test, …) and calls that only read are left out and listed in `skipped` with the reason (`includeReads:true` keeps the reads). Idempotency keys are dropped so every call runs again, bots the session spawned and did not remove are removed in `cleanup`, and the scenario ends with `expect_no_exceptions`. Add your own `expect` checks before keeping it. `file` (ending in .cwtest.json, `overwrite` to replace) writes it on the hub's machine; over `craftwire serve` the scenario is only returned. A teammate can record only their own sessions.

## Bots

Bots come in three kinds: `plugin` (fake players inside a Paper server with the Craftwire plugin, the default), `java` and `bedrock` (real protocol clients run by a bot runner, joining any server by `address`, including servers without Craftwire, proxies and other versions). The same tools drive all three; an action a kind cannot do answers `UNSUPPORTED_FOR_KIND`. Many bots at once: name them with `bots` (a list or a pattern like `Load*`) or `group` instead of `bot`.

### bot_spawn

Spawn bots that plugins treat as real players (join event, tab list, permissions). Names: `names`, or `count` (up to 1000 per call) × `namePrefix`1, 2, …; `group` names the batch for later calls. `location` defaults to the main world's spawn; `spread {radius}` scatters them. `perTick` joins that many per tick. `profile`: light (default: cheap, no chunk view) or realistic (view distance, chunk loading like a client). `settings` sets the client settings plugins read: locale, brand, ping, skin, mainHand, chat, skinParts, viewDistance. `resourcePack` says what the bot does with a server pack (accept, decline, fail_download, …; `verifyPacks` downloads and checks it). Plugin bots skip the login checks (whitelist, bans) and their player data is deleted when they leave; `hideBots` makes them invisible to every player (pure load tests).

Java and Bedrock bots need `address` (host or host:port) and a bot runner (botrunner_process start, or one on another machine). `version` picks the Java protocol (default: the server's), `auth: microsoft` with `account` (signed in once with client_process {action:'sign_in'}) joins online-mode servers, `netsim {latencyMs, jitterMs, lossPercent}` adds network trouble, `worldMode: none` skips the shared chunk cache (cheaper, but the bot cannot walk with move_to). Returns `{spawned, names: "Bot1..Bot50", failed?}`.

### bot_action

Drive a bot. chat {text}. command {command, collectMs} sends it like a client (PlayerCommandPreprocessEvent fires, so command blockers apply) and returns success (the command exists and no plugin cancelled it; cancelled/unknown say why) and the messages the bot received. messages {since?, limit?}. look {yaw,pitch} or {x,y,z}. move_to {x,y,z, tolerance?, sprint?, timeoutMs? (30000)} finds a path and walks it: around walls, up steps and slabs (jumps one block), down drops of up to maxFall (3), through doors and fence gates (opened with a real right-click; openDoors:false avoids them), up ladders and vines, swimming through water; never into lava, fire, magma or cactus. Searches loaded chunks only, within 256 blocks; searches again when blocked by a mob or player. Returns reached + reason (arrived; no_path with closest — the nearest point it can reach, partial:true walks there; blocked — a door that would not open; height — right x/z but the target y is not where the bot can stand; stuck; timeout; died) and path {nodes, length, complete}. path:false walks in a straight line instead. break_block {block:{x,y,z}, face?} digs it like a survival player (the real dig time for the held tool; instant in creative) and returns broken, the block, ticks, and cancelledBy (BlockBreakEvent / BlockDamageEvent) when a plugin refused; reason unbreakable/timeout/refused otherwise; out of reach is an error: move_to first. jump. sneak {on} and sprint {on} (PlayerToggleSneakEvent / PlayerToggleSprintEvent). drop {all?} drops the held item or stack (PlayerDropItemEvent; cancelled:true when a plugin refused). swap_hands (PlayerSwapHandItemsEvent). state: position, health, held item, inventory, open menu, moving, digging, sneaking, sprinting. give {item, count} (ids like diamond_sword, components allowed). select_hotbar {slot 0-8}. gui_read / gui_click {slot, click: left|right|shift|number (key 1-9)|offhand|drop|drop_stack|middle|double|drag (slots, button)|outside} / gui_close for plugin menus (`settleMs`, default 150, is how long gui_click waits for the plugin before reading the
menu back); move_item {from, to, count?}, transfer {from: container|inventory} and equip {to: hand|offhand|head|chest|legs|feet} move items the way a player would (slots as in gui_read; gui_click returns the menu after the plugin reacted; with inventory:true and no menu open they act on the bot's own inventory like a player pressing E: 5-8 armour, 9-35 main, 36-44 hotbar, 45 offhand). use: right-click with the held item — {block:{x,y,z}, face} on a block, {entity: uuid} on an entity, or nothing for the air. attack {entity: uuid} or {type: 'zombie'} (nearest within reach). hud_read: what the bot's screen would show, rebuilt from the packets it received (works with plugins that send scoreboard packets directly) — same fields as the client hud_read. Special screens: anvil_rename {text}, sign_edit {block, lines, side?}, book_edit {pages, title?}, trade_select {index}, menu_button {id} (enchanting, stonecutter, loom, lectern), beacon {primary, secondary?}, recipe_place {recipe}, creative_set {slot, item}, dialog_answer {id, inputs?} (server dialogs; Bedrock forms too), chat_click {message, part?} (a clickable chat component). settings changes the client settings; resource_packs lists the packs offered and what the bot did. stop ends a move_to. Actions go through the server's packet handlers (or the real protocol for java and bedrock bots), so plugins see the same events as from a real client.

- `path`: move_to: find a path (default) or walk in a straight line (false).
- `partial`: move_to: when the target cannot be reached, walk to the closest point instead of not moving.
- `on`: sneak / sprint: true to start (default), false to stop.
- `all`: drop: the whole stack instead of one item.

### bot_remove

Remove bots: `bot`, `bots` (names or a pattern), `group`, or `all:true`. Bots leave like players logging out (quit event, data saved). Returns the count; `names:true` lists them.

### bot_list

The bots of every kind (or by `bot`, `bots`, `group`, `kind`): count, joining, and per bot its name, kind, group and position.

### bot_behavior

Give bots something to keep doing without a call per step: `set {type, params}` with wander, follow, patrol, chat, command_loop, menu_click, idle or script (JavaScript with `bots.onTick` and `bots.onMessage`), `stop`, `status`. `seed` makes random behaviors repeatable. load_test uses them for realistic load.

### bot_form

Bedrock bots only: read the open form (simple buttons, modal yes/no, custom inputs), `answer` it with `button` (index or text) or `values` (one per custom input), or `close` it. Listed while a bot runner with Bedrock bots is connected.

### botrunner_process

Start, stop or show the bot runner this hub runs (`kinds`, `maxBots`, `memoryMb`, `java`). A runner on another machine connects by itself with the hub token (`java -jar craftwire-botrunner.jar --name runner-2`); its bots are then spawned on it as well, split by free capacity.

### load_test

Spawn bots in steps (`ramp`, e.g. [10, 50, 100, 200], `stepSeconds` each), optionally run a `behavior`, and per step measure TPS, MSPT (avg, p95, max), slow ticks, heap and chunks and profile who uses the tick. Java and Bedrock bots are split across runners; the report shows each runner's share and tick time. Returns the steps and an HTML report; bots are removed at the end unless `keep`. From CI: `npx craftwire load-test --ramp 10,50 --max-mspt 40`.

### dupe_hunt

On your own test server: a bot opens the menu under test (`open {command}` or `{block}`; none = its own inventory) and tries click sequences that have duplicated or lost items before: shift_spam, number_keys, offhand_swap, double_collect, drag_split, click_then_close, close_race, drop_from_menu, middle_clone, outside_drop, right_split, swap_into_menu, spam_with_pause (`sequences` to pick, `rounds` each). Before and after each try it counts its whole inventory, the cursor, the dropped items within `dropRadius` and, with `countMenu` (for chests and other real containers, not plugin GUIs whose items are only displays), the menu. Returns the tries whose items changed: `gained` (a possible dupe) and `lost` (a loss bug), with the errors the clicks met. Give the bot items first; what it gains stays in its inventory.

### locale_tour

Find what a plugin left untranslated: for the `base` locale (en_us) and each of `locales`, a bot joins with that client locale, opens each of `targets` (`{name, command}` or `{name, block}`) and reads the menu title, item names and lore, the chat replies and (`hud`) titles, action bar, boss bars and sidebar. Per locale it lists `untranslated` (text identical to the base locale's at the same place), `rawKeys` (texts that look like message keys, unparsed tags or unfilled placeholders) and `notSeen` (places the base showed that this locale did not).

## Proxy

### proxy

A Velocity proxy with the craftwire-velocity plugin; listed while one is connected. info: version, port, online mode, the try order and servers. servers: each backend pinged (online, latency, players, version, motd; `ping:false` skips the ping). players {server?}: who is on which server. send {player, server}: move a player (status and the backend's reason when it refused). kick {player, reason?}. command {command}: a proxy command (velocity, server, send, glist or a proxy plugin's) as a console with every permission, with its output. Cross-server bot tests: spawn java bots with the proxy's address, move them with `bot_action command "server games"` or proxy send, and wait with wait_for server_switch.

## Debugging

### profile

Find what uses a server's tick or a client's frame time, and whose code it is. Samples the game thread (Paper: Server thread; client: Render thread) every intervalMs for durationMs, then returns: busyPercent (how much of the time the thread was working rather than waiting for the next tick/frame); owners — who the time belongs to: a plugin or mod by name (its own code and what it called, e.g. a listener's world lookups), or minecraft / server (Paper, CraftBukkit) / fabric-loader / craftwire / java / library; entryPoints — where the server or game called into each plugin or mod (event: the event a listener handles, task: a scheduler task, calledFrom: the caller), so you know which listener or task to look at; hotMethods — the methods the thread was executing (self time). On a server also ticks: count, msptAvg, p50, p95, max, over50ms and the slowest ticks with their owners; on a client fps {avg, min}. Mixin handlers count for the mod that injected them. Run it while the slow thing happens (start the scenario or bot action first, or ask the user to reproduce it), then trace a hot method for exact call counts and times.

- `durationMs`: How long to sample.
- `intervalMs`: Sampling interval (default 10).
- `top`: Entries per list (default 15).

### trace

Time every call of specific methods on a server or client for durationMs, without changing any code: the JVM instruments the named methods (Flight Recorder method tracing) only while tracing. method: com.example.Shop::buy (one method), com.example.Shop (every method of the class), @com.example.Timed (methods with that annotation); nested classes as Outer$Inner; several separated by ';'. Returns methods (invocations, avgMs, maxMs, totalMs per method), slowest (the slowest calls with thread and stack, each frame with its owner), callers (who called them, with counts) and calls. minMs keeps only calls at least that slow. A trace stops early after 100000 calls (truncated: true). One trace at a time per game (ALREADY_RUNNING otherwise; trace several methods at once with ';'). Make the method run while tracing (a command, a bot action). Typical use: profile → a hot method or a plugin's entry point → trace it.

- `minMs`: Only report calls at least this slow (counts still include every call).
- `stackDepth`: Frames per slow call (default 8).
- `limit`: Slowest calls to return (default 50).

### profile_diff

Compare two profile results (before and after a change): MSPT and busy time, and the owners and hot methods whose share changed most.

### bedrock_client

Your own Bedrock game, seen and driven from outside: Minecraft for Windows, or on Linux the Android game run by
[mcpelauncher](https://minecraft-linux.github.io). The hub opens it with a `minecraft://connect` link, takes pictures of
its window and sends keys, text and clicks. It reads nothing from inside the game, so use it for what a player sees (a
menu's look, a form, a HUD); Bedrock bots read menus and forms as data. One game at a time; the game must be installed
and signed in once by hand. Over `craftwire serve` only the owner and the dev role have it, since it drives a game on
the hub machine.

- `action`: start (open the game; with `address` join host[:port], default port 19132), status (running, focused, the
  window's area), screenshot (PNG of the window; `maxWidth`, default 1280), key {key, holdMs?} (a letter, a digit,
  f1-f12, enter, escape, space, tab, backspace, shift, ctrl, alt, arrows, home, end, pageup, pagedown, delete, slash,
  minus, equals, comma, period), type {text, enter?}, command {command} (T, the text, Enter), click {x, y, button?} and
  scroll {x, y, amount} (in the last screenshot's pixels; Linux only), show (Windows: brings the window back onto the
  screen and to the front for you), stop (asks the game to close).

On Windows:

- The game works off the screen. start opens it with the link and moves its window past the right edge of the
  desktop (about 1600 x 900), so the user's cursor never passes over it; if it took the focus, the window the user
  was in gets it back. A game the user started and has in front stays where it is; `show` brings the hub's game back.
- A running game given a link only comes to the front and stays on its menu, so start with an address closes a
  running game and starts it again (`restarted: true`); start without one leaves a running game alone.
- Screenshots use Windows.Graphics.Capture, as screen recorders do: the picture comes with the window covered and
  without the focus (`method: "capture"`). Only where that fails (an old Windows 10) do they fall back to
  PrintWindow, then to a screen copy that brings the game to the front.
- Keys, text and commands are posted to the window, so they reach the game in the background without the user's
  keyboard.
- Clicks and the wheel cannot be sent: the game reads the mouse straight from the device (GameInput), and neither
  posted mouse messages nor injected input reach it, even with the game in front (measured on 26.63). click and
  scroll fail with BEDROCK_CLICK_UNSUPPORTED. Every menu, dialog and form takes the keys instead: the arrows move the
  selection (a long list scrolls with it), enter presses the selected button, escape goes back.
- While the game is playing (no menu open), it reads the mouse from the device even off the screen, so the user's
  mouse turns the character. Leave it on the pause menu (key escape) while the user works; there it ignores the mouse.

On Linux:

- Install mcpelauncher (a package, the Flatpak `io.mrarm.mcpelauncher` or a build), open its launcher once, sign in
  to Google Play with the account that bought Minecraft and download a version; sign in to Xbox in the game once if
  the server needs it. Craftwire never downloads the game. The hub then runs `mcpelauncher-client` itself with the
  newest downloaded version; `CRAFTWIRE_MCPELAUNCHER` names another client program and `CRAFTWIRE_BEDROCK_GAME_DIR`
  another version folder.
- The game runs on an Xvfb screen of its own (1280 x 720), so the machine needs no display and every action,
  clicks and scrolls included, stays off the user's desktop. `CRAFTWIRE_BEDROCK_DISPLAY=:0` puts it on a display
  you can watch instead; then keys and clicks move that display's focus and pointer.
- Needs Xvfb, xdotool and ImageMagick (or ffmpeg): `apt install xvfb xdotool imagemagick`. Xvfb renders with Mesa on
  the CPU, so expect a low frame rate; screenshots do not mind. `npx craftwire doctor` checks all of it.
- A start that ends with BEDROCK_CLIENT_EXITED carries the game's last log lines in `details`.

### client_eval

Run JavaScript (GraalJS) on a Minecraft client's render thread, like server_eval on a server: for debugging a mod or checking client state no other tool shows. Globals: mc (the Minecraft instance), player (the local player, or null), level (the client world, or null), screen() (the open screen, or null), print(...); Java.type('…') reaches any class, the mods' own included (Minecraft 26.x names are Mojang's, unobfuscated). Returns {result, output}; Java objects come back as {class, toString}. Globals persist between calls until reset:true or a new hub session. Keep scripts short: the game does not render while one runs (timeoutMs cancels it). The first call downloads GraalJS (about 60 MB, checked against the agent's sha256 list) into ~/.craftwire/lib and can take a minute; later calls start at once. The user can turn it off (-Dcraftwire.allowEval=false).

- `reset`: Drop the globals of earlier calls first.
