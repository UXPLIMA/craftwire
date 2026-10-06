# Scenarios

A scenario is a test for a plugin, written as JSON: steps that Craftwire runs in order against a real Paper server,
usually through bots. Each step is a Craftwire tool call or a check. The run stops at the first failed check and
reports what was expected, what was found, and what the server looked like at that moment.

Run them from your AI with `scenario_run`, or from a terminal or CI with `npx craftwire test`.

```json
{
  "name": "buying a diamond costs 30 emeralds",
  "bots": ["Buyer"],
  "setup": [
    { "command": "minecraft:give Buyer minecraft:emerald 40" }
  ],
  "steps": [
    { "bot": "Buyer", "command": "/shop", "expect": { "path": "success", "equals": true } },
    { "bot": "Buyer", "action": "gui_read", "expect": { "path": "title", "matches": "shop" }, "within": 2000 },
    { "bot": "Buyer", "action": "gui_click", "slot": 13 },
    { "expect_message": { "bot": "Buyer", "matches": "you bought a diamond" } },
    { "wait": { "condition": "inventory", "player": "Buyer", "item": "emerald", "atMost": 10, "timeoutMs": 3000 } },
    { "expect_event": { "type": "ShopPurchaseEvent", "player": "Buyer", "matches": "\"cancelled\":false" } },
    { "expect_no_exceptions": {} }
  ],
  "cleanup": [
    { "command": "minecraft:clear Buyer" }
  ]
}
```

Save it as `tests/shop.cwtest.json` and run `npx craftwire test tests`.

## The file

| Field | |
|---|---|
| `name` | Shown in reports. Defaults to the file name. |
| `vars` | Constants for `${name}` in steps. |
| `bots` | Bot names (or `{names, location: {x, y, z, world?}}`) spawned before `setup` and removed after `cleanup`. |
| `setup` | Steps run first. A failure here skips `steps`. |
| `steps` | The test. Required. |
| `cleanup` | Always runs, also after a failure. Every cleanup step runs even if one fails; a failing cleanup fails the scenario. |
| `timeoutMs` | The whole scenario's limit (default 300000). |

## Steps

| Step | Does |
|---|---|
| `{"tool": "world_query", "args": {…}}` | Any Craftwire tool. |
| `{"bot": "B", "command": "/shop"}` | `bot_action` command. The result has `success`, `cancelled`, `unknown` and the bot's `messages`. |
| `{"bot": "B", "chat": "hi"}` | `bot_action` chat. |
| `{"bot": "B", "action": "gui_click", "slot": 3}` | Any `bot_action`. |
| `{"command": "time set day"}` | `server_command` on the console. |
| `{"wait": {…}}` | `wait_for`, e.g. `{"condition": "block", "x": 1, "y": 64, "z": 2, "is": "oak_door[open=true]"}`. |
| `{"sleep": 500}` | Waits. Prefer `wait` or `within`. |

Every step above can also have:
- `expect`: a check on its result (or a list of checks), see below.
- `within`: retry the step until its checks pass, for up to this many ms.
- `save`: keep the result as a variable, e.g. `"save": "snap"`, then `"${snap.snapshotId}"` in a later step.
- `expectError`: the step must fail with this error code, e.g. `"BOT_NOT_FOUND"`.
- `name`: the label in reports.

## Checks

| Check | Passes when |
|---|---|
| `{"expect": {"tool", "args", "path", …}}` | The tool's result matches (a call made only to check). |
| `{"expect_message": {"bot", "matches"}}` | The bot received a matching message (chat, system line or action bar) since the last action. |
| `{"expect_hud": {"bot", "path"?, "matches" \| "equals" \| "contains"}}` | The bot's HUD (sidebar, tab list, boss bars, titles) matches. |
| `{"expect_block": {"x", "y", "z", "is" \| "isNot"}}` | The block is (or is not) that block. `oak_door[open=true]` checks only the given properties. |
| `{"expect_event": {"type", "player"?, "matches"?}}` | That Bukkit event (also your plugin's own) fired since the last action; `matches` is a regex on its JSON. |
| `{"expect_no_exceptions": {}}` | No new exceptions were logged since the scenario started. |

The `expect_message`, `expect_hud`, `expect_block` and `expect_event` checks wait up to `within` ms (default 5000).
"Since the last action" means since the most recent step that was not a check, so a message from an earlier
step does not satisfy a later check.

A check on a result (`expect`) takes a `path` and one or more of these comparisons:

| | |
|---|---|
| `equals` | Same value (objects compared by content). |
| `matches` | Case-insensitive regex on the text (JSON for anything but text). |
| `contains` | Substring, an array element, or an object with at least these fields. |
| `exists` | `true`: the path leads to a value. `false`: it does not. |
| `lessThan`, `greaterThan` | Numbers. |
| `length` | Array or text length. |

Paths: `a.b`, `items[0]`, `items[-1]` (last), `lines[*].text` (any element; add `"every": true` to require all),
`["key with spaces"]`.

Variables: `${start}` is the scenario's start time (epoch ms); `${name.path}` reads a saved result or a `vars`
entry. A string that is only `${…}` keeps the value's type (a number stays a number).

## When a check fails

The report names the step and shows the expected and actual values, plus what Craftwire saw at that moment:
- the messages each bot received since the start, and its HUD;
- how many of each event fired since the start;
- new exceptions (grouped, with the plugin and line to blame);
- warnings in the server log.

```
✗ buying a diamond costs 30 emeralds (3.4 s)
    step 4 "expect Buyer receives /you bought a diamond/": Buyer received no message matching /you bought a diamond/
      expected: "you bought a diamond"
      actual:   ["Not enough emeralds"]
      Buyer: {"messages":["Buyer joined the game","Not enough emeralds"],"hud":{…}}
      events: [{"type":"InventoryClickEvent","count":1},…]
```

## `npx craftwire test`

```
npx craftwire test [files or folders…] [--server <dir>] [--junit <file>] [--json] [--wait <seconds>]
```

- Runs every `*.cwtest.json` under the given folders (default: the current folder), in name order.
- `--server <dir>` starts that Paper server for the run and stops it afterwards. Without it, the run waits for a
  server with the Craftwire plugin to connect.
- `--junit <file>` writes JUnit XML for CI. Exit code: 0 all passed, 1 a scenario failed, 2 the run could not start.
- While an AI session's hub is running, it holds the server's connection: run the scenarios from the AI with
  `scenario_run {files: ["tests"]}` instead.
