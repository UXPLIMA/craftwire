# Running the hub as a server (`craftwire serve`)

Normally your AI client starts the hub itself and talks to it over stdio: one AI session, one hub. Run
`npx craftwire serve` instead when you want:
- several AI clients (or sessions) to share the same game and server,
- an AI client on another machine,
- `npx craftwire test` to run while your AI is connected.

```
npx craftwire serve
[craftwire] hub 0.6.0 serving MCP at http://127.0.0.1:7777/mcp (games connect on 127.0.0.1:47821)
[craftwire] token created: ~/.craftwire/http.json
[craftwire] Claude Code: claude mcp add --transport http craftwire http://127.0.0.1:7777/mcp --header "Authorization: Bearer …"
```

Run the printed command once (or put the URL and the header in your client's MCP settings), and remove the
stdio entry, so only one hub runs. Games connect to the hub exactly as before.

## Options

| Option | |
|---|---|
| `--port <n>` | HTTP port (default 7777). |
| `--host <address>` | Address to listen on (default 127.0.0.1: this machine only). |
| `--allow-remote` | Required for any other address. |
| `--tls-cert <file> --tls-key <file>` | Serve HTTPS (PEM files). |
| `--read-only` | Offer only tools that change nothing (screenshots, reads, logs, renders, status). |
| `--tools <groups>` | Offer only these groups: `hub`, `client`, `server`, `dev`, `bots`, `extensions`. |
| `--allow-host <name>` | Another name this machine is reached by (repeatable). |
| `--allow-origin <url>` | A web page origin to accept (repeatable; none by default). |
| `--rate-limit <n>` | Requests per minute (default 120). |
| `--idle <minutes>` | End sessions whose client left without closing them (default 30). |
| `--rotate-token` | Make a new token; clients must be set up again. |

## Security

- Every request needs `Authorization: Bearer <token>`. The token is in `~/.craftwire/http.json` and is separate
  from the token games use. A token in the URL is never accepted.
- The `Host` header must name this machine, and requests that carry an `Origin` header (web pages) are refused
  unless you allow that origin. This stops a web page from reaching the hub through your browser.
- Requests are rate limited and limited to 4 MB.
- With `--host` set to anything but 127.0.0.1 (and `--allow-remote`), anyone who can reach the port and has the
  token can drive your games and servers, run scripts on the server (`server_eval`), and build and deploy
  plugins. Without TLS, the token crosses the network in clear text: prefer an SSH tunnel
  (`ssh -L 7777:127.0.0.1:7777 you@host`) or `--tls-cert`/`--tls-key`. Use `--read-only` for observers.

## Tests

While `craftwire serve` runs, `npx craftwire test` on the same machine runs its scenarios through it. To use a
hub on another machine: `npx craftwire test --hub https://host:7777/mcp` with the token in `CRAFTWIRE_TOKEN`.

## Teammates: one token each

The token in `~/.craftwire/http.json` is the owner's. Give each teammate their own instead, so the hub knows who
made every call and what they may do:

```
npx craftwire token add alice --role dev
npx craftwire token add ci --role bots
npx craftwire token add viewer            # read-only is the default
npx craftwire token list
npx craftwire token remove alice           # revoked at once, also for open sessions
```

| Role | May use |
|---|---|
| `read-only` | Every tool that changes nothing: screenshots, reads, logs, renders, status. |
| `bots` | The hub and bot tools (spawn, drive, load tests, dupe hunts, locale tours). |
| `dev` | Everything, as the owner. |

`--tools <groups>` narrows a role further (e.g. `--role dev --tools hub,server,dev`). A token is printed once;
only its SHA-256 hash is stored, in `~/.craftwire/auth/tokens.json`. A session belongs to the person who opened it:
another person's token on the same session id is refused.

Every call is written to the audit log (`~/.craftwire/logs/audit-<day>.jsonl`) with the person, the session, the
arguments and a preview of the result (images are summarised, never stored). Tokens never appear in it.

## Web panel

`craftwire serve` also serves a web panel at `http://127.0.0.1:7777/panel` (`--no-panel` turns it off). Sign in with
your token; the panel keeps you signed in for 12 hours with a cookie that holds no token, and checks your token
again on every request, so a revoked token stops working at once.

- **instances**: the connected clients, servers, bot runners and proxies, their versions and uptime.
- **bots**: every bot of every kind with its position, and a map of the area they are in.
- **logs**: one instance's recent log, stack traces folded.
- **client**: a client's screenshot and a live view (MJPEG, at most 5 frames a second, 4 viewers).
- **replay**: the sessions since the hub started, each call with its arguments, result and time, and
  **save as scenario** to download the session as a `.cwtest.json` (see scenario_record in the tool reference).
  Replay needs the `dev` role or the owner's token, since it shows everyone's arguments.

Each tab needs the role that offers the tool behind it (the map needs world_render, the live view screenshot).
Sign-in is rate limited and accepts only posts from the panel's own page.
