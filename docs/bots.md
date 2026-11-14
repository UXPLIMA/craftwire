# Bots

Bots are players the AI (or a scenario) drives: they join, chat, run commands, walk with a pathfinder, click every
kind of menu, dig, use items and blocks, and read what the server sent them (chat, HUD, menus). Plugins see them as
real players. Three kinds share one set of tools (`bot_spawn`, `bot_action`, `bot_behavior`, `bot_list`,
`bot_remove`):

| Kind | Runs in | Joins | Good for |
|---|---|---|---|
| `plugin` (default) | the Paper server, through the Craftwire plugin | that server only | plugin tests, thousands of cheap bots, exact server-side state |
| `java` | a bot runner (`craftwire-botrunner`) | any Java server by `address`: Paper, Spigot, Fabric, Velocity/BungeeCord proxies, other versions | real packets (anti-cheat, packet plugins, login plugins), proxies, servers without Craftwire |
| `bedrock` | a bot runner | any Bedrock server by `address`: BDS, Geyser, other Bedrock software | Bedrock forms, Geyser setups, cross-play |

## A bot runner

`java` and `bedrock` bots need a bot runner. On the hub's machine: `botrunner_process {action: "start"}` (the npm
package ships the runner; `kinds`, `maxBots`, `memoryMb` and `java` are optional). On another machine, copy
`craftwire-botrunner.jar` and the hub's `hub.json` (or set `CRAFTWIRE_HOME`) and run:

```
java -jar craftwire-botrunner.jar --name runner-2 --kinds java,bedrock --max-bots 2000
```

It connects to the hub like the other agents and reconnects by itself. With several runners, a spawn is split
over them by free capacity, and load tests report each runner's share and tick time.

Java bots load the vanilla packet code of the server's version from Mojang's server jar, downloaded once per version
and checked against its pinned sha1. Bedrock bots speak Craftwire's own Bedrock protocol, versions 1.26.60 and
1.26.50: a bot pings the server first and speaks the newest version not above the server's (Geyser takes the newest).
A bot without an account claims a stable 12-digit XUID from its name, which Floodgate needs and no real player has.
Menus are titled by their block entity's name, which is how Geyser shows a plugin menu's title.

To see what a Bedrock player sees (the look of a form or a menu), `bedrock_client` drives your own Bedrock game from
outside (Minecraft for Windows, or mcpelauncher on Linux, where it runs on a screen of its own): start it with an
address, take screenshots, send keys and text (and clicks on Linux); on Windows it stays behind your windows.

## Accounts

Offline-mode servers take any name. For online-mode Java servers (and Xbox-authenticated Bedrock servers), sign in
a Microsoft account you own once with `client_process {action: "sign_in"}` (a device code you confirm in the
browser), then spawn with `auth: "microsoft", account: "<name>"`. Tokens stay in `~/.craftwire/auth/accounts.json` and never
appear in tool results or logs. One account joins as one player at a time.

## Many bots

- `count` up to 1000 per call, `perTick` joins per tick, `spread {radius}` scatters them, `group` names the batch.
- Drive many at once with `bots: "Load*"` or `group` in `bot_action` and `bot_behavior`.
- `profile: "light"` (default) keeps each bot cheap; `realistic` loads chunks and sends settings like a client.
- `bot_behavior` keeps them busy without a call per step: wander, follow, patrol, chat, command_loop, menu_click,
  idle, or a JavaScript `script`.
- `load_test` ramps them up in steps and profiles each step; `npx craftwire load-test` does the same from a shell or
  CI and can fail the build on `--max-mspt`.

## Network trouble and proxies

`netsim {latencyMs, jitterMs, lossPercent}` gives a protocol bot a bad connection. Java bots join proxies like a
client: spawn them with the proxy's address, switch servers with the proxy's `/server` command, and, with the
Velocity plugin installed, watch them move with `wait_for {condition: "server_switch"}` and `proxy {action:
"players"}` (see [velocity.md](velocity.md)).

## Finding bugs with bots

bot_behavior, load_test, dupe_hunt, locale_tour and scenario_record are off in a new session (their definitions
would cost tokens in every session): the AI turns them on with `more_tools`, and scenarios and `craftwire load-test`
do it by themselves.

- Scenarios (`*.cwtest.json`) replay a flow after every change; `scenario_record` turns a session you did by hand
  into one.
- `dupe_hunt` tries click sequences that duplicate or lose items and counts the items before and after.
- `locale_tour` opens your menus in each locale and lists what is left untranslated.
