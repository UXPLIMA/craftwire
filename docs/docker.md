# Craftwire in Docker

`docker/compose.yml` runs a whole test setup in containers: the hub (`craftwire serve`, MCP over HTTP and the web
panel), a Paper server with the Craftwire plugin, and a bot runner for Java and Bedrock bots. It is what the
GitHub Action uses, and it works the same on a laptop or a build server.

```
./gradlew build                                   # the agent jars, when building from this checkout
EULA=true docker compose -f docker/compose.yml up --build
docker compose -f docker/compose.yml logs hub     # the MCP URL and how to connect
```

`EULA=true` says you accept the [Minecraft EULA](https://aka.ms/MinecraftEULA) for the server in the container;
without it the Paper service stops at once with a message. Craftwire never accepts it for you.

## What runs where

| Service | Image | Ports |
|---|---|---|
| `hub` | `docker/hub.Dockerfile` (Node 22) | 7777 (MCP at `/mcp`, panel at `/panel`), and Paper's 25565 |
| `paper` | `docker/paper.Dockerfile` (Temurin 25, Paper pinned by build number and sha256) | shares the hub's network |
| `botrunner` | `docker/botrunner.Dockerfile` (Temurin 25) | shares the hub's network |

Paper and the bot runner run in the hub's network namespace, so their agents reach the hub on 127.0.0.1 exactly as
on one machine, and the agent socket never leaves loopback. The `craftwire-home` volume holds `hub.json` and
`http.json` (the owner's token) for all three; every image runs as uid 1000 so they can share it. The world lives in
the `paper-data` volume.

The owner's token: `docker compose -f docker/compose.yml exec hub cat /craftwire/http.json`. Teammates get their own
with `docker compose -f docker/compose.yml exec hub node /opt/craftwire/dist/cli.js token add <name> --role dev`.

## Settings

| Variable | Default | |
|---|---|---|
| `EULA` | (none) | `true` to accept the Minecraft EULA; required. |
| `CRAFTWIRE_SOURCE` | `repo` | `repo` builds from this checkout (run `./gradlew build` first); `npm` installs a release. |
| `CRAFTWIRE_VERSION` | `latest` | The npm release with `CRAFTWIRE_SOURCE=npm`. |
| `MC_VERSION`, `PAPER_BUILD`, `PAPER_SHA256` | 26.3, 157, its hash | The Paper build; a download that does not match the hash fails the build. |
| `ONLINE_MODE` | `false` | Written to a new world's `server.properties`; bots and hidden clients join offline servers. |
| `PAPER_MEMORY` | `2G` | Paper's heap. |
| `CRAFTWIRE_HTTP_PORT`, `MINECRAFT_PORT` | 7777, 25565 | The host ports. |

## Hidden clients

Screenshots, recordings and the panel's live view need a game client. Add `docker/compose.client.yml`:

```
EULA=true docker compose -f docker/compose.yml -f docker/compose.client.yml up --build
```

The hub image then also carries Java, Xvfb and Mesa, and `client_process {action: "start"}` runs clients with
software rendering inside the hub container. They join the Paper server as offline players.

## Your plugin

Mount the jar into the Paper service with an override file:

```yaml
services:
  paper:
    volumes:
      - "./build/libs/my-plugin.jar:/data/plugins/my-plugin.jar:ro"
```

```
EULA=true docker compose -f docker/compose.yml -f my-plugin.yml up -d --build
npx craftwire test tests --hub http://127.0.0.1:7777/mcp --junit report.xml   # CRAFTWIRE_TOKEN from http.json
```

For CI, the GitHub Action does all of this: see [ci.md](ci.md).
