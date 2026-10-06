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
