# Craftwire: project rules

Craftwire lets AI agents see and drive Minecraft: a TypeScript MCP hub (`hub/`), a Paper/Folia plugin
(`agent-paper/`), a Fabric client mod (`agent-fabric/`) and, from the `next` branch on, an external bot runner with
Java and Bedrock protocol bots. These rules apply to every change. The current roadmap and its decisions are in
`docs/superpowers/specs/2026-11-03-craftwire-next-design.md`.

## Working mode on `next`

1. Write all the code for the roadmap first, module after module, without stopping to compile or test.
2. Then compile everything and fix the build.
3. Then tests (the user explains the test approach at that point).
4. Then full end-to-end trials.

While writing without a compiler, be strict about correctness: check every Minecraft/Paper/Fabric API against the
mapped sources or existing usages in this repo before calling it, keep imports exact, and never leave stubs,
placeholders or "implement later" bodies. A file is done when it would compile.

## Language

- Code, identifiers, comments, docs, tool descriptions, error messages and commit messages: English.
- Conversation with the user: Turkish.

## Layout

Feature-first packages, never layer-first (`bot/gui`, `bot/path`, not `services/`, `utils/`, `managers/`).

| Module | Holds |
|---|---|
| `hub/` | MCP server, CLI, tools (`hub/src/tools`), hub-run processes (`hub/src/client`, `hub/src/dev`), HTTP (`hub/src/http`), web panel (`hub/src/panel`) |
| `protocol/` | Hub <-> agent wire protocol (`PROTOCOL.md`) and its fixtures |
| `agent-core/` | Java shared by all agents: connection, RPC, errors (`AgentError`), profiler |
| `bot-core/` | Bot model shared by every bot kind: actions, GUI click vocabulary, behaviors, pathfinder, world view interfaces |
| `agent-paper/` | Paper/Folia plugin; the in-server (plugin) bot lives in `paper/bot` on top of `bot-core` |
| `agent-fabric/` | Fabric client mod |
| `agent-velocity/` | Velocity proxy plugin: proxy commands, servers, players and switches for the hub |
| `botrunner/` | Standalone process for external bots: `java/` (vanilla-codec Java bots), `bedrock/` (Bedrock bots), shared world cache |
| `bedrock-protocol/` | Our own Bedrock protocol: `raknet/`, `crypto/`, `auth/`, `codec/`, `packet/` |
| `build-core/`, `agent-script/`, `mc-shared/`, `craftwire-api/` | Build engine, script sandbox, shared vanilla data, public extension API |
| `docker/` | Images and compose files |
| `versions/<mc>.properties`, `*/compat/<mc>/` | Everything that differs per Minecraft version (26.2, 26.3, 26.4) |

Version-specific code goes only into compat modules; common code compiles against the oldest supported version.
Package names: `com.uxplima.craftwire.<module>.<feature>`.

## Size limits (no god files)

- A source file: aim under 300 lines, never over 400. Split by responsibility, not by line count alone.
- One public top-level type per Java file.
- A method: aim under 40 lines.
- Exception: generated or table-like data (packet ID tables, registries), which goes in its own file with a comment
  saying so.
- Existing files over the limit (`fabric/video/Recorder.java`, `hub/src/client/client-manager.ts`) are split when they
  are next changed for a feature.

## Java style

- 4-space indent, max line length 150, UTF-8, LF.
- Classes `final` unless designed for extension; utility classes have a private constructor.
- Data carriers are `record`s; prefer immutable values and `List.of`/`Map.of`.
- No wildcard imports (except static imports in tests). Import order: static, then `com.uxplima`, then everything else
  alphabetically (as in the existing code).
- Errors that reach the agent are `AgentError(code, message, hint)`: `code` is UPPER_SNAKE, the message says what is
  wrong, the hint says what to do next.
- State which thread owns an object or method (server thread, region thread, render thread, bot runner loop) in its
  class comment when it matters; never touch world state off its owning thread.
- No reflection except in compat code that has no other way, with a comment why.

## TypeScript style

- `strict` TypeScript, ESM, imports end in `.js`.
- 2-space indent, double quotes, semicolons, max line length 150.
- Tool parameters are zod schemas; tool descriptions stay short (the hub has a tool-definition token budget).
- No `any` except at JSON boundaries, narrowed right away.

## Comments

- Explain why, not what: a constraint, a server quirk, a protocol rule, a measured number.
- Every class/module starts with a one-line comment (Javadoc `/** ... */` in Java, `/** ... */` in TS) that says what
  it is for and, where it matters, which thread runs it.
- No TODO, FIXME or commented-out code in the repo. Unknowns go into the design doc, not the code.
- Protocol code cites the packet or field it implements by its protocol name.

## Commits

- Conventional Commits: `type(scope): subject`, lowercase, imperative or descriptive, no trailing period.
- Types: `feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `build`, `release`.
- Scopes: `hub`, `paper`, `fabric`, `velocity`, `core`, `bot-core`, `botrunner`, `bedrock`, `panel`, `docker`, `build-core`,
  `protocol`, `mc` (or none for cross-cutting changes).
- Body wrapped at about 100 columns, explains why and what changed for the user; one logical change per commit.
- Work happens on `next`; commit when a module or a coherent feature is complete. Never push or open PRs unless asked.

## Docs

- Designs in `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`, plans in `docs/superpowers/plans/`.
- User guides in `docs/*.md`; the wire protocol in `protocol/PROTOCOL.md` (update it with every new method).
