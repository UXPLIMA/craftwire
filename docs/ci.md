# Craftwire in CI

## GitHub Action

The repository is also a GitHub Action. It starts the [Docker setup](docker.md) with your plugin in the Paper
server, runs your scenarios, optionally runs a short load test, uploads the reports and keeps one comment on the
pull request up to date.

```yaml
name: plugin tests
on: [pull_request]
permissions:
  contents: read
  pull-requests: write   # for the comment
jobs:
  craftwire:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 25 }
      - run: ./gradlew build
      - uses: uxplima/craftwire@v1
        with:
          plugin-jar: build/libs/my-plugin.jar
          eula: "true"          # you accept the Minecraft EULA (https://aka.ms/MinecraftEULA) for the test server
          scenarios: tests
          load-ramp: "10,50"    # optional
          max-mspt: "40"        # optional: fail when a step's p95 MSPT is above this
```

| Input | Default | |
|---|---|---|
| `plugin-jar` | (required) | The jar to test. |
| `eula` | (required) | `"true"` to accept the Minecraft EULA for the test server; nothing starts otherwise. |
| `scenarios` | `tests` | Scenario files or folders (`*.cwtest.json`). |
| `craftwire-version` | `latest` | The Craftwire release the images are built from. |
| `minecraft-version`, `paper-build`, `paper-sha256` | 26.3, 157, its hash | The server. |
| `load-ramp` | (none) | Bot counts for a load test after the scenarios, e.g. `10,50`. |
| `load-step-seconds` | 20 | Seconds per load step. |
| `load-kind` | `plugin` | `plugin`, or `java`/`bedrock` bots from the bot runner. |
| `max-mspt` | (none) | Fail when a load step's p95 MSPT is above this. |
| `comment` | `true` | Comment on the pull request. |

The `passed` output is `"true"` when every scenario passed and the load test stayed under `max-mspt`. The
`craftwire-reports` artifact holds the JUnit XML, the Markdown summary, the load report (HTML, Markdown, JSON) and
the containers' logs.

## Other CI systems

Everything the action does is a command:

```
EULA=true docker compose -f docker/compose.yml -f my-plugin.yml up -d --build
export CRAFTWIRE_TOKEN="$(docker compose -f docker/compose.yml exec -T hub node -e \
  'process.stdout.write(JSON.parse(require("fs").readFileSync("/craftwire/http.json","utf8")).token)')"
npx craftwire test tests --hub http://127.0.0.1:7777/mcp --wait 300 --junit report.xml --summary summary.md
npx craftwire load-test --hub http://127.0.0.1:7777/mcp --ramp 10,50 --out load.html --summary load.md --max-mspt 40
```

Without Docker, `npx craftwire test tests --server <dir> --junit report.xml` starts a local Paper server itself.

Exit codes: `craftwire test` 0 all passed, 1 a scenario failed, 2 the run could not start; `craftwire load-test` 0
done, 1 over `--max-mspt`, 2 could not start.
