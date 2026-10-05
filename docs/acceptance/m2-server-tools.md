# M2 manual end-to-end run — server tools on a real Paper server

**Date:** 2026-10-05 · **Hub:** craftwire 0.2.0 (local `hub/dist/cli.js`, driven over stdio by a scratch MCP client) · **Plugin:** Craftwire 0.2.0
**Server:** the user's local Paper 26.2 (build 129) test server with uxmBuilders, uxmEssentials, uxmSupplyRush, WorldEdit, WorldGuard, Vault(Unlocked), PlaceholderAPI
**Client:** the user's Modrinth "Fabric 26.2" profile with Craftwire Agent 0.1.0 (M1 build)

## Result: PASS

The jar was copied into `plugins/` and the server restarted. The first start downloaded GraalJS 25.0.4 through Paper's library loader in about 7 s, and the plugin connected to the hub before `Done (17.6s)`.

| # | Call | Outcome |
|---|---|---|
| 1 | `list_instances` | `client-1` (siracozmen, agent 0.1.0) and `server-1` (name `server`, agent 0.2.0, MC 26.2) |
| 2 | `server_info` | TPS 19.99 ×3, MSPT 0.5, 923/6144 MB, 3 worlds, all 8 plugins with versions |
| 3 | `server_command {command:"time query gametime"}` | `success:true`, **empty output** — uxmEssentials overrides `/time` (`server_eval` showed `PluginVanillaCommandWrapper / uxmEssentials`) and prints nothing for `query` |
| 3b | `server_command {command:"minecraft:time query gametime"}` | `["The game time is 614237 tick(s)"]` |
| 3c | `server_command {command:"version", collectMs:3000}` | the asynchronous version check reply was collected; with the default 250 ms only "Checking version, please wait..." came back |
| 4 | `server_eval {code:"plugin('uxmBuilders').getPluginMeta().getVersion()"}` | `"1.0.0"` |
| 5 | `logs {level:"WARN"}` | 6 lines, incl. the Craftwire production warning and **`uxmBuilders: Economy provider not found (Vault/VaultUnlocked) -- prices disabled`** |
| 6 | `world_query region` → `world_edit snapshot` → `fill stone` (75 blocks) → `world_query block` (stone) → `world_edit restore` → `world_query block` (air) → `find_block stone` (none) | box 5×3×5 at y 300–302 above spawn (-1168, 16); area back to air |
| 7 | client: `gui_action click_widget "Back to Server List"` → `"Join Server"` while `wait_for {condition:"player_join", instance:"server-1"}` waited | join event `{action:"join", name:"siracozmen", uuid:…}` |
| 8 | `world_query {action:"players"}` | siracozmen at (-3107, 75, -603.6), creative, op |
| 9 | client `chat send "craftwire m2 test"` while `wait_for chat_match` on `server-1` waited | server `chat` event with sender siracozmen |
| 10 | `server_command {command:"minecraft:time query gametime", asPlayer:"siracozmen"}` | dispatched as the player; the reply appeared in the client's chat buffer (`chat {action:"read"}`) |

## Notes and follow-ups

1. **Client logs:** `logs {instance:"client-1"}` was empty because the installed mod is the M1 build (0.1.0). Client log streaming ships in the 0.2.0 mod and is covered by the client gametest. Re-check after updating the mod in the profile.
2. **Windows console:** the startup warning's em dash shows as `�` in a Windows console. `logs/latest.log` and the hub receive correct UTF-8. Cosmetic only.
3. **Not Craftwire:** the Builders "Balance: 0 $" from the M1 run is explained by finding 5. uxmBuilders finds no Vault economy provider, so prices are disabled. Fix it on the server (an economy plugin that registers with VaultUnlocked) before the final marketing shots.
4. Skill guidance confirmed: plugins that override vanilla commands need the `minecraft:` prefix, and slow plugins need a higher `collectMs`.
