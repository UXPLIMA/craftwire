# M4 manual run — bots on a real Paper server

**Date:** 2026-10-06 · **Hub:** craftwire 0.4.0 (local `hub/dist/cli.js`, driven over stdio by a scratch MCP client) · **Plugin:** Craftwire 0.3.0 → 0.4.0
**Server:** the user's local Paper 26.2 server with uxmBuilders, uxmEssentials, uxmSupplyRush, WorldEdit, WorldGuard, VaultUnlocked and PlaceholderAPI. `online-mode=true`, `spawn-protection=16`.

## Result: PASS

| # | Call | Outcome |
|---|---|---|
| 1 | `server_process {action:"start"}` | Ready in 11.2 s with plugin 0.3.0, `agent: server-1` |
| 2 | `plugin_deploy {jar: craftwire-paper-0.4.0.jar}` | 0.3.0 moved to `plugins/.craftwire-backup/`, 0.4.0 installed, server restarted. `loaded` returned `{version:"0.4.0", enabled:true}`, and `problems` held only the production warning. |
| 3 | `plugin_manage {action:"info", name:"uxmBuilders"}` | Command `builders` |
| 4 | `bot_spawn {count:2, namePrefix:"CwBot"}` | `CwBot1` and `CwBot2` joined at the world spawn (-1167.5, 78, 16.5). The online-mode server accepted them, because bots skip the login handshake. |
| 5 | `bot_action {bot:"CwBot1", action:"command", command:"builders"}` | `success: true` |
| 6 | `bot_action gui_read` | `uxmBuilders`, a chest menu: Package Shop (slot 10), Active Builds (12, "Running: 0/1"), My Crew (14), … with their lore |
| 7 | `bot_action gui_click {slot:10}` | The plugin opened its **Package Shop** menu: Houses / Castles / Farms tabs, Oak Cottage, Back, Close and "Your Balance: 0 $". The result already carried the new menu. |
| 8 | `bot_action gui_close` | `closed: true` |
| 9 | `bot_action {bot:"CwBot2", action:"move_to", x:-1162.5, y:78, z:16.5}` | Walked 5 blocks to 0.14 blocks of the target, but on lower ground (y 74). Result `reached:false, reason:"stuck"` (see note 1). |
| 10 | `bot_action messages` | The bot's inbox held the join lines and uxmEssentials' welcome messages ("ᴡᴇʟᴄᴏᴍᴇ ᴛᴏ ᴛʜᴇ ꜱᴇʀᴠᴇʀ", "ɢʟᴀᴅ ᴛᴏ ʜᴀᴠᴇ ʏᴏᴜ ʜᴇʀᴇ, CwBot1!", …) |
| 11 | `bot_remove {all:true}`, then `world_query players` | `removed: [CwBot1, CwBot2]`, `players: []` |
| 12 | `logs {level:"WARN"}` | Plugin warnings only, none from the bots. uxmSupplyRush: no LuckPerms, a newer release is available. uxmEssentials: a legacy `commands` directory. uxmBuilders: no economy provider. Craftwire: the production warning. |
| 13 | `server_process {action:"stop"}` | `stopped: true, forced: false, exitCode: 0` |

## Notes

1. **`move_to` and height.** "Arrived" needs the bot within 1.5 blocks of the target's y. A target y in the air or underground therefore ends as `stuck` even when the bot stands right under or over it. The result's `x/y/z` and `distance` show what happened. Give the ground height, or read `y` from `world_query`.
2. **Encoding.** `plugin_deploy` `problems` showed the plugin's em dash as `â€”`, while `logs` showed it correctly. The console of a server started from `start.bat` is decoded with the wrong charset there (M3 code, not bots).
3. Bots leave `world/playerdata/<uuid>.dat` files for their offline UUIDs. `spawn-protection=16` would stop them building near spawn, because they are not op.
