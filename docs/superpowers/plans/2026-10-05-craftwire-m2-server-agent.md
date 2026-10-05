# Craftwire M2 (Server Agent) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship milestone M2 of Craftwire: a Paper server plugin ("Craftwire") and the hub tools that drive it. With M2, Claude Code can run server commands and read their output, run JavaScript against the Bukkit API, read and edit the world (with undo snapshots), read server and client logs, check server health and toggle plugins.

**Architecture:** The new Gradle module `agent-paper/` embeds `agent-core` (already used by the Fabric agent), connects *out* to the hub with `agentKind: "server"` and runs every request on the thread that owns the data, through Paper's global and region schedulers. That is the main thread on Paper and the owning region on Folia. GraalJS is not shaded. Paper's library loader downloads it through `plugin.yml` `libraries`. The hub gains `server_*`, `world_*`, `plugin_manage` and `logs` tools. A shared log4j appender in `agent-core` streams log lines from both agents as `log` events.

**Tech Stack:**
- Hub: unchanged (Node ≥ 20, TypeScript 5.9, `@modelcontextprotocol/sdk` 1.32, `ws` 8, `zod` 4, Vitest 5).
- Java: Java 25 (`release 25`), Gradle 9.7.1 wrapper.
- Paper: Paper API `26.2.build.130-stable`; integration tests run Paper 26.2 build 130.
- Scripting: GraalJS 25.0.4 (`org.graalvm.polyglot:polyglot`, `org.graalvm.js:js-language`).
- Libraries: log4j-core 2.26.0 (compile only; Minecraft and Paper ship it), JUnit 5.13, Java-WebSocket 1.6 (tests only).

**Spec:** `docs/superpowers/specs/2026-10-05-craftwire-design.md`. This plan covers spec §9 milestone **M2**: `server_command`, `server_eval`, `world_query`, `world_edit`, `server_info`, `logs` and `plugin_manage`. It also covers the M2 slices of §3 Protocol, §5 Security, §6 Errors, §7 Testing and §8 Distribution. M3 (dev loop) and M4 (bots) get their own plans.

**Branch:** `feat/m2-server-agent`, created from `feat/m1-core-client`, because M1 is not merged to `main` yet.

## Global Constraints

**Versions and runtime**
- Minecraft target is **26.2**. Paper API is `26.2.build.130-stable`. Java bytecode uses `release = 25`.
- Hub runtime dependencies stay limited to `@modelcontextprotocol/sdk`, `ws` and `zod` (spec §2).
- Plugin jar contents: the plugin jar bundles the `agent-core` classes. Gson and log4j come from Paper.
- GraalJS 25.0.4 is declared in `plugin.yml` `libraries:` and is never put in the jar (spec §4 "Script environment", §10).
- Plugin identity: name `Craftwire`, main class `com.uxplima.craftwire.paper.CraftwirePlugin`, jar `craftwire-paper-<version>.jar`, MIT license.

**Security and config**
- Network: the plugin opens no ports. It reads `~/.craftwire/hub.json` (or `$CRAFTWIRE_HOME/hub.json`) and connects to `127.0.0.1` (spec §5).
- Config defaults are `allow-eval: true`, `allow-world-edit: true`, `allow-bots: true` and `max-edit-volume: 1000000` (spec §5).
- A disabled capability returns `PERMISSION_DISABLED` (spec §5).
- Startup warning, logged as WARN and exact text: `Craftwire is active — do not run on production servers` (spec §5).
- `world_edit` takes an automatic snapshot before any edit larger than **32,768** blocks, and the result returns its id (spec §5).

**Scripting**
- `server_eval`: `timeoutMs` defaults to **5000** (max 60000).
- Globals: `server`, `player(name)`, `plugin(name)`, `loc(x, y, z, world?)`, `Java.type`, `print`.
- Bindings persist per hub session until `reset: true`.
- A watchdog cancels runaway scripts (spec §4).

**Threading and protocol**
- Server actions run through `Sync` (global/region schedulers). A handler never blocks the server thread waiting on the network, and a handler exception never reaches the game loop (spec §3, §6).
- Protocol stays `protocolVersion = 1`. M2 only adds methods and event types.
- Error shape stays `{ code, message, hint }`. New codes: `UNKNOWN_COMMAND`, `COMMAND_FAILED`, `PLAYER_NOT_FOUND`, `WORLD_NOT_FOUND`, `QUERY_TOO_LARGE`, `EDIT_TOO_LARGE`, `SNAPSHOT_NOT_FOUND`, `SCHEMATIC_NOT_FOUND`, `PLUGIN_NOT_FOUND` and `CANNOT_DISABLE_SELF`. `PERMISSION_DISABLED`, `EVAL_ERROR` and `TIMEOUT` are used as defined in spec §6.

**Releases**
- Lockstep version: every component moves to **0.2.0** at the end of M2 (spec §8).
- Local builds need `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1"` (the system JDK is 21). CI uses Temurin 25.

## Deviations from spec (deliberate, flag in review)

1. **run-paper is not used.** Spec §7 says the Paper tests run against "a real Paper server launched by Gradle (run-paper)".
   - run-paper's `runServer` is an interactive dev task; tests need to start, drive and stop the server from code.
   - Instead, the `integrationTest` Gradle task runs JUnit. JUnit downloads the pinned Paper build through the Fill v3 API (with a sha256 check), launches it and acts as the hub.
   - The tests are still launched by Gradle against a real Paper server.
2. **Schematic format.** `save_schematic` / `paste_schematic` read and write vanilla structure files (`.nbt`, Bukkit Structure API) in `plugins/Craftwire/structures/`, not WorldEdit `.schem`.
   - This avoids a WorldEdit dependency. Snapshots use the same format.
3. **Folia.** All code goes through the global/region schedulers, so single-region work is Folia-correct. Snapshot and schematic placement run on the region of the box's minimum corner, which is not safe on Folia when a box spans regions.
   - No Folia server is in the test matrix. `plugin.yml` therefore says `folia-supported: false` until Folia is verified.
4. **The spec §7 end-to-end layer is deferred to M3** (server + client + hub with a fixture plugin opening a GUI). It needs `server_process` (M3) to start the server from the hub without a human.
   - M2 adds `test-fixtures/` with a minimal Paper plugin that the integration tests use.
   - The real hub ↔ real Paper path is verified manually in Task 11.
5. **Chat and join events are not covered by automated tests.** `EventBridge` (player join/quit, chat) needs a connected client, so it is verified manually in Task 11.

## Review Focus

1. **A runaway or chatty script.** For example `while(true){}`, or `print` in a tight loop.
   - Expected: the server thread is released at `timeoutMs`, printed output is capped at 64 KiB, the result is `TIMEOUT` with a hint, globals are reset, and the next eval works.
   - Owned by Task 5: unit tests `infiniteLoopTimesOutAndResetsGlobals` and `outputIsCapped`, and IT `runawayScriptIsCancelledAndTheServerKeepsTicking`.
2. **Queries and edits far from spawn (unloaded chunks) and across chunk borders.**
   - Expected: chunks are loaded asynchronously first, each chunk's part runs on its owner, and results are complete and ordered.
   - Owned by Tasks 6 and 7, with ITs at x ≥ 1000 and blocks at x = 1007/1008, which sit in two different chunks.
3. **Plugins that answer a command a tick or more later.** Expected: `server_command` keeps collecting feedback for `collectMs` (default 250 ms). Owned by Task 4: IT `lateFeedbackIsCollected` with the fixture plugin.
4. **The hub restarts mid-session** (Claude Code restarted).
   - Expected: on the new connection the agent replays its log backlog to the new instance, and eval globals start fresh.
   - Owned by Task 3 (IT `reconnectReplaysTheBacklogToTheNewSession`) and Task 5 (IT `globalsResetWhenTheHubReconnects`).
5. **Undoing big edits and unsafe names.**
   - Expected: a large edit returns a `snapshotId`, and `restore` puts back exactly what was there, air included.
   - Schematic names cannot escape `plugins/Craftwire/structures/`.
   - Owned by Task 7: IT `largeFillTakesASnapshotThatRestores` and unit test `namesRejectTraversal`.

## File Structure

```
craftwire/
├─ settings.gradle                     + agent-paper, test-fixtures
├─ gradle.properties                   + paper_api_version, paper_build, paper_sha256, graal_version, log4j_version
├─ agent-core/src/main/java/…/core/
│  ├─ LogCapture.java                  log4j appender → backlog + live sink (both agents)
│  └─ HubClient.java                   + notifyEvent(type, data, time)
├─ agent-paper/
│  ├─ build.gradle                     jar bundles agent-core; integrationTest task
│  ├─ src/main/resources/plugin.yml, config.yml
│  ├─ src/main/java/com/uxplima/craftwire/paper/
│  │  ├─ CraftwirePlugin.java          composition root: config, log capture, hub client, handlers
│  │  ├─ AgentConfig.java              config.yml → record, PERMISSION_DISABLED
│  │  ├─ Args.java                     JSON parameter helpers, world lookup
│  │  ├─ Sync.java                     global/region scheduler → CompletableFuture
│  │  ├─ EventBridge.java              join/quit/chat → hub events
│  │  ├─ handlers/ Handlers, ServerInfoHandler, PluginJson, CommandHandler, EvalHandler,
│  │  │            WorldQueryHandler, WorldEditHandler, PluginManageHandler
│  │  ├─ script/   ScriptEngine, ValueJson, CapturedOutput
│  │  └─ world/    Box, ChunkWork, Blocks, BlockMatcher, Placement, SnapshotStore
│  ├─ src/test/java/…                  unit tests (no server)
│  └─ src/integrationTest/java/…/it/   ItEnv, ItHub, PaperServer, PaperDownload, ItSessionListener, *IT
├─ test-fixtures/                      CraftwireFixture Paper plugin (test-only)
├─ hub/src/
│  ├─ agents.ts                        + resolveAny(); server NO_INSTANCE hint
│  ├─ tools/registry.ts                + forward(c, kind, …) shared by client and server tools
│  ├─ tools/server-tools.ts            server_command, server_eval, world_query, world_edit, server_info, plugin_manage
│  ├─ tools/log-tools.ts               logs (+ groupLogs)
│  └─ server.ts                        registers the new tools
├─ protocol/PROTOCOL.md, fixtures/valid-event-log.json
├─ claude-plugin/skills/craftwire/SKILL.md
└─ .github/workflows/ci.yml            + agent-paper unit tests, paper-it job
```

---

### Task 1: Log capture in agent-core

**Files:**
- Modify: `gradle.properties`, `agent-core/build.gradle`
- Create: `agent-core/src/main/java/com/uxplima/craftwire/core/LogCapture.java`
- Modify: `agent-core/src/main/java/com/uxplima/craftwire/core/HubClient.java`
- Create: `agent-core/src/test/resources/log4j2-test.xml`
- Test: `agent-core/src/test/java/com/uxplima/craftwire/core/LogCaptureTest.java`, `HubClientTest.java`

**Interfaces:**
- Produces: `LogCapture.install(int capacity) → LogCapture`, `attach(BiConsumer<JsonObject, Long> sink)`, `detach()`, `backlog() → List<LogCapture.Entry>`, `uninstall()`. `Entry(long time, JsonObject data)`; data = `{level, logger, thread, message, thrown?}`.
- Produces: `HubClient.notifyEvent(String type, JsonObject data, long time)`.

- [ ] **Step 1: Add the log4j dependency**

`gradle.properties`, append:

```properties
log4j_version=2.26.0
```

`agent-core/build.gradle`, in `dependencies` after the Gson `compileOnly` line:

```groovy
    // log4j-core ships with Minecraft and Paper; LogCapture plugs into it, never bundle it.
    compileOnly "org.apache.logging.log4j:log4j-core:${log4j_version}"
```

and after `testImplementation 'com.google.code.gson:gson:2.13.2'`:

```groovy
    testImplementation "org.apache.logging.log4j:log4j-core:${log4j_version}"
```

`agent-core/src/test/resources/log4j2-test.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN">
  <Loggers>
    <Root level="info"/>
  </Loggers>
</Configuration>
```

- [ ] **Step 2: Write the failing tests**

`agent-core/src/test/java/com/uxplima/craftwire/core/LogCaptureTest.java`:

```java
package com.uxplima.craftwire.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LogCaptureTest {
    final Logger log = LogManager.getLogger("craftwire-test");
    LogCapture capture;

    @AfterEach
    void tearDown() {
        if (capture != null) capture.uninstall();
    }

    static List<String> messages(List<LogCapture.Entry> entries) {
        return entries.stream()
                .filter(e -> e.data().get("logger").getAsString().equals("craftwire-test"))
                .map(e -> e.data().get("message").getAsString())
                .toList();
    }

    @Test
    void keepsInfoAndAboveWithLevelAndLogger() {
        capture = LogCapture.install(10);
        log.debug("quiet");
        log.info("hello");
        log.warn("careful");
        List<LogCapture.Entry> b = capture.backlog();
        assertEquals(List.of("hello", "careful"), messages(b));
        assertEquals("INFO", b.get(0).data().get("level").getAsString());
        assertEquals("WARN", b.get(1).data().get("level").getAsString());
        assertTrue(b.get(0).time() > 0);
    }

    @Test
    void backlogIsBounded() {
        capture = LogCapture.install(3);
        for (int i = 0; i < 5; i++) log.info("m{}", i);
        assertEquals(List.of("m2", "m3", "m4"), messages(capture.backlog()));
    }

    @Test
    void attachReplaysBacklogThenStreamsUntilDetached() {
        capture = LogCapture.install(10);
        log.info("before");
        List<String> got = new ArrayList<>();
        capture.attach((d, t) -> got.add(d.get("message").getAsString()));
        log.info("during");
        capture.detach();
        log.info("after");
        assertEquals(List.of("before", "during"), got);
    }

    @Test
    void rendersThrownStackTraces() {
        capture = LogCapture.install(10);
        log.error("broke", new IllegalStateException("bad state"));
        JsonObject d = capture.backlog().get(0).data();
        assertEquals("ERROR", d.get("level").getAsString());
        assertTrue(d.get("thrown").getAsString().contains("IllegalStateException: bad state"), d.toString());
    }

    @Test
    void loggingFromInsideTheSinkDoesNotRecurse() {
        capture = LogCapture.install(10);
        List<String> got = new ArrayList<>();
        capture.attach((d, t) -> {
            got.add(d.get("message").getAsString());
            log.info("echo of {}", d.get("message").getAsString());
        });
        log.info("outer");
        assertEquals(List.of("outer"), got);
        assertEquals(List.of("outer"), messages(capture.backlog()));
    }

    @Test
    void aFailingSinkDoesNotBreakLogging() {
        capture = LogCapture.install(10);
        capture.attach((d, t) -> { throw new IllegalStateException("socket gone"); });
        assertDoesNotThrow(() -> log.info("still logged"));
        assertEquals(List.of("still logged"), messages(capture.backlog()));
    }
}
```

Append to `HubClientTest.java` (inside the class):

```java
    @Test
    void notifyEventKeepsTheGivenTimestamp() throws Exception {
        int port = TestHub.freePort();
        hub = new TestHub(port, TOKEN).startAndWait();
        clientFor(new AtomicReference<>(new HubConfig(port, TOKEN)));
        hub.next();   // hello
        assertTrue(connected.await(5, TimeUnit.SECONDS));
        JsonObject d = new JsonObject();
        d.addProperty("message", "replayed");
        client.notifyEvent("log", d, 1234L);
        JsonObject ev = hub.next();
        assertEquals("log", ev.getAsJsonObject("params").get("type").getAsString());
        assertEquals(1234L, ev.getAsJsonObject("params").get("time").getAsLong());
    }
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-core:test --console=plain`
Expected: compilation FAILS with `cannot find symbol: class LogCapture` and `notifyEvent(String,JsonObject,long)`.

- [ ] **Step 4: Implement LogCapture and the HubClient overload**

`agent-core/src/main/java/com/uxplima/craftwire/core/LogCapture.java`:

```java
package com.uxplima.craftwire.core;

import com.google.gson.JsonObject;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.BiConsumer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * Copies INFO-and-above log4j events into a bounded backlog and, while attached, to the hub as {@code log} events.
 * Minecraft and Paper both ship log4j-core, so this adds no runtime dependency.
 */
public final class LogCapture extends AbstractAppender {
    public record Entry(long time, JsonObject data) {}

    static final int MAX_TEXT = 8_192;
    private static final ThreadLocal<Boolean> INSIDE = ThreadLocal.withInitial(() -> false);

    private final int capacity;
    private final ArrayDeque<Entry> backlog = new ArrayDeque<>();
    private BiConsumer<JsonObject, Long> sink;

    private LogCapture(int capacity) {
        super("craftwire-capture", null, null, true, Property.EMPTY_ARRAY);
        this.capacity = capacity;
    }

    /** Attaches a capture to the root logger. Call as early as possible so startup lines are kept for replay. */
    public static LogCapture install(int capacity) {
        LogCapture capture = new LogCapture(capacity);
        capture.start();
        ((Logger) LogManager.getRootLogger()).addAppender(capture);
        return capture;
    }

    public void uninstall() {
        ((Logger) LogManager.getRootLogger()).removeAppender(this);
        stop();
    }

    @Override
    public void append(LogEvent event) {
        if (!event.getLevel().isMoreSpecificThan(Level.INFO) || INSIDE.get()) return;
        INSIDE.set(true);   // a sink that logs (e.g. a network error) must not recurse into itself
        try {
            record(event.getInstant().getEpochMillisecond(), toJson(event));
        } finally {
            INSIDE.set(false);
        }
    }

    static JsonObject toJson(LogEvent e) {
        JsonObject d = new JsonObject();
        d.addProperty("level", e.getLevel().name());
        d.addProperty("logger", e.getLoggerName() == null ? "" : e.getLoggerName());
        d.addProperty("thread", e.getThreadName());
        d.addProperty("message", cut(e.getMessage() == null ? "" : e.getMessage().getFormattedMessage()));
        if (e.getThrown() != null) {
            StringWriter w = new StringWriter();
            e.getThrown().printStackTrace(new PrintWriter(w));
            d.addProperty("thrown", cut(w.toString()));
        }
        return d;
    }

    private static String cut(String s) {
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT) + "…";
    }

    private synchronized void record(long time, JsonObject data) {
        backlog.addLast(new Entry(time, data));
        if (backlog.size() > capacity) backlog.removeFirst();
        if (sink == null) return;
        try {
            sink.accept(data, time);
        } catch (RuntimeException ignored) {
            // the hub link is best effort; logging must never fail because of it
        }
    }

    /** Replays the backlog to {@code sink}, then forwards every new entry until {@link #detach()}. */
    public synchronized void attach(BiConsumer<JsonObject, Long> sink) {
        for (Entry e : backlog) {
            try {
                sink.accept(e.data(), e.time());
            } catch (RuntimeException ignored) {
                // same as record(): never let the hub link break the caller
            }
        }
        this.sink = sink;
    }

    public synchronized void detach() {
        sink = null;
    }

    public synchronized List<Entry> backlog() {
        return List.copyOf(backlog);
    }
}
```

In `HubClient.java`, replace `notifyEvent(String type, JsonObject data)` with:

```java
    public void notifyEvent(String type, JsonObject data) {
        notifyEvent(type, data, System.currentTimeMillis());
    }

    public void notifyEvent(String type, JsonObject data, long time) {
        WebSocket ws = socket;
        if (ws != null && instanceId != null) send(ws, RpcCodec.event(type, data, time));
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-core:test --console=plain`
Expected: BUILD SUCCESSFUL, all agent-core tests pass (25 existing + 7 new).

- [ ] **Step 6: Commit**

```bash
git add gradle.properties agent-core
git commit -m "feat(core): LogCapture streams log4j lines with a replayable backlog"
```

---

### Task 2: Hub server tools and logs

**Files:**
- Modify: `hub/src/agents.ts` (add `resolveAny`, better server hint)
- Modify: `hub/src/tools/registry.ts` (add `forward`)
- Modify: `hub/src/tools/client-tools.ts` (use shared `forward`)
- Create: `hub/src/tools/server-tools.ts`, `hub/src/tools/log-tools.ts`
- Modify: `hub/src/server.ts`
- Create: `protocol/fixtures/valid-event-log.json`
- Test: `hub/test/server-tools.test.ts`, `hub/test/log-tools.test.ts`

**Interfaces:**
- Produces (hub → server agent methods): `server.command {command, asPlayer?, collectMs}`, `server.eval {code, timeoutMs, reset, at?}`, `world.query {action, …}`, `world.edit {action, …}`, `server.info {}`, `plugin.manage {action, name?}`. Every method may also carry `operationId`.
- Consumes (agent → hub events): `log {level, logger, thread, message, thrown?}`, `player {action: "join"|"quit", name, uuid}`, `chat {text, kind: "chat", sender}`.
- Produces: `forward(c, kind, method, args, timeoutMs?)` in `registry.ts`; `AgentServer.resolveAny(selector?)`; `groupLogs(events)` in `log-tools.ts`.

- [ ] **Step 1: Write the failing tests**

`hub/test/server-tools.test.ts`:

```ts
import { afterEach, describe, expect, it } from "vitest";
import { connectFakeAgent } from "./helpers/fakeAgent.js";
import { json, startHub, TOKEN } from "./helpers/hub.js";

let hub: Awaited<ReturnType<typeof startHub>> | undefined;
afterEach(async () => { await hub?.close(); hub = undefined; });

async function withServer() {
  hub = await startHub();
  const agent = await connectFakeAgent(hub.port, { token: TOKEN, kind: "server", name: "server" });
  return { hub, agent };
}

describe("server tools", () => {
  const forwards: Array<[string, string, Record<string, unknown>]> = [
    ["server_command", "server.command", { command: "time query gametime" }],
    ["server_eval", "server.eval", { code: "1 + 1" }],
    ["world_query", "world.query", { action: "block", x: 1, y: 2, z: 3 }],
    ["world_edit", "world.edit", { action: "fill", min: { x: 0, y: 0, z: 0 }, max: { x: 1, y: 1, z: 1 }, block: "stone" }],
    ["server_info", "server.info", {}],
    ["plugin_manage", "plugin.manage", { action: "list" }],
  ];

  for (const [tool, method, args] of forwards) {
    it(`${tool} forwards to ${method}`, async () => {
      const { hub, agent } = await withServer();
      agent.onRequest(method, (p) => ({ got: p }));
      const res = await hub.call(tool, args);
      expect(res.isError).toBeFalsy();
      expect(json(res)).toEqual({ got: expect.objectContaining(args) });
    });
  }

  it("applies parameter defaults before forwarding", async () => {
    const { hub, agent } = await withServer();
    agent.onRequest("server.command", (p) => p);
    agent.onRequest("server.eval", (p) => p);
    expect(json(await hub.call("server_command", { command: "list" }))).toEqual({ command: "list", collectMs: 250 });
    expect(json(await hub.call("server_eval", { code: "1" }))).toEqual({ code: "1", timeoutMs: 5000, reset: false });
  });

  it("server_eval waits for the script's own timeout, not the default request timeout", async () => {
    const { hub, agent } = await withServer();
    agent.onRequest("server.eval", async () => {
      await new Promise((r) => setTimeout(r, 2500));   // longer than the test hub's 2000 ms request timeout
      return { result: 1, output: "" };
    });
    const res = await hub.call("server_eval", { code: "slow()", timeoutMs: 3000 });
    expect(res.isError).toBeFalsy();
  }, 10_000);

  it("server tools never route to a client", async () => {
    hub = await startHub();
    await connectFakeAgent(hub.port, { token: TOKEN, kind: "client" });
    expect(json(await hub.call("server_info"))).toMatchObject({ code: "NO_INSTANCE", hint: expect.stringContaining("Craftwire plugin") });
  });

  it("world_edit rejects schematic names that could escape the folder", async () => {
    const { hub } = await withServer();
    const res = await hub.call("world_edit", { action: "save_schematic", name: "../evil", min: { x: 0, y: 0, z: 0 }, max: { x: 0, y: 0, z: 0 } });
    expect(res.isError).toBe(true);
  });
});
```

`hub/test/log-tools.test.ts`:

```ts
import { afterEach, describe, expect, it, vi } from "vitest";
import { connectFakeAgent } from "./helpers/fakeAgent.js";
import { json, startHub, TOKEN } from "./helpers/hub.js";

let hub: Awaited<ReturnType<typeof startHub>> | undefined;
afterEach(async () => { await hub?.close(); hub = undefined; });

const messages = (r: { lines: Array<{ message: string }> }) => r.lines.map((l) => l.message);

describe("logs", () => {
  it("folds stack lines into the line above and filters by level, text and limit", async () => {
    hub = await startHub();
    const agent = await connectFakeAgent(hub.port, { token: TOKEN, kind: "server", name: "server" });
    agent.emit("log", { level: "INFO", logger: "Minecraft", message: "Done (3.1s)!" });
    agent.emit("log", { level: "WARN", logger: "", message: "java.lang.IllegalStateException: boom" });
    agent.emit("log", { level: "WARN", logger: "", message: "\tat com.example.Foo.bar(Foo.java:10)" });
    agent.emit("log", { level: "WARN", logger: "", message: "Caused by: java.io.IOException: disk" });
    agent.emit("log", { level: "ERROR", logger: "Shop", message: "Could not save", thrown: "java.io.IOException: disk\n\tat x" });
    const h = hub;
    await vi.waitFor(() => expect(h.agents.events(agent.instanceId)).toHaveLength(5));

    const all = json(await hub.call("logs"));
    expect(all.instance).toBe(agent.instanceId);
    expect(all.lines).toHaveLength(3);
    expect(all.lines[1]).toMatchObject({
      level: "WARN",
      message: "java.lang.IllegalStateException: boom",
      stack: ["at com.example.Foo.bar(Foo.java:10)", "Caused by: java.io.IOException: disk"],
    });
    expect(messages(json(await hub.call("logs", { level: "ERROR" })))).toEqual(["Could not save"]);
    expect(messages(json(await hub.call("logs", { contains: "FOO.JAVA" })))).toEqual(["java.lang.IllegalStateException: boom"]);
    expect(messages(json(await hub.call("logs", { limit: 1 })))).toEqual(["Could not save"]);
  });

  it("defaults to the server when a client is connected too", async () => {
    hub = await startHub();
    const client = await connectFakeAgent(hub.port, { token: TOKEN, kind: "client" });
    const server = await connectFakeAgent(hub.port, { token: TOKEN, kind: "server", name: "server" });
    client.emit("log", { level: "INFO", logger: "c", message: "client line" });
    server.emit("log", { level: "INFO", logger: "s", message: "server line" });
    const h = hub;
    await vi.waitFor(() => expect(h.agents.events(server.instanceId)).toHaveLength(1));
    const res = json(await hub.call("logs"));
    expect(res.instance).toBe(server.instanceId);
    expect(messages(res)).toEqual(["server line"]);
    expect(messages(json(await hub.call("logs", { instance: client.instanceId })))).toEqual(["client line"]);
  });

  it("asks for an instance when several clients and no server are connected", async () => {
    hub = await startHub();
    await connectFakeAgent(hub.port, { token: TOKEN, kind: "client", name: "A" });
    await connectFakeAgent(hub.port, { token: TOKEN, kind: "client", name: "B" });
    expect(json(await hub.call("logs"))).toMatchObject({ code: "AMBIGUOUS_INSTANCE" });
  });

  it("reports NO_INSTANCE when nothing is connected", async () => {
    hub = await startHub();
    expect(json(await hub.call("logs"))).toMatchObject({ code: "NO_INSTANCE" });
  });

  it("wait_for log_match matches server log messages", async () => {
    hub = await startHub();
    const agent = await connectFakeAgent(hub.port, { token: TOKEN, kind: "server" });
    const pending = hub.call("wait_for", { condition: "log_match", pattern: "^Done \\(", timeoutMs: 3000 });
    setTimeout(() => agent.emit("log", { level: "INFO", logger: "Minecraft", message: "Done (4.5s)! For help, type \"help\"" }), 50);
    expect(json(await pending)).toMatchObject({ matched: true, event: { type: "log" } });
  });
});
```

`protocol/fixtures/valid-event-log.json`:

```json
{"jsonrpc":"2.0","method":"event","params":{"type":"log","time":1759670000000,"data":{"level":"INFO","logger":"net.minecraft.server.MinecraftServer","thread":"Server thread","message":"Done (4.500s)! For help, type \"help\""}}}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd hub && npx vitest run test/server-tools.test.ts test/log-tools.test.ts`
Expected: FAIL. The tool calls return MCP errors such as `Tool server_command not found` and `Tool logs not found`. `wait_for log_match` already passes because M1 implemented it.

- [ ] **Step 3: Implement**

`hub/src/agents.ts`. In `resolve()`, replace the `ofKind.length === 0` line with:

```ts
    if (ofKind.length === 0) {
      throw new CraftwireError("NO_INSTANCE", `No ${label} is connected`, kind === "client"
        ? "Start Minecraft with the Craftwire Agent mod installed; it connects automatically."
        : "Start the Paper server with the Craftwire plugin installed; it connects automatically.");
    }
```

and add after `resolve()`:

```ts
  /** Any instance: by selector, else the only server, else the only instance. */
  resolveAny(selector?: string): InstanceInfo {
    const all = this.instances();
    if (selector) {
      const s = selector.toLowerCase();
      const hit = all.find((i) => i.id === selector) ?? all.find((i) => i.name.toLowerCase() === s);
      if (!hit) throw new CraftwireError("NO_INSTANCE", `No instance matches "${selector}"`, "Call list_instances to see connected instances.");
      return hit;
    }
    const servers = all.filter((i) => i.kind === "server");
    if (servers.length === 1) return servers[0]!;
    if (all.length === 1) return all[0]!;
    if (all.length === 0) throw new CraftwireError("NO_INSTANCE", "Nothing is connected", "Start a Paper server with the Craftwire plugin, or Minecraft with the Craftwire Agent mod.");
    throw new CraftwireError("AMBIGUOUS_INSTANCE", `${all.length} instances are connected`, `Pass instance: one of ${all.map((i) => `${i.id} (${i.name})`).join(", ")}.`);
  }
```

`hub/src/tools/registry.ts`. Add the import `import type { AgentKind, AgentServer } from "../agents.js";` (replacing the `AgentServer`-only import) and append:

```ts
/** Routes a tool call to one agent of `kind`; `instance` picks it, `operationId` makes retries idempotent. */
export async function forward(c: ToolContext, kind: AgentKind, method: string, args: Record<string, unknown>, timeoutMs?: number): Promise<unknown> {
  const { instance, operationId, ...params } = args as { instance?: string; operationId?: string } & Record<string, unknown>;
  const inst = c.agents.resolve(kind, instance);
  const payload = operationId ? { ...params, operationId } : params;
  return c.ops.run(operationId, () => c.agents.request(inst.id, method, payload, timeoutMs));
}
```

`hub/src/tools/client-tools.ts`:
- Delete the local `async function forward(...)`.
- Import `forward` from `./registry.js`.
- Replace the three `forward(c, ` calls with `forward(c, "client", `. `fwd` becomes:

```ts
const fwd = (method: string, timeoutMs?: number) => async (args: Record<string, unknown>, c: ToolContext) =>
  ok(await forward(c, "client", method, args, timeoutMs));
```

`hub/src/tools/server-tools.ts`:

```ts
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { defineTool, forward, ok, targetArgs, type ToolContext } from "./registry.js";

const pos = z.object({ x: z.number().int(), y: z.number().int(), z: z.number().int() });
const world = z.string().optional().describe("World name. Defaults to the main world.");

const call = (method: string, timeoutMs?: number) => async (args: Record<string, unknown>, c: ToolContext) =>
  ok(await forward(c, "server", method, args, timeoutMs));

export function registerServerTools(server: McpServer, ctx: ToolContext): void {
  defineTool(server, ctx, "server_command",
    "Run a server command as the console and return its feedback lines. The leading / is optional. Feedback a plugin sends up to collectMs after the command returns is included. asPlayer runs it as that online player instead (feedback then goes to the player's chat). Use the minecraft: prefix when a plugin overrides a vanilla command.",
    {
      ...targetArgs,
      command: z.string().min(1).max(32_000),
      asPlayer: z.string().optional(),
      collectMs: z.number().int().min(0).max(5000).default(250),
    }, call("server.command", 15_000));

  defineTool(server, ctx, "server_eval",
    "Run JavaScript (GraalJS) on the server thread with full Bukkit/Paper API access. Globals: server, player(name), plugin(name), loc(x,y,z,world?), Java.type('fully.qualified.Class'), print(...). The last expression is returned as JSON (Java objects as {class,toString}); print output comes back in `output`. Globals persist until reset:true, a timeout, or a hub reconnect; store them on globalThis (top-level let/const cannot be re-declared on the next run).",
    {
      ...targetArgs,
      code: z.string().min(1),
      timeoutMs: z.number().int().min(100).max(60_000).default(5000),
      reset: z.boolean().default(false),
      at: z.object({ world, x: z.number(), z: z.number() }).optional().describe("Run on the region that owns this position (Folia). Not needed on Paper."),
    },
    async (args, c) => ok(await forward(c, "server", "server.eval", args, args.timeoutMs + 5000)));

  defineTool(server, ctx, "world_query",
    "Read the world. block: one block at x,y,z. region: every block in min..max (inclusive, at most 32768) as palette + blocks (palette indices, x fastest, then z, then y) + counts. entities: entities in min..max, optional type (e.g. 'zombie'). players: online players. find_block: positions of `block` (an id like 'chest' or a state like 'oak_stairs[facing=east]') in min..max, up to limit.",
    {
      ...targetArgs,
      action: z.enum(["block", "region", "entities", "players", "find_block"]),
      world,
      x: z.number().int().optional(), y: z.number().int().optional(), z: z.number().int().optional(),
      min: pos.optional(), max: pos.optional(),
      type: z.string().optional(),
      block: z.string().optional(),
      limit: z.number().int().min(1).max(1000).default(100),
    }, call("world.query", 30_000));

  defineTool(server, ctx, "world_edit",
    "Change the world. set_blocks: blocks [{x,y,z,block}] (up to 10000). fill: min..max with block, only where `replace` matches if given. snapshot: save min..max and return an id; restore: put snapshot `id` back. save_schematic: save min..max as structure `name`; paste_schematic: place it with its origin at `at`, optionally rotated/mirrored around that point. Edits over 32768 blocks take a snapshot first and return its snapshotId. Block states use Minecraft syntax, e.g. 'oak_stairs[facing=east]'.",
    {
      ...targetArgs,
      action: z.enum(["set_blocks", "fill", "snapshot", "restore", "save_schematic", "paste_schematic"]),
      world,
      blocks: z.array(pos.extend({ block: z.string() })).max(10_000).optional(),
      min: pos.optional(), max: pos.optional(),
      block: z.string().optional(),
      replace: z.string().optional(),
      id: z.string().optional(),
      name: z.string().regex(/^[A-Za-z0-9_-]{1,64}$/).optional(),
      at: pos.optional(),
      rotation: z.enum(["none", "clockwise_90", "clockwise_180", "counterclockwise_90"]).default("none"),
      mirror: z.enum(["none", "left_right", "front_back"]).default("none"),
      includeEntities: z.boolean().default(false),
      physics: z.boolean().default(false),
    }, call("world.edit", 120_000));

  defineTool(server, ctx, "server_info",
    "Server health and metadata: TPS (1/5/15 min), MSPT, memory, versions, online players, worlds and plugins.",
    { ...targetArgs }, call("server.info"));

  defineTool(server, ctx, "plugin_manage",
    "list: installed plugins with version and state. info: details for `name` (version, authors, depends, commands). enable/disable: toggle `name` at runtime (not Craftwire itself). Toggling is for quick checks; restart the server for a clean state.",
    { ...targetArgs, action: z.enum(["list", "info", "enable", "disable"]), name: z.string().optional() },
    call("plugin.manage"));
}
```

`hub/src/tools/log-tools.ts`:

```ts
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import type { AgentEvent } from "../protocol.js";
import { defineTool, ok, type ToolContext } from "./registry.js";

const LEVELS = ["TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL"] as const;
const rank = (level: string) => {
  const i = LEVELS.indexOf(level as (typeof LEVELS)[number]);
  return i === -1 ? 2 : i;
};
// Lines the JVM prints one by one when a stack trace goes through System.err.
const CONTINUATION = /^(\s+at |\s*Caused by: |\s*Suppressed: |\s+\.\.\. \d+ more)/;

export interface LogLine {
  time: number;
  level: string;
  logger: string;
  message: string;
  thrown?: string;
  stack?: string[];
}

export function groupLogs(events: AgentEvent[]): LogLine[] {
  const lines: LogLine[] = [];
  for (const ev of events) {
    if (ev.type !== "log") continue;
    const d = ev.data;
    const message = String(d.message ?? "");
    const prev = lines[lines.length - 1];
    if (prev && CONTINUATION.test(message)) {
      (prev.stack ??= []).push(message.trim());
      continue;
    }
    const line: LogLine = { time: ev.time, level: String(d.level ?? "INFO"), logger: String(d.logger ?? ""), message };
    if (d.thrown) line.thrown = String(d.thrown);
    lines.push(line);
  }
  return lines;
}

export function registerLogTools(server: McpServer, ctx: ToolContext): void {
  defineTool(server, ctx, "logs",
    "Recent log lines from a Paper server (the default when one is connected) or a client, oldest first. Stack-trace lines printed separately are folded into the line above (`stack`). Filter by minimum `level`, `contains` (case-insensitive, also searches stacks) and `since` (epoch ms). Up to 1000 lines logged before the agent connected are replayed.",
    {
      instance: z.string().optional().describe("Instance id or name. Defaults to the only server, else the only instance."),
      level: z.enum(LEVELS).default("INFO"),
      contains: z.string().optional(),
      since: z.number().optional(),
      limit: z.number().int().min(1).max(1000).default(100),
    },
    async (args, c) => {
      const inst = c.agents.resolveAny(args.instance);
      const min = rank(args.level);
      const needle = args.contains?.toLowerCase();
      const lines = groupLogs(c.agents.events(inst.id))
        .filter((l) => rank(l.level) >= min)
        .filter((l) => args.since === undefined || l.time >= args.since)
        .filter((l) => !needle || [l.message, l.thrown ?? "", ...(l.stack ?? [])].join("\n").toLowerCase().includes(needle))
        .slice(-args.limit);
      return ok({ instance: inst.id, lines });
    });
}
```

`hub/src/server.ts`: import and register both, and extend the instructions:

```ts
import { registerLogTools } from "./tools/log-tools.js";
import { registerServerTools } from "./tools/server-tools.js";
```

```ts
const INSTRUCTIONS = [
  "Craftwire lets you see and drive Minecraft.",
  "Start with list_instances. Client tools (screenshot, camera, gui_*, input, chat, hud_read, player_state, client_settings) act on a game client.",
  "Server tools (server_command, server_eval, world_query, world_edit, server_info, plugin_manage) act on a Paper server running the Craftwire plugin; logs reads either.",
  "After an action that opens a menu (e.g. chat {action:'command'}), call wait_for {condition:'screen_open'} before gui_read.",
  "Errors carry a `hint` with the next step. Pass operationId on actions you might retry.",
].join(" ");
```

and in `createCraftwireServer`, after `registerClientTools(server, ctx);`:

```ts
  registerServerTools(server, ctx);
  registerLogTools(server, ctx);
```

- [ ] **Step 4: Run the whole hub suite and typecheck**

Run: `cd hub && npx vitest run && npm run typecheck`
Expected: all test files pass, and the protocol fixture test also accepts `valid-event-log.json`. Typecheck prints no errors.

- [ ] **Step 5: Commit**

```bash
git add hub protocol/fixtures/valid-event-log.json
git commit -m "feat(hub): server tools, logs with stack grouping, shared forward()"
```

---

### Task 3: Paper agent scaffold, server_info, integration-test harness

**Files:**
- Modify: `settings.gradle`, `gradle.properties`
- Create: `agent-paper/build.gradle`
- Create: `agent-paper/src/main/resources/plugin.yml`, `config.yml`
- Create: `agent-paper/src/main/java/com/uxplima/craftwire/paper/{CraftwirePlugin,AgentConfig,Args,Sync,EventBridge}.java`
- Create: `agent-paper/src/main/java/com/uxplima/craftwire/paper/handlers/{Handlers,ServerInfoHandler,PluginJson}.java`
- Create: `agent-paper/src/integrationTest/java/com/uxplima/craftwire/paper/it/{ItEnv,ItHub,PaperServer,PaperDownload,ItSessionListener,ConnectionIT}.java`
- Create: `agent-paper/src/integrationTest/resources/META-INF/services/org.junit.platform.launcher.LauncherSessionListener`
- Test: `agent-paper/src/test/java/com/uxplima/craftwire/paper/{AgentConfigTest,ArgsTest}.java`

**Interfaces:**
- Consumes: `LogCapture`, `HubClient.notifyEvent(type, data, time)` (Task 1).
- Produces: `CraftwirePlugin.dispatcher()`, `sync()`, `agentConfig()` and `emit(type, data)`.
  - `Sync.global(Callable<T>) → CompletableFuture<T>`, `Sync.region(World, int chunkX, int chunkZ, Callable<T>) → CompletableFuture<T>`.
  - `AgentConfig(instanceName, allowEval, allowWorldEdit, allowBots, maxEditVolume)` + `require(boolean, String key)`.
  - `Args.{string, integer, number, object, array, optString, optInt, optLong, bool, world, invalid}`.
  - `PluginJson.summary(Plugin) → {name, version, enabled}`.
  - Agent method `server.info`.
- Produces (tests): `ItEnv.get().hub` with `result(method, json) → JsonElement`, `error(method, json) → {code, hint, message}`, `awaitLog(Predicate<String>, ms)`, `awaitHello(ms, alive)` and `dropConnectionAndClearEvents()`.

- [ ] **Step 1: Gradle module and properties**

`settings.gradle`: change the include line to:

```groovy
include 'agent-core', 'agent-fabric', 'agent-paper'
```

`gradle.properties`, append:

```properties
paper_api_version=26.2.build.130-stable
paper_build=130
paper_sha256=715cd6633db27b2c05ce6efd2d477330ebae8a4f668cf0459fcaf434c4720fba
graal_version=25.0.4
```

`agent-paper/build.gradle`:

```groovy
plugins {
    id 'java'
}

base {
    archivesName = 'craftwire-paper'
}

evaluationDependsOn(':agent-core')

repositories {
    maven { name = 'PaperMC'; url = 'https://repo.papermc.io/repository/maven-public/' }
    mavenCentral()
}

sourceSets {
    integrationTest {}
}

dependencies {
    compileOnly "io.papermc.paper:paper-api:${paper_api_version}"
    compileOnly "org.apache.logging.log4j:log4j-core:${log4j_version}"
    // GraalJS comes from Paper's library loader at runtime (plugin.yml `libraries`), never from this jar.
    compileOnly "org.graalvm.polyglot:polyglot:${graal_version}"
    implementation project(':agent-core')

    testImplementation "io.papermc.paper:paper-api:${paper_api_version}"
    testImplementation "org.apache.logging.log4j:log4j-core:${log4j_version}"
    testImplementation "org.graalvm.polyglot:polyglot:${graal_version}"
    testImplementation "org.graalvm.js:js-language:${graal_version}"
    testImplementation platform('org.junit:junit-bom:5.13.4')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'

    integrationTestImplementation platform('org.junit:junit-bom:5.13.4')
    integrationTestImplementation 'org.junit.jupiter:junit-jupiter'
    integrationTestImplementation 'org.junit.platform:junit-platform-launcher'
    integrationTestImplementation 'com.google.code.gson:gson:2.13.2'
    integrationTestImplementation 'org.java-websocket:Java-WebSocket:1.6.0'
}

jar {
    // agent-core is bundled as plain classes; Gson and log4j are provided by Paper.
    dependsOn ':agent-core:classes'
    from project(':agent-core').sourceSets.main.output
}

processResources {
    def props = [version: project.version.toString(), graalVersion: graal_version]
    inputs.properties props
    filesMatching('plugin.yml') { expand props }
}

tasks.withType(JavaCompile).configureEach {
    options.release = 25
    options.encoding = 'UTF-8'
}

test {
    useJUnitPlatform()
}

tasks.register('integrationTest', Test) {
    description = 'Runs the plugin inside a real Paper server.'
    group = 'verification'
    testClassesDirs = sourceSets.integrationTest.output.classesDirs
    classpath = sourceSets.integrationTest.runtimeClasspath
    useJUnitPlatform()
    dependsOn tasks.named('jar')
    def pluginJar = tasks.named('jar').flatMap { it.archiveFile }
    def itDir = layout.buildDirectory.dir('it')
    inputs.files(pluginJar)
    systemProperty 'craftwire.mcVersion', minecraft_version
    systemProperty 'craftwire.paperBuild', paper_build
    systemProperty 'craftwire.paperSha256', paper_sha256
    systemProperty 'craftwire.version', project.version.toString()
    doFirst {
        systemProperty 'craftwire.pluginJars', pluginJar.get().asFile.absolutePath
        systemProperty 'craftwire.itDir', itDir.get().asFile.absolutePath
    }
    outputs.upToDateWhen { false }
    testLogging {
        events 'passed', 'failed'
        exceptionFormat 'full'
    }
}
```

`agent-paper/src/main/resources/plugin.yml`:

```yaml
name: Craftwire
version: '${version}'
main: com.uxplima.craftwire.paper.CraftwirePlugin
api-version: '26.2'
description: Lets AI agents drive this server through the Craftwire MCP hub. Development servers only.
author: UXPLIMA
website: https://github.com/uxplima/craftwire
folia-supported: false
libraries:
  - org.graalvm.polyglot:polyglot:${graalVersion}
  - org.graalvm.js:js-language:${graalVersion}
```

`agent-paper/src/main/resources/config.yml`:

```yaml
# Craftwire lets AI agents drive this server through the craftwire hub on 127.0.0.1.
# Development servers only: anyone who can run the hub on this machine gets full control.

# Name shown in list_instances. Empty = the server folder name.
instance-name: ""
# server_eval: run JavaScript with full Bukkit access.
allow-eval: true
# world_edit: change blocks, paste structures, restore snapshots.
allow-world-edit: true
# bot_spawn / bot_action (milestone M4).
allow-bots: true
# Largest box, in blocks, that one world_edit call may change.
max-edit-volume: 1000000
```

- [ ] **Step 2: Write the failing unit tests**

`agent-paper/src/test/java/com/uxplima/craftwire/paper/AgentConfigTest.java`:

```java
package com.uxplima.craftwire.paper;

import static org.junit.jupiter.api.Assertions.*;

import com.uxplima.craftwire.core.AgentError;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class AgentConfigTest {
    @Test
    void defaultsAllowEverythingAndUseTheFolderName() {
        AgentConfig c = AgentConfig.from(new YamlConfiguration(), "myserver");
        assertEquals(new AgentConfig("myserver", true, true, true, 1_000_000), c);
    }

    @Test
    void readsValuesFromTheFile() throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString("instance-name: lobby\nallow-eval: false\nmax-edit-volume: 5000\n");
        AgentConfig c = AgentConfig.from(y, "ignored");
        assertEquals("lobby", c.instanceName());
        assertFalse(c.allowEval());
        assertTrue(c.allowWorldEdit());
        assertEquals(5000, c.maxEditVolume());
    }

    @Test
    void shippedConfigMatchesTheDefaults() throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(new String(getClass().getResourceAsStream("/config.yml").readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(new AgentConfig("x", true, true, true, 1_000_000), AgentConfig.from(y, "x"));
    }

    @Test
    void aDisabledCapabilityIsPermissionDisabled() {
        AgentConfig c = new AgentConfig("s", false, true, true, 10);
        AgentError e = assertThrows(AgentError.class, () -> c.require(c.allowEval(), "allow-eval"));
        assertEquals("PERMISSION_DISABLED", e.code());
        assertTrue(e.hint().contains("allow-eval: true"), e.hint());
    }
}
```

`agent-paper/src/test/java/com/uxplima/craftwire/paper/ArgsTest.java`:

```java
package com.uxplima.craftwire.paper;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.uxplima.craftwire.core.AgentError;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ArgsTest {
    final JsonObject p = JsonParser.parseString("{\"s\":\"hi\",\"n\":3,\"o\":{\"x\":1},\"nil\":null}").getAsJsonObject();

    @Test
    void readsPresentValues() {
        assertEquals("hi", Args.string(p, "s"));
        assertEquals(3, Args.integer(p, "n"));
        assertEquals(1, Args.integer(Args.object(p, "o"), "x"));
        assertEquals(Optional.of(3L), Args.optLong(p, "n"));
    }

    @Test
    void nullCountsAsMissing() {
        assertEquals(Optional.empty(), Args.optString(p, "nil"));
        assertTrue(Args.bool(p, "nil", true));
    }

    @Test
    void missingRequiredValuesAreInvalidParams() {
        AgentError e = assertThrows(AgentError.class, () -> Args.string(p, "absent"));
        assertEquals("INVALID_PARAMS", e.code());
        assertTrue(e.getMessage().contains("absent"));
        assertThrows(AgentError.class, () -> Args.object(p, "s"));
    }
}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:test --console=plain`
Expected: compilation FAILS with `cannot find symbol: class AgentConfig` and `class Args`.

- [ ] **Step 4: Implement the plugin core**

`agent-paper/src/main/java/com/uxplima/craftwire/paper/AgentConfig.java`:

```java
package com.uxplima.craftwire.paper;

import com.uxplima.craftwire.core.AgentError;
import org.bukkit.configuration.ConfigurationSection;

public record AgentConfig(String instanceName, boolean allowEval, boolean allowWorldEdit, boolean allowBots, long maxEditVolume) {
    public static AgentConfig from(ConfigurationSection c, String fallbackName) {
        String name = c.getString("instance-name", "");
        return new AgentConfig(name == null || name.isBlank() ? fallbackName : name,
                c.getBoolean("allow-eval", true),
                c.getBoolean("allow-world-edit", true),
                c.getBoolean("allow-bots", true),
                Math.max(1, c.getLong("max-edit-volume", 1_000_000)));
    }

    public void require(boolean allowed, String key) {
        if (!allowed) {
            throw new AgentError("PERMISSION_DISABLED", key + " is disabled in plugins/Craftwire/config.yml",
                    "Ask the server owner to set " + key + ": true and restart the server.");
        }
    }
}
```

`agent-paper/src/main/java/com/uxplima/craftwire/paper/Args.java`:

```java
package com.uxplima.craftwire.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.World;

/** Reads request parameters; a missing required value is INVALID_PARAMS. */
public final class Args {
    private Args() {}

    private static Optional<JsonElement> get(JsonObject p, String k) {
        JsonElement e = p.get(k);
        return e == null || e.isJsonNull() ? Optional.empty() : Optional.of(e);
    }

    public static Optional<String> optString(JsonObject p, String k) {
        return get(p, k).map(JsonElement::getAsString);
    }

    public static Optional<Integer> optInt(JsonObject p, String k) {
        return get(p, k).map(JsonElement::getAsInt);
    }

    public static Optional<Long> optLong(JsonObject p, String k) {
        return get(p, k).map(JsonElement::getAsLong);
    }

    public static boolean bool(JsonObject p, String k, boolean fallback) {
        return get(p, k).map(JsonElement::getAsBoolean).orElse(fallback);
    }

    public static String string(JsonObject p, String k) {
        return optString(p, k).orElseThrow(() -> missing(k));
    }

    public static int integer(JsonObject p, String k) {
        return optInt(p, k).orElseThrow(() -> missing(k));
    }

    public static double number(JsonObject p, String k) {
        return get(p, k).map(JsonElement::getAsDouble).orElseThrow(() -> missing(k));
    }

    public static JsonObject object(JsonObject p, String k) {
        return get(p, k).filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject).orElseThrow(() -> missing(k));
    }

    public static JsonArray array(JsonObject p, String k) {
        return get(p, k).filter(JsonElement::isJsonArray).map(JsonElement::getAsJsonArray).orElseThrow(() -> missing(k));
    }

    /** The world named by {@code world}, or the main world when absent. */
    public static World world(JsonObject p) {
        Optional<String> name = optString(p, "world");
        if (name.isEmpty()) return Bukkit.getWorlds().get(0);
        World w = Bukkit.getWorld(name.get());
        if (w == null) {
            throw new AgentError("WORLD_NOT_FOUND", "No world named " + name.get(),
                    "Loaded worlds: " + Bukkit.getWorlds().stream().map(World::getName).toList() + ".");
        }
        return w;
    }

    public static AgentError invalid(String message) {
        return new AgentError("INVALID_PARAMS", message, "Check the tool's parameter description and retry.");
    }

    private static AgentError missing(String k) {
        return invalid("`" + k + "` is required");
    }
}
```

`agent-paper/src/main/java/com/uxplima/craftwire/paper/Sync.java`:

```java
package com.uxplima.craftwire.paper;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Runs work on the thread that owns it (the main thread on Paper, the owning region on Folia) and returns a future.
 * Never blocks the caller; exceptions complete the future instead of reaching the server loop.
 */
public final class Sync {
    private final Plugin plugin;

    public Sync(Plugin plugin) {
        this.plugin = plugin;
    }

    public <T> CompletableFuture<T> global(Callable<T> work) {
        CompletableFuture<T> f = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> complete(f, work));
        return f;
    }

    public <T> CompletableFuture<T> region(World world, int chunkX, int chunkZ, Callable<T> work) {
        CompletableFuture<T> f = new CompletableFuture<>();
        Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, () -> complete(f, work));
        return f;
    }

    private static <T> void complete(CompletableFuture<T> f, Callable<T> work) {
        try {
            f.complete(work.call());
        } catch (Throwable t) {
            f.completeExceptionally(t);
        }
    }
}
```

`agent-paper/src/main/java/com/uxplima/craftwire/paper/EventBridge.java`:

```java
package com.uxplima.craftwire.paper;

import com.google.gson.JsonObject;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Forwards player and chat events to the hub, where wait_for and the event buffer see them. */
final class EventBridge implements Listener {
    private final CraftwirePlugin plugin;

    EventBridge(CraftwirePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        plugin.emit("player", player("join", e.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        plugin.emit("player", player("quit", e.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        JsonObject d = new JsonObject();
        d.addProperty("text", PlainTextComponentSerializer.plainText().serialize(e.message()));
        d.addProperty("kind", "chat");
        d.addProperty("sender", e.getPlayer().getName());
        plugin.emit("chat", d);
    }

    private static JsonObject player(String action, Player p) {
        JsonObject d = new JsonObject();
        d.addProperty("action", action);
        d.addProperty("name", p.getName());
        d.addProperty("uuid", p.getUniqueId().toString());
        return d;
    }
}
```

`agent-paper/src/main/java/com/uxplima/craftwire/paper/handlers/PluginJson.java`:

```java
package com.uxplima.craftwire.paper.handlers;

import com.google.gson.JsonObject;
import org.bukkit.plugin.Plugin;

final class PluginJson {
    private PluginJson() {}

    static JsonObject summary(Plugin p) {
        JsonObject o = new JsonObject();
        o.addProperty("name", p.getName());
        o.addProperty("version", p.getPluginMeta().getVersion());
        o.addProperty("enabled", p.isEnabled());
        return o;
    }
}
```

`agent-paper/src/main/java/com/uxplima/craftwire/paper/handlers/ServerInfoHandler.java`:

```java
package com.uxplima.craftwire.paper.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Comparator;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

final class ServerInfoHandler {
    private ServerInfoHandler() {}

    static JsonElement read() {
        Server s = Bukkit.getServer();
        JsonObject o = new JsonObject();
        o.addProperty("name", s.getName());
        o.addProperty("version", s.getVersion());
        o.addProperty("minecraftVersion", s.getMinecraftVersion());
        JsonArray tps = new JsonArray();
        for (double t : s.getTPS()) tps.add(round(Math.min(t, 20.0)));
        o.add("tps", tps);
        o.addProperty("mspt", round(s.getAverageTickTime()));

        Runtime rt = Runtime.getRuntime();
        JsonObject memory = new JsonObject();
        memory.addProperty("usedMb", (rt.totalMemory() - rt.freeMemory()) >> 20);
        memory.addProperty("maxMb", rt.maxMemory() >> 20);
        o.add("memory", memory);

        JsonObject players = new JsonObject();
        players.addProperty("online", s.getOnlinePlayers().size());
        players.addProperty("max", s.getMaxPlayers());
        JsonArray names = new JsonArray();
        for (Player p : s.getOnlinePlayers()) names.add(p.getName());
        players.add("names", names);
        o.add("players", players);

        JsonArray worlds = new JsonArray();
        for (World w : s.getWorlds()) {
            JsonObject wo = new JsonObject();
            wo.addProperty("name", w.getName());
            wo.addProperty("environment", w.getEnvironment().name().toLowerCase());
            wo.addProperty("players", w.getPlayers().size());
            wo.addProperty("loadedChunks", w.getLoadedChunks().length);
            worlds.add(wo);
        }
        o.add("worlds", worlds);

        JsonArray plugins = new JsonArray();
        Arrays.stream(s.getPluginManager().getPlugins())
                .sorted(Comparator.comparing(Plugin::getName, String.CASE_INSENSITIVE_ORDER))
                .forEach(p -> plugins.add(PluginJson.summary(p)));
        o.add("plugins", plugins);
        return o;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
```

`agent-paper/src/main/java/com/uxplima/craftwire/paper/handlers/Handlers.java`:

```java
package com.uxplima.craftwire.paper.handlers;

import com.uxplima.craftwire.core.Dispatcher;
import com.uxplima.craftwire.paper.CraftwirePlugin;
import com.uxplima.craftwire.paper.Sync;

public final class Handlers {
    private Handlers() {}

    public static void registerAll(CraftwirePlugin plugin) {
        Dispatcher d = plugin.dispatcher();
        Sync s = plugin.sync();
        d.register("server.info", p -> s.global(ServerInfoHandler::read));
    }
}
```

`agent-paper/src/main/java/com/uxplima/craftwire/paper/CraftwirePlugin.java`:

```java
package com.uxplima.craftwire.paper;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.Dispatcher;
import com.uxplima.craftwire.core.Hello;
import com.uxplima.craftwire.core.HubClient;
import com.uxplima.craftwire.core.HubConfig;
import com.uxplima.craftwire.core.LogCapture;
import com.uxplima.craftwire.core.OperationCache;
import com.uxplima.craftwire.paper.handlers.Handlers;
import java.nio.file.Path;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class CraftwirePlugin extends JavaPlugin {
    private final Dispatcher dispatcher = new Dispatcher(new OperationCache(300_000, System::currentTimeMillis));
    private LogCapture logs;
    private HubClient hub;
    private AgentConfig config;
    private Sync sync;

    @Override
    public void onLoad() {
        // As early as possible: lines logged while the server starts are replayed to the hub once it connects.
        logs = LogCapture.install(1000);
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = AgentConfig.from(getConfig(), serverFolderName());
        sync = new Sync(this);
        getLogger().warning("Craftwire is active — do not run on production servers");
        Handlers.registerAll(this);
        getServer().getPluginManager().registerEvents(new EventBridge(this), this);
        hub = new HubClient(() -> HubConfig.load(HubConfig.defaultHome()), this::hello, dispatcher, new HubClient.Listener() {
            @Override public void onConnected(String instanceId) { onHubConnected(instanceId); }
            @Override public void onDisconnected() { onHubDisconnected(); }
            @Override public void onLog(String message) { getSLF4JLogger().debug(message); }
        });
        hub.start();
    }

    @Override
    public void onDisable() {
        if (hub != null) hub.close();
        if (logs != null) logs.uninstall();
    }

    private Hello hello() {
        return new Hello("server", getPluginMeta().getVersion(), Bukkit.getMinecraftVersion(), config.instanceName());
    }

    private void onHubConnected(String instanceId) {
        getLogger().info("Connected to the Craftwire hub as " + instanceId);
        logs.attach((data, time) -> hub.notifyEvent("log", data, time));
    }

    private void onHubDisconnected() {
        logs.detach();
    }

    private static String serverFolderName() {
        Path dir = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        return dir.getFileName() == null ? "server" : dir.getFileName().toString();
    }

    public Dispatcher dispatcher() {
        return dispatcher;
    }

    public Sync sync() {
        return sync;
    }

    public AgentConfig agentConfig() {
        return config;
    }

    public void emit(String type, JsonObject data) {
        if (hub != null) hub.notifyEvent(type, data);
    }
}
```

- [ ] **Step 5: Run the unit tests to verify they pass**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:test :agent-paper:jar --console=plain`
Expected: BUILD SUCCESSFUL, 7 tests pass. `agent-paper/build/libs/craftwire-paper-0.1.0.jar` exists and contains `plugin.yml` with `polyglot:25.0.4` and the `com/uxplima/craftwire/core/HubClient.class` class (check with `jar tf`).

- [ ] **Step 6: Write the integration harness and the failing ITs**

`agent-paper/src/integrationTest/java/com/uxplima/craftwire/paper/it/PaperDownload.java`:

```java
package com.uxplima.craftwire.paper.it;

import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Downloads one pinned Paper build through the Fill v3 API and checks its sha256. */
final class PaperDownload {
    private static final String AGENT = "craftwire-integration-tests (https://github.com/uxplima/craftwire)";

    private PaperDownload() {}

    static Path ensure(Path cacheDir, String mcVersion, String build, String sha256) throws Exception {
        Path jar = cacheDir.resolve("paper-" + mcVersion + "-" + build + ".jar");
        if (Files.isRegularFile(jar) && sha256(jar).equals(sha256)) return jar;
        Files.createDirectories(cacheDir);
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        URI metaUri = URI.create("https://fill.papermc.io/v3/projects/paper/versions/" + mcVersion + "/builds/" + build);
        String meta = http.send(HttpRequest.newBuilder(metaUri).header("User-Agent", AGENT).build(), BodyHandlers.ofString()).body();
        String url = JsonParser.parseString(meta).getAsJsonObject().getAsJsonObject("downloads")
                .getAsJsonObject("server:default").get("url").getAsString();
        Path part = cacheDir.resolve(jar.getFileName() + ".part");
        http.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", AGENT).build(), BodyHandlers.ofFile(part));
        String got = sha256(part);
        if (!got.equals(sha256)) throw new IllegalStateException("Paper jar checksum mismatch: " + got);
        Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING);
        return jar;
    }

    static String sha256(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }
}
```

`agent-paper/src/integrationTest/java/com/uxplima/craftwire/paper/it/PaperServer.java`:

```java
package com.uxplima.craftwire.paper.it;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** A Paper server process in a fresh flat world, bound to 127.0.0.1. */
final class PaperServer {
    final Process process;
    final List<String> console = new CopyOnWriteArrayList<>();
    private final Writer stdin;

    private PaperServer(Process process) {
        this.process = process;
        this.stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) console.add(line);
            } catch (IOException ignored) {
                // the process ended
            }
        }, "paper-console");
        reader.setDaemon(true);
        reader.start();
    }

    static PaperServer start(Path dir, Path paperJar, List<Path> plugins, Path craftwireHome, int port) throws IOException {
        Path pluginDir = dir.resolve("plugins");
        Files.createDirectories(pluginDir);
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(p -> p.getFileName().toString().startsWith("world")).toList()) deleteTree(p);
        }
        try (Stream<Path> s = Files.list(pluginDir)) {
            for (Path p : s.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) Files.delete(p);
        }
        deleteTree(pluginDir.resolve("Craftwire"));
        for (int i = 0; i < plugins.size(); i++) Files.copy(plugins.get(i), pluginDir.resolve("it-" + i + ".jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n");
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
                "server-ip=127.0.0.1",
                "server-port=" + port,
                "online-mode=false",
                "level-type=minecraft\\:flat",
                "generate-structures=false",
                "spawn-protection=0",
                "view-distance=4",
                "simulation-distance=4",
                "max-players=4",
                "motd=craftwire-it") + "\n");
        String java = ProcessHandle.current().info().command().orElse("java");
        ProcessBuilder pb = new ProcessBuilder(java, "-Xmx2G", "-jar", paperJar.toAbsolutePath().toString(), "--nogui")
                .directory(dir.toFile())
                .redirectErrorStream(true);
        pb.environment().put("CRAFTWIRE_HOME", craftwireHome.toAbsolutePath().toString());
        return new PaperServer(pb.start());
    }

    String tail(int lines) {
        return String.join("\n", console.subList(Math.max(0, console.size() - lines), console.size()));
    }

    void stop() {
        if (!process.isAlive()) return;
        try {
            stdin.write("stop\n");
            stdin.flush();
            if (process.waitFor(90, TimeUnit.SECONDS)) return;
        } catch (IOException ignored) {
            // fall through to a forced stop
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        process.destroyForcibly();
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
```

`agent-paper/src/integrationTest/java/com/uxplima/craftwire/paper/it/ItHub.java`:

```java
package com.uxplima.craftwire.paper.it;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/** Stands in for the craftwire hub: welcomes the agent, sends requests, records events. */
final class ItHub extends WebSocketServer {
    static final String TOKEN = "c".repeat(64);

    final List<JsonObject> events = new CopyOnWriteArrayList<>();
    private final Map<Integer, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger(1);
    private final CountDownLatch started = new CountDownLatch(1);
    private volatile CompletableFuture<JsonObject> hello = new CompletableFuture<>();
    private volatile WebSocket conn;

    ItHub(int port) {
        super(new InetSocketAddress("127.0.0.1", port));
        setReuseAddr(true);
    }

    void startAndWait() throws InterruptedException {
        start();
        if (!started.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test hub did not start");
    }

    JsonObject awaitHello(long timeoutMs, BooleanSupplier serverAlive) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (hello.isDone()) return hello.get();
            if (!serverAlive.getAsBoolean()) throw new AssertionError("Paper exited before the agent connected");
            Thread.sleep(200);
        }
        throw new AssertionError("agent did not connect within " + timeoutMs + " ms");
    }

    JsonObject call(String method, String paramsJson, long timeoutMs) throws Exception {
        int id = ids.getAndIncrement();
        CompletableFuture<JsonObject> f = new CompletableFuture<>();
        pending.put(id, f);
        JsonObject req = new JsonObject();
        req.addProperty("jsonrpc", "2.0");
        req.addProperty("id", id);
        req.addProperty("method", method);
        req.add("params", JsonParser.parseString(paramsJson));
        conn.send(req.toString());
        return f.get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    JsonElement result(String method, String paramsJson) throws Exception {
        JsonObject r = call(method, paramsJson, 120_000);
        if (r.has("error")) throw new AssertionError(method + " failed: " + r.get("error"));
        return r.get("result");
    }

    /** The {code, hint, message} of an expected error. */
    JsonObject error(String method, String paramsJson) throws Exception {
        JsonObject r = call(method, paramsJson, 120_000);
        if (!r.has("error")) throw new AssertionError(method + " unexpectedly succeeded: " + r.get("result"));
        JsonObject e = r.getAsJsonObject("error");
        JsonObject out = e.getAsJsonObject("data").deepCopy();
        out.add("message", e.get("message"));
        return out;
    }

    JsonObject awaitEvent(Predicate<JsonObject> match, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            for (JsonObject e : events) if (match.test(e)) return e;
            Thread.sleep(50);
        }
        throw new AssertionError("no matching event within " + timeoutMs + " ms; last events: "
                + events.subList(Math.max(0, events.size() - 10), events.size()));
    }

    JsonObject awaitLog(Predicate<String> message, long timeoutMs) throws InterruptedException {
        return awaitEvent(e -> "log".equals(e.get("type").getAsString())
                && message.test(e.getAsJsonObject("data").get("message").getAsString()), timeoutMs);
    }

    /** Simulates a hub restart: the agent must reconnect and start a fresh session. */
    void dropConnectionAndClearEvents() {
        events.clear();
        hello = new CompletableFuture<>();
        WebSocket c = conn;
        if (c != null) c.close(1001, "test reconnect");
    }

    @Override public void onStart() { started.countDown(); }
    @Override public void onOpen(WebSocket c, ClientHandshake h) { conn = c; }
    @Override public void onClose(WebSocket c, int code, String reason, boolean remote) {}
    @Override public void onError(WebSocket c, Exception e) {}

    @Override
    public void onMessage(WebSocket c, String text) {
        JsonObject o = JsonParser.parseString(text).getAsJsonObject();
        String method = o.has("method") ? o.get("method").getAsString() : null;
        if ("hello".equals(method)) {
            c.send("{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"instanceId\":\"server-1\"}}");
            hello.complete(o.getAsJsonObject("params"));
        } else if ("event".equals(method)) {
            events.add(o.getAsJsonObject("params"));
        } else if (o.has("id")) {
            CompletableFuture<JsonObject> f = pending.remove(o.get("id").getAsInt());
            if (f != null) f.complete(o);
        }
    }
}
```

`agent-paper/src/integrationTest/java/com/uxplima/craftwire/paper/it/ItEnv.java`:

```java
package com.uxplima.craftwire.paper.it;

import com.google.gson.JsonObject;
import java.io.File;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** One Paper server + test hub shared by every IT class in the run (startup takes tens of seconds). */
final class ItEnv {
    private static ItEnv instance;

    final ItHub hub;
    final PaperServer server;
    final JsonObject hello;

    private ItEnv(ItHub hub, PaperServer server, JsonObject hello) {
        this.hub = hub;
        this.server = server;
        this.hello = hello;
    }

    static synchronized ItEnv get() throws Exception {
        if (instance == null) instance = start();
        return instance;
    }

    static synchronized void shutdown() {
        if (instance == null) return;
        instance.server.stop();
        try {
            instance.hub.stop(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        instance = null;
    }

    private static ItEnv start() throws Exception {
        Path work = Path.of(System.getProperty("craftwire.itDir"));
        Path home = work.resolve("craftwire-home");
        Files.createDirectories(home);
        ItHub hub = new ItHub(freePort());
        hub.startAndWait();
        Files.writeString(home.resolve("hub.json"), "{\"port\":" + hub.getPort() + ",\"token\":\"" + ItHub.TOKEN + "\"}");
        Path paper = PaperDownload.ensure(work.resolve("cache"), System.getProperty("craftwire.mcVersion"),
                System.getProperty("craftwire.paperBuild"), System.getProperty("craftwire.paperSha256"));
        List<Path> plugins = Arrays.stream(System.getProperty("craftwire.pluginJars").split(File.pathSeparator)).map(Path::of).toList();
        PaperServer server = PaperServer.start(work.resolve("server"), paper, plugins, home, freePort());
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        try {
            // First run downloads the Mojang jar and GraalJS through Paper's library loader: allow minutes.
            JsonObject hello = hub.awaitHello(300_000, server.process::isAlive);
            hub.awaitLog(m -> m.startsWith("Done ("), 300_000);
            return new ItEnv(hub, server, hello);
        } catch (Throwable t) {
            server.stop();
            throw new AssertionError(t.getMessage() + "\n--- Paper console (tail) ---\n" + server.tail(80), t);
        }
    }

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
```

`agent-paper/src/integrationTest/java/com/uxplima/craftwire/paper/it/ItSessionListener.java`:

```java
package com.uxplima.craftwire.paper.it;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/** Stops the shared Paper server when the test run ends (the JVM shutdown hook is only a backup). */
public final class ItSessionListener implements LauncherSessionListener {
    @Override
    public void launcherSessionClosed(LauncherSession session) {
        ItEnv.shutdown();
    }
}
```

`agent-paper/src/integrationTest/resources/META-INF/services/org.junit.platform.launcher.LauncherSessionListener`:

```
com.uxplima.craftwire.paper.it.ItSessionListener
```

`agent-paper/src/integrationTest/java/com/uxplima/craftwire/paper/it/ConnectionIT.java`:

```java
package com.uxplima.craftwire.paper.it;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class ConnectionIT {
    @Test
    void helloIdentifiesThePaperAgent() throws Exception {
        JsonObject h = ItEnv.get().hello;
        assertEquals("server", h.get("agentKind").getAsString());
        assertEquals(1, h.get("protocolVersion").getAsInt());
        assertEquals("26.2", h.get("mcVersion").getAsString());
        assertEquals("server", h.get("instanceName").getAsString());
        assertEquals(System.getProperty("craftwire.version"), h.get("agentVersion").getAsString());
    }

    @Test
    void startupLinesAreReplayedIncludingTheProductionWarning() throws Exception {
        ItHub hub = ItEnv.get().hub;
        hub.awaitLog(m -> m.contains("Craftwire is active — do not run on production servers"), 10_000);
        // Logged after onLoad but before the plugin enabled and connected: only the backlog can deliver it.
        hub.awaitLog(m -> m.startsWith("Preparing level"), 10_000);
    }

    @Test
    void serverInfoReportsVersionsAndPlugins() throws Exception {
        JsonObject info = ItEnv.get().hub.result("server.info", "{}").getAsJsonObject();
        assertEquals("26.2", info.get("minecraftVersion").getAsString());
        assertEquals(3, info.getAsJsonArray("tps").size());
        assertTrue(info.getAsJsonArray("plugins").toString().contains("\"name\":\"Craftwire\""), info.toString());
        assertEquals("world", info.getAsJsonArray("worlds").get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void unknownMethodsAreStructuredErrors() throws Exception {
        assertEquals("UNKNOWN_METHOD", ItEnv.get().hub.error("no.such.method", "{}").get("code").getAsString());
    }

    @Test
    void reconnectReplaysTheBacklogToTheNewSession() throws Exception {
        ItHub hub = ItEnv.get().hub;
        hub.dropConnectionAndClearEvents();
        hub.awaitHello(30_000, () -> true);
        hub.awaitLog(m -> m.startsWith("Done ("), 10_000);
    }
}
```

- [ ] **Step 7: Run the integration tests**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:integrationTest --console=plain`
Expected: on the first run, Paper and GraalJS download (up to a few minutes). Then all 5 ConnectionIT tests PASS.

If a test fails, read `agent-paper/build/it/server/logs/latest.log`.

The ITs were written in Step 6 against code from Step 4, so to see RED, temporarily comment out the `logs.attach(...)` line in `onHubConnected`. Run only ConnectionIT with `--tests '*ConnectionIT'`: `startupLinesAreReplayedIncludingTheProductionWarning` must FAIL. Restore the line and run again: PASS.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle gradle.properties agent-paper
git commit -m "feat(paper): Craftwire plugin scaffold, server.info, real-Paper integration harness"
```

---

### Task 4: server_command and the test-fixtures plugin

**Files:**
- Modify: `settings.gradle`, `agent-paper/build.gradle`
- Create: `test-fixtures/build.gradle`, `test-fixtures/src/main/resources/plugin.yml`, `test-fixtures/src/main/java/com/uxplima/craftwire/fixtures/FixturePlugin.java`
- Create: `agent-paper/src/main/java/com/uxplima/craftwire/paper/handlers/CommandHandler.java`
- Modify: `agent-paper/src/main/java/com/uxplima/craftwire/paper/handlers/Handlers.java`
- Test: `agent-paper/src/integrationTest/java/com/uxplima/craftwire/paper/it/CommandIT.java`

**Interfaces:**
- Consumes: `Sync.global`, `Args` (Task 3).
- Produces: agent method `server.command {command, asPlayer?, collectMs?}` → `{command, success, output: string[], sender?, note?}`. Errors: `UNKNOWN_COMMAND`, `COMMAND_FAILED` and `PLAYER_NOT_FOUND`.
- Produces: the `CraftwireFixture` test plugin with the command `/cwfixture`. It replies `fixture: now` immediately and `fixture: later` 2 ticks later.

- [ ] **Step 1: Add the fixture plugin**

`settings.gradle`:

```groovy
include 'agent-core', 'agent-fabric', 'agent-paper', 'test-fixtures'
```

`test-fixtures/build.gradle`:

```groovy
plugins {
    id 'java'
}

base {
    archivesName = 'craftwire-test-fixtures'
}

repositories {
    maven { name = 'PaperMC'; url = 'https://repo.papermc.io/repository/maven-public/' }
    mavenCentral()
}

dependencies {
    compileOnly "io.papermc.paper:paper-api:${paper_api_version}"
}

processResources {
    def props = [version: project.version.toString()]
    inputs.properties props
    filesMatching('plugin.yml') { expand props }
}

tasks.withType(JavaCompile).configureEach {
    options.release = 25
    options.encoding = 'UTF-8'
}
```

`test-fixtures/src/main/resources/plugin.yml`:

```yaml
name: CraftwireFixture
version: '${version}'
main: com.uxplima.craftwire.fixtures.FixturePlugin
api-version: '26.2'
description: Test-only plugin for the Craftwire integration tests.
commands:
  cwfixture:
    description: Replies now and again two ticks later.
```

`test-fixtures/src/main/java/com/uxplima/craftwire/fixtures/FixturePlugin.java`:

```java
package com.uxplima.craftwire.fixtures;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/** Test-only plugin: predictable behaviour for the Craftwire integration tests to observe. */
public final class FixturePlugin extends JavaPlugin {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        sender.sendMessage(Component.text("fixture: now"));
        // Many plugins answer a tick or more later (async lookups, menus); server_command must still see it.
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> sender.sendMessage(Component.text("fixture: later")), 2);
        return true;
    }
}
```

In `agent-paper/build.gradle`, add `evaluationDependsOn(':test-fixtures')` below `evaluationDependsOn(':agent-core')`. In the `integrationTest` task, replace the `dependsOn`, `pluginJar`, `inputs.files` and `doFirst` lines with:

```groovy
    dependsOn tasks.named('jar'), ':test-fixtures:jar'
    def pluginJar = tasks.named('jar').flatMap { it.archiveFile }
    def fixtureJar = project(':test-fixtures').tasks.named('jar').flatMap { it.archiveFile }
    def itDir = layout.buildDirectory.dir('it')
    inputs.files(pluginJar, fixtureJar)
```

```groovy
    doFirst {
        systemProperty 'craftwire.pluginJars', [pluginJar.get().asFile, fixtureJar.get().asFile]*.absolutePath.join(File.pathSeparator)
        systemProperty 'craftwire.itDir', itDir.get().asFile.absolutePath
    }
```

- [ ] **Step 2: Write the failing ITs**

`agent-paper/src/integrationTest/java/com/uxplima/craftwire/paper/it/CommandIT.java`:

```java
package com.uxplima.craftwire.paper.it;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class CommandIT {
    static JsonObject run(String paramsJson) throws Exception {
        return ItEnv.get().hub.result("server.command", paramsJson).getAsJsonObject();
    }

    static JsonObject fail(String paramsJson) throws Exception {
        return ItEnv.get().hub.error("server.command", paramsJson);
    }

    @Test
    void vanillaFeedbackIsReturned() throws Exception {
        JsonObject r = run("{\"command\":\"time query gametime\"}");
        assertTrue(r.get("success").getAsBoolean());
        assertTrue(r.getAsJsonArray("output").get(0).getAsString().startsWith("The game time is"), r.toString());
    }

    @Test
    void leadingSlashIsOptionalAndBukkitCommandsWork() throws Exception {
        assertTrue(run("{\"command\":\"/plugins\"}").getAsJsonArray("output").toString().contains("Craftwire"));
    }

    @Test
    void lateFeedbackIsCollected() throws Exception {
        JsonObject r = run("{\"command\":\"cwfixture\",\"collectMs\":1000}");
        assertEquals("[\"fixture: now\",\"fixture: later\"]", r.getAsJsonArray("output").toString());
    }

    @Test
    void collectMsZeroReturnsOnlyImmediateFeedback() throws Exception {
        JsonObject r = run("{\"command\":\"cwfixture\",\"collectMs\":0}");
        assertEquals("[\"fixture: now\"]", r.getAsJsonArray("output").toString());
    }

    @Test
    void unknownCommandIsAnError() throws Exception {
        JsonObject e = fail("{\"command\":\"nosuchcmd arg\"}");
        assertEquals("UNKNOWN_COMMAND", e.get("code").getAsString());
        assertTrue(e.get("message").getAsString().contains("nosuchcmd"));
    }

    @Test
    void aThrowingVanillaCommandIsCommandFailed() throws Exception {
        // 26.2 moved day time to timelines; the old form throws inside the command.
        JsonObject e = fail("{\"command\":\"time query daytime\"}");
        assertEquals("COMMAND_FAILED", e.get("code").getAsString());
        assertTrue(e.get("message").getAsString().contains("daytime"), e.toString());
    }

    @Test
    void asPlayerNeedsAnOnlinePlayer() throws Exception {
        assertEquals("PLAYER_NOT_FOUND", fail("{\"command\":\"list\",\"asPlayer\":\"Nobody\"}").get("code").getAsString());
    }
}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:integrationTest --tests '*CommandIT' --console=plain`
Expected: all 7 FAIL with `server.command failed: {... "code":"UNKNOWN_METHOD" ...}`.

- [ ] **Step 4: Implement**

`agent-paper/src/main/java/com/uxplima/craftwire/paper/handlers/CommandHandler.java`:

```java
package com.uxplima.craftwire.paper.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.paper.Args;
import com.uxplima.craftwire.paper.Sync;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

final class CommandHandler {
    private CommandHandler() {}

    static CompletableFuture<JsonElement> handle(JsonObject p, Sync sync) {
        String raw = Args.string(p, "command").strip();
        String command = raw.startsWith("/") ? raw.substring(1) : raw;
        if (command.isBlank()) throw Args.invalid("`command` is empty");
        long collectMs = Math.clamp(Args.optLong(p, "collectMs").orElse(250L), 0L, 5000L);
        Optional<String> asPlayer = Args.optString(p, "asPlayer");
        List<String> output = new CopyOnWriteArrayList<>();
        CompletableFuture<JsonObject> dispatched = sync.global(() -> dispatch(command, asPlayer, output));
        long wait = asPlayer.isPresent() ? 0 : collectMs;
        return dispatched
                .thenCompose(r -> CompletableFuture.supplyAsync(() -> r, CompletableFuture.delayedExecutor(wait, TimeUnit.MILLISECONDS)))
                .thenApply(r -> {
                    JsonArray lines = new JsonArray();
                    output.forEach(lines::add);
                    r.add("output", lines);
                    return (JsonElement) r;
                });
    }

    private static JsonObject dispatch(String command, Optional<String> asPlayer, List<String> output) {
        CommandSender sender;
        if (asPlayer.isPresent()) {
            Player player = Bukkit.getPlayerExact(asPlayer.get());
            if (player == null) {
                throw new AgentError("PLAYER_NOT_FOUND", "No online player named " + asPlayer.get(),
                        "Use world_query {action:'players'} to see who is online.");
            }
            sender = player;
        } else {
            sender = Bukkit.createCommandSender(c -> output.add(PlainTextComponentSerializer.plainText().serialize(c)));
        }
        String label = command.split(" ", 2)[0];
        boolean known = Bukkit.getCommandMap().getCommand(label) != null;
        boolean success;
        try {
            success = Bukkit.dispatchCommand(sender, command);
        } catch (CommandException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new AgentError("COMMAND_FAILED", String.valueOf(cause.getMessage()),
                    "Fix the command's arguments. Vanilla syntax changes between versions (e.g. gamerules are snake_case in 26.x).");
        }
        if (!success && !known) {
            throw new AgentError("UNKNOWN_COMMAND", "Unknown command: " + label,
                    "Check the spelling or plugin_manage {action:'list'}. Use the minecraft: prefix when a plugin overrides a vanilla command.");
        }
        JsonObject r = new JsonObject();
        r.addProperty("command", command);
        r.addProperty("success", success);
        if (asPlayer.isPresent()) {
            r.addProperty("sender", asPlayer.get());
            r.addProperty("note", "Feedback went to the player's chat; read it with the client chat tool.");
        }
        return r;
    }
}
```

In `Handlers.registerAll`, add:

```java
        d.register("server.command", p -> CommandHandler.handle(p, s));
```

- [ ] **Step 5: Run the integration suite**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:test :agent-paper:integrationTest --console=plain`
Expected: BUILD SUCCESSFUL. ConnectionIT (5) and CommandIT (7) pass.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle agent-paper test-fixtures
git commit -m "feat(paper): server.command with captured and late feedback; test-fixtures plugin"
```

---

### Task 5: server_eval (GraalJS)

**Files:**
- Create: `agent-paper/src/main/java/com/uxplima/craftwire/paper/script/{ScriptEngine,ValueJson,CapturedOutput}.java`
- Create: `agent-paper/src/main/java/com/uxplima/craftwire/paper/handlers/EvalHandler.java`
- Modify: `CraftwirePlugin.java` (adds the `scripts` field: reset on connect, close on disable), `Handlers.java`
- Test: `agent-paper/src/test/java/com/uxplima/craftwire/paper/script/ScriptEngineTest.java`
- Test: `agent-paper/src/integrationTest/java/com/uxplima/craftwire/paper/it/EvalIT.java`

**Interfaces:**
- Produces: `ScriptEngine(ClassLoader loader, String prelude)` with `eval(String code, long timeoutMs) → {result, output}`, `resetSession()` and `close()`. `ValueJson.toJson(Value) → JsonElement`.
- Produces: agent method `server.eval {code, timeoutMs?, reset?, at?: {world?, x, z}}` → `{result, output}`. Errors: `PERMISSION_DISABLED`, `TIMEOUT` and `EVAL_ERROR`.
- Produces: `CraftwirePlugin.scripts()`.

- [ ] **Step 1: Write the failing unit tests**

`agent-paper/src/test/java/com/uxplima/craftwire/paper/script/ScriptEngineTest.java`:

```java
package com.uxplima.craftwire.paper.script;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.uxplima.craftwire.core.AgentError;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScriptEngineTest {
    static ScriptEngine engine;   // engine startup takes about a second; share it

    @BeforeAll
    static void start() {
        engine = new ScriptEngine(ScriptEngineTest.class.getClassLoader(), "globalThis.greet = (n) => 'hi ' + n;");
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    @BeforeEach
    void fresh() {
        engine.resetSession();
    }

    static JsonElement result(String code) {
        return engine.eval(code, 5000).get("result");
    }

    @Test
    void convertsJsValuesToJson() {
        assertEquals(JsonParser.parseString("{\"a\":1,\"b\":[true,\"x\",null],\"c\":null,\"d\":1.5}"),
                result("({a: 1, b: [true, 'x', null], c: undefined, d: 1.5})"));
    }

    @Test
    void summarisesJavaObjects() {
        JsonObject o = result("new (Java.type('java.lang.StringBuilder'))('hey')").getAsJsonObject();
        assertEquals("java.lang.StringBuilder", o.get("class").getAsString());
        assertEquals("hey", o.get("toString").getAsString());
    }

    @Test
    void javaListsBecomeArrays() {
        assertEquals(JsonParser.parseString("[1,2]"), result("Java.type('java.util.List').of(1, 2)"));
    }

    @Test
    void functionsAreMarked() {
        assertEquals(new JsonPrimitive("[function]"), result("(() => 1)"));
    }

    @Test
    void deepValuesAreCut() {
        JsonObject r = result("({a: {b: {c: {d: {e: 1}}}}})").getAsJsonObject();
        assertTrue(r.getAsJsonObject("a").getAsJsonObject("b").getAsJsonObject("c").get("d").isJsonPrimitive());
    }

    @Test
    void capturesPrintAndConsoleLog() {
        JsonObject r = engine.eval("print('a'); console.log('b'); 3", 5000);
        assertEquals(3, r.get("result").getAsInt());
        assertEquals("a\nb\n", r.get("output").getAsString().replace("\r\n", "\n"));
    }

    @Test
    void outputIsCapped() {
        String out = engine.eval("for (let i = 0; i < 20000; i++) print('xxxxxxxxxx'); 1", 5000).get("output").getAsString();
        assertTrue(out.length() <= CapturedOutput.LIMIT + 40, "length " + out.length());
        assertTrue(out.endsWith("(output truncated)"));
    }

    @Test
    void globalsPersistUntilReset() {
        result("globalThis.n = 41");
        assertEquals(42, result("n + 1").getAsInt());
        engine.resetSession();
        assertEquals("undefined", result("typeof n").getAsString());
    }

    @Test
    void preludeGlobalsExist() {
        assertEquals("hi Ada", result("greet('Ada')").getAsString());
    }

    @Test
    void infiniteLoopTimesOutAndResetsGlobals() {
        result("globalThis.k = 1");
        long t0 = System.nanoTime();
        AgentError e = assertThrows(AgentError.class, () -> engine.eval("while (true) {}", 300));
        assertEquals("TIMEOUT", e.code());
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 5_000);
        assertEquals("undefined", result("typeof k").getAsString());
    }

    @Test
    void errorsReportLineAndColumn() {
        AgentError e = assertThrows(AgentError.class, () -> engine.eval("let a = 1;\nfoo.bar()", 5000));
        assertEquals("EVAL_ERROR", e.code());
        assertTrue(e.getMessage().contains("ReferenceError"), e.getMessage());
        assertTrue(e.getMessage().contains("line 2"), e.getMessage());
    }

    @Test
    void syntaxErrorsAreEvalErrors() {
        assertEquals("EVAL_ERROR", assertThrows(AgentError.class, () -> engine.eval("1 +", 5000)).code());
    }

    @Test
    void javaExceptionsNameTheJavaClass() {
        AgentError e = assertThrows(AgentError.class, () -> engine.eval("Java.type('java.lang.Integer').parseInt('x')", 5000));
        assertTrue(e.getMessage().contains("NumberFormatException"), e.getMessage());
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:test --console=plain`
Expected: compilation FAILS with `cannot find symbol: class ScriptEngine`.

- [ ] **Step 3: Implement the engine**

`agent-paper/src/main/java/com/uxplima/craftwire/paper/script/CapturedOutput.java`:

```java
package com.uxplima.craftwire.paper.script;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** print()/console output of one eval, capped so a chatty loop cannot exhaust memory. */
final class CapturedOutput extends OutputStream {
    static final int LIMIT = 65_536;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private boolean truncated;

    @Override
    public synchronized void write(int b) {
        if (buffer.size() < LIMIT) buffer.write(b);
        else truncated = true;
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) {
        int room = LIMIT - buffer.size();
        if (len > room) truncated = true;
        buffer.write(b, off, Math.max(0, Math.min(len, room)));
    }

    /** Returns what was written since the last call and clears it. */
    synchronized String take() {
        String s = buffer.toString(StandardCharsets.UTF_8) + (truncated ? "\n(output truncated)" : "");
        buffer.reset();
        truncated = false;
        return s;
    }
}
```

`agent-paper/src/main/java/com/uxplima/craftwire/paper/script/ValueJson.java`:

```java
package com.uxplima.craftwire.paper.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.graalvm.polyglot.Value;

/** Script results as JSON: JS values structurally, Java objects as {class, toString}. */
public final class ValueJson {
    static final int MAX_DEPTH = 4;
    static final int MAX_ITEMS = 1000;
    static final int MAX_TEXT = 1000;

    private ValueJson() {}

    public static JsonElement toJson(Value v) {
        return convert(v, 0);
    }

    private static JsonElement convert(Value v, int depth) {
        if (v == null || v.isNull()) return JsonNull.INSTANCE;
        if (v.isBoolean()) return new JsonPrimitive(v.asBoolean());
        if (v.isNumber()) {
            if (v.fitsInLong()) return new JsonPrimitive(v.asLong());
            double d = v.asDouble();
            return Double.isFinite(d) ? new JsonPrimitive(d) : new JsonPrimitive(String.valueOf(d));
        }
        if (v.isString()) return new JsonPrimitive(v.asString());
        if (depth >= MAX_DEPTH) return new JsonPrimitive(cut(v.toString()));
        if (v.hasArrayElements()) {
            JsonArray a = new JsonArray();
            long n = Math.min(v.getArraySize(), MAX_ITEMS);
            for (long i = 0; i < n; i++) a.add(convert(v.getArrayElement(i), depth + 1));
            return a;
        }
        if (v.isHostObject()) {
            JsonObject o = new JsonObject();
            o.addProperty("class", v.asHostObject().getClass().getName());
            o.addProperty("toString", cut(v.toString()));
            return o;
        }
        if (v.canExecute()) return new JsonPrimitive("[function]");
        if (v.hasMembers()) {
            JsonObject o = new JsonObject();
            int count = 0;
            for (String key : v.getMemberKeys()) {
                if (count++ == MAX_ITEMS) break;
                o.add(key, convert(v.getMember(key), depth + 1));
            }
            return o;
        }
        return new JsonPrimitive(cut(v.toString()));
    }

    private static String cut(String s) {
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT) + "…";
    }
}
```

`agent-paper/src/main/java/com/uxplima/craftwire/paper/script/ScriptEngine.java`:

```java
package com.uxplima.craftwire.paper.script;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.Value;

/**
 * GraalJS for server_eval: one context per hub session, so globals survive between calls until reset.
 * A watchdog cancels a run after its timeout and the cancelled context is discarded.
 */
public final class ScriptEngine implements AutoCloseable {
    private final ClassLoader loader;
    private final String prelude;
    private final CapturedOutput output = new CapturedOutput();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "craftwire-eval-watchdog");
        t.setDaemon(true);
        return t;
    });
    private Engine engine;
    private Context context;

    public ScriptEngine(ClassLoader loader, String prelude) {
        this.loader = loader;
        this.prelude = prelude;
    }

    /** Runs {@code code} on the calling thread and returns {result, output}. */
    public synchronized JsonObject eval(String code, long timeoutMs) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);   // Truffle discovers GraalJS through the context class loader
        try {
            Context ctx = context();
            output.take();   // drop anything printed between calls (e.g. by a scheduled callback)
            ScheduledFuture<?> kill = watchdog.schedule(() -> ctx.close(true), timeoutMs, TimeUnit.MILLISECONDS);
            try {
                Value value = ctx.eval(Source.newBuilder("js", code, "eval.js").buildLiteral());
                JsonObject r = new JsonObject();
                r.add("result", ValueJson.toJson(value));
                r.addProperty("output", output.take());
                return r;
            } catch (PolyglotException e) {
                if (e.isCancelled()) {
                    throw new AgentError("TIMEOUT", "The script ran longer than " + timeoutMs + " ms and was cancelled.",
                            "Globals were reset. Keep loops short on the server thread, or raise timeoutMs (max 60000).");
                }
                throw new AgentError("EVAL_ERROR", describe(e) + printed(output.take()),
                        "Fix the script and run it again. Globals set before the error are kept.");
            } finally {
                if (!kill.cancel(false)) context = null;   // the watchdog fired: that context is closed
            }
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    public synchronized void resetSession() {
        if (context != null) context.close(true);
        context = null;
    }

    @Override
    public synchronized void close() {
        resetSession();
        if (engine != null) engine.close();
        engine = null;
        watchdog.shutdownNow();
    }

    private Context context() {
        if (context == null) {
            if (engine == null) engine = Engine.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build();
            Context ctx = Context.newBuilder("js").engine(engine).allowAllAccess(true)
                    .hostClassLoader(loader).out(output).err(output).build();
            if (!prelude.isEmpty()) ctx.eval("js", prelude);
            context = ctx;
        }
        return context;
    }

    static String describe(PolyglotException e) {
        StringBuilder b = new StringBuilder(String.valueOf(e.getMessage()));
        SourceSection at = e.getSourceLocation();
        if (at != null) b.append(" (line ").append(at.getStartLine()).append(", column ").append(at.getStartColumn()).append(')');
        if (e.isHostException()) b.append(" [Java ").append(e.asHostException().getClass().getName()).append(']');
        List<String> frames = new ArrayList<>();
        for (PolyglotException.StackFrame f : e.getPolyglotStackTrace()) {
            if (f.isGuestFrame()) frames.add("  at " + f);
            if (frames.size() == 8) break;
        }
        if (!frames.isEmpty()) b.append('\n').append(String.join("\n", frames));
        return b.toString();
    }

    private static String printed(String out) {
        return out.isEmpty() ? "" : "\nOutput before the error:\n" + out;
    }
}
```

- [ ] **Step 4: Run the unit tests**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:test --console=plain`
Expected: all 13 ScriptEngineTest tests and the Task 3 tests pass.

If `capturesPrintAndConsoleLog` fails because GraalJS has no `print` builtin, add `globalThis.print = globalThis.print ?? ((...a) => console.log(...a));` to the test prelude and ledger a ruling. The plugin prelude in Step 6 defines the same fallback.

- [ ] **Step 5: Write the failing ITs**

`agent-paper/src/integrationTest/java/com/uxplima/craftwire/paper/it/EvalIT.java`:

```java
package com.uxplima.craftwire.paper.it;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class EvalIT {
    static JsonObject eval(String paramsJson) throws Exception {
        return ItEnv.get().hub.result("server.eval", paramsJson).getAsJsonObject();
    }

    @Test
    void seesTheBukkitApi() throws Exception {
        assertEquals("26.2", eval("{\"code\":\"server.getMinecraftVersion()\"}").get("result").getAsString());
    }

    @Test
    void locHelperUsesTheMainWorld() throws Exception {
        assertEquals("world", eval("{\"code\":\"loc(0, 64, 0).getWorld().getName()\"}").get("result").getAsString());
    }

    @Test
    void printOutputIsReturned() throws Exception {
        JsonObject r = eval("{\"code\":\"print('hello from js'); 7\"}");
        assertEquals(7, r.get("result").getAsInt());
        assertTrue(r.get("output").getAsString().contains("hello from js"));
    }

    @Test
    void runawayScriptIsCancelledAndTheServerKeepsTicking() throws Exception {
        ItHub hub = ItEnv.get().hub;
        assertEquals("TIMEOUT", hub.error("server.eval", "{\"code\":\"while(true){}\",\"timeoutMs\":500}").get("code").getAsString());
        assertTrue(hub.result("server.info", "{}").getAsJsonObject().has("tps"));   // the server thread is free again
    }

    @Test
    void scriptErrorsAreEvalErrors() throws Exception {
        assertEquals("EVAL_ERROR", ItEnv.get().hub.error("server.eval", "{\"code\":\"nope()\"}").get("code").getAsString());
    }

    @Test
    void atRunsOnTheOwningRegion() throws Exception {
        assertEquals(2, eval("{\"code\":\"1 + 1\",\"at\":{\"x\":100,\"z\":-100}}").get("result").getAsInt());
    }

    @Test
    void resetClearsGlobals() throws Exception {
        eval("{\"code\":\"globalThis.q = 5\"}");
        assertEquals("undefined", eval("{\"code\":\"typeof q\",\"reset\":true}").get("result").getAsString());
    }

    @Test
    void globalsResetWhenTheHubReconnects() throws Exception {
        ItHub hub = ItEnv.get().hub;
        eval("{\"code\":\"globalThis.r = 1\"}");
        hub.dropConnectionAndClearEvents();
        hub.awaitHello(30_000, () -> true);
        assertEquals("undefined", eval("{\"code\":\"typeof r\"}").get("result").getAsString());
    }
}
```

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:integrationTest --tests '*EvalIT' --console=plain`
Expected: all 8 FAIL with `UNKNOWN_METHOD`.

- [ ] **Step 6: Implement the handler and wire it**

`agent-paper/src/main/java/com/uxplima/craftwire/paper/handlers/EvalHandler.java`:

```java
package com.uxplima.craftwire.paper.handlers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.paper.AgentConfig;
import com.uxplima.craftwire.paper.Args;
import com.uxplima.craftwire.paper.CraftwirePlugin;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import org.bukkit.World;

public final class EvalHandler {
    /** Globals every eval sees. Wrapped in a function so user code may declare its own Bukkit/Location. */
    public static final String PRELUDE = """
            (() => {
              const Bukkit = Java.type('org.bukkit.Bukkit');
              const Location = Java.type('org.bukkit.Location');
              globalThis.server = Bukkit.getServer();
              globalThis.player = (name) => Bukkit.getPlayerExact(name);
              globalThis.plugin = (name) => Bukkit.getPluginManager().getPlugin(name);
              globalThis.loc = (x, y, z, world) =>
                new Location(world ? Bukkit.getWorld(world) : Bukkit.getWorlds().get(0), x, y, z);
              if (typeof globalThis.print !== 'function') globalThis.print = (...a) => console.log(...a);
            })();
            """;

    private EvalHandler() {}

    static CompletableFuture<JsonElement> handle(JsonObject p, CraftwirePlugin plugin) {
        AgentConfig config = plugin.agentConfig();
        config.require(config.allowEval(), "allow-eval");
        String code = Args.string(p, "code");
        long timeoutMs = Math.clamp(Args.optLong(p, "timeoutMs").orElse(5000L), 100L, 60_000L);
        boolean reset = Args.bool(p, "reset", false);
        Callable<JsonElement> run = () -> {
            if (reset) plugin.scripts().resetSession();
            return plugin.scripts().eval(code, timeoutMs);
        };
        if (!p.has("at") || !p.get("at").isJsonObject()) return plugin.sync().global(run);
        JsonObject at = p.getAsJsonObject("at");
        World world = Args.world(at);
        int x = (int) Math.floor(Args.number(at, "x"));
        int z = (int) Math.floor(Args.number(at, "z"));
        return plugin.sync().region(world, x >> 4, z >> 4, run);
    }
}
```

`CraftwirePlugin.java`:
- Add `import com.uxplima.craftwire.paper.handlers.EvalHandler;` and `import com.uxplima.craftwire.paper.script.ScriptEngine;`.
- Add the field `private ScriptEngine scripts;`.
- In `onEnable`, before `Handlers.registerAll(this);`:

```java
        scripts = new ScriptEngine(getClass().getClassLoader(), EvalHandler.PRELUDE);
```

In `onHubConnected`, after the info log line:

```java
        scripts.resetSession();   // a new hub session starts with fresh script globals
```

In `onDisable`, first line:

```java
        if (scripts != null) scripts.close();
```

and add the accessor:

```java
    public ScriptEngine scripts() {
        return scripts;
    }
```

`Handlers.registerAll`, add:

```java
        d.register("server.eval", p -> EvalHandler.handle(p, plugin));
```

- [ ] **Step 7: Run unit and integration suites**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:test :agent-paper:integrationTest --console=plain`
Expected: BUILD SUCCESSFUL. All unit tests pass, and ConnectionIT, CommandIT and EvalIT (8) pass.

- [ ] **Step 8: Commit**

```bash
git add agent-paper
git commit -m "feat(paper): server.eval with GraalJS, watchdog timeout and per-session globals"
```

---

### Task 6: world_query

**Files:**
- Create: `agent-paper/src/main/java/com/uxplima/craftwire/paper/world/{Box,ChunkWork,Blocks,BlockMatcher}.java`
- Create: `agent-paper/src/main/java/com/uxplima/craftwire/paper/handlers/WorldQueryHandler.java`
- Modify: `Handlers.java`
- Test: `agent-paper/src/test/java/com/uxplima/craftwire/paper/world/BoxTest.java`
- Test: `agent-paper/src/integrationTest/java/com/uxplima/craftwire/paper/it/WorldQueryIT.java`

**Interfaces:**
- Produces: `Box(minX, minY, minZ, maxX, maxY, maxZ)` with `of(...)`, `from(JsonObject{min,max})`, `sizeX/Y/Z()`, `volume()`, `chunks() → List<int[]{cx,cz}>`, `clipToChunk(cx, cz)`, `clampY(min, max)` and `toJson()`.
- Produces: `ChunkWork.forChunks(Sync, World, List<int[]>, BiFunction<Integer,Integer,R>)` and `ChunkWork.forEachChunk(Sync, World, Box, Function<Box,R>) → CompletableFuture<List<R>>`.
- Produces: `Blocks.parse(String) → BlockData` (INVALID_PARAMS on bad input) and `BlockMatcher.parse(String)`, which implements `Predicate<BlockData>`.
- Produces: agent method `world.query` with actions `block`, `region`, `entities`, `players` and `find_block`. Errors: `QUERY_TOO_LARGE`, `WORLD_NOT_FOUND` and `INVALID_PARAMS`.

- [ ] **Step 1: Write the failing unit tests**

`agent-paper/src/test/java/com/uxplima/craftwire/paper/world/BoxTest.java`:

```java
package com.uxplima.craftwire.paper.world;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.uxplima.craftwire.core.AgentError;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoxTest {
    @Test
    void normalisesCornersAndCountsBlocks() {
        Box b = Box.of(5, 10, 5, 1, 2, 3);
        assertEquals(new Box(1, 2, 3, 5, 10, 5), b);
        assertEquals(5 * 9 * 3, b.volume());
    }

    @Test
    void chunksCoverNegativeCoordinatesInXThenZOrder() {
        List<int[]> chunks = Box.of(-1, 0, -1, 16, 0, 0).chunks();
        assertEquals(List.of("-1,-1", "-1,0", "0,-1", "0,0", "1,-1", "1,0"),
                chunks.stream().map(c -> c[0] + "," + c[1]).toList());
    }

    @Test
    void clipsToOneChunk() {
        Box b = Box.of(10, 0, 10, 20, 5, 20);
        assertEquals(new Box(10, 0, 10, 15, 5, 15), b.clipToChunk(0, 0));
        assertEquals(new Box(16, 0, 16, 20, 5, 20), b.clipToChunk(1, 1));
        assertNull(b.clipToChunk(2, 0));
    }

    @Test
    void clampsToBuildHeight() {
        assertEquals(new Box(0, -64, 0, 0, 319, 0), Box.of(0, -100, 0, 0, 400, 0).clampY(-64, 319));
        assertNull(Box.of(0, 500, 0, 0, 600, 0).clampY(-64, 319));
    }

    @Test
    void readsMinAndMaxFromJson() {
        Box b = Box.from(JsonParser.parseString("{\"min\":{\"x\":3,\"y\":2,\"z\":1},\"max\":{\"x\":0,\"y\":0,\"z\":0}}").getAsJsonObject());
        assertEquals(new Box(0, 0, 0, 3, 2, 1), b);
        assertEquals(b, Box.from(b.toJson()));
        AgentError e = assertThrows(AgentError.class, () -> Box.from(JsonParser.parseString("{\"min\":{\"x\":0,\"y\":0,\"z\":0}}").getAsJsonObject()));
        assertEquals("INVALID_PARAMS", e.code());
    }
}
```

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:test --console=plain`
Expected: compilation FAILS with `cannot find symbol: class Box`.

- [ ] **Step 2: Implement Box, ChunkWork, Blocks, BlockMatcher**

`agent-paper/src/main/java/com/uxplima/craftwire/paper/world/Box.java`:

```java
package com.uxplima.craftwire.paper.world;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.paper.Args;
import java.util.ArrayList;
import java.util.List;

/** An inclusive block box. */
public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public static Box of(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Box(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    /** Reads {min:{x,y,z}, max:{x,y,z}}; the corners may be given in any order. */
    public static Box from(JsonObject p) {
        JsonObject min = Args.object(p, "min");
        JsonObject max = Args.object(p, "max");
        return of(Args.integer(min, "x"), Args.integer(min, "y"), Args.integer(min, "z"),
                Args.integer(max, "x"), Args.integer(max, "y"), Args.integer(max, "z"));
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    /** Chunk coordinates this box touches, as {x, z} pairs, x-major. */
    public List<int[]> chunks() {
        List<int[]> out = new ArrayList<>();
        for (int cx = Math.floorDiv(minX, 16); cx <= Math.floorDiv(maxX, 16); cx++) {
            for (int cz = Math.floorDiv(minZ, 16); cz <= Math.floorDiv(maxZ, 16); cz++) out.add(new int[] {cx, cz});
        }
        return out;
    }

    /** The part of this box inside chunk (cx, cz), or null when they do not overlap. */
    public Box clipToChunk(int cx, int cz) {
        int x0 = Math.max(minX, cx * 16), x1 = Math.min(maxX, cx * 16 + 15);
        int z0 = Math.max(minZ, cz * 16), z1 = Math.min(maxZ, cz * 16 + 15);
        return x0 > x1 || z0 > z1 ? null : new Box(x0, minY, z0, x1, maxY, z1);
    }

    /** This box limited to build height [worldMinY, worldMaxY], or null when nothing is left. */
    public Box clampY(int worldMinY, int worldMaxY) {
        int y0 = Math.max(minY, worldMinY), y1 = Math.min(maxY, worldMaxY);
        return y0 > y1 ? null : new Box(minX, y0, minZ, maxX, y1, maxZ);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.add("min", corner(minX, minY, minZ));
        o.add("max", corner(maxX, maxY, maxZ));
        return o;
    }

    private static JsonObject corner(int x, int y, int z) {
        JsonObject c = new JsonObject();
        c.addProperty("x", x);
        c.addProperty("y", y);
        c.addProperty("z", z);
        return c;
    }
}
```

`agent-paper/src/main/java/com/uxplima/craftwire/paper/world/ChunkWork.java`:

```java
package com.uxplima.craftwire.paper.world;

import com.uxplima.craftwire.paper.Sync;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.bukkit.Chunk;
import org.bukkit.World;

/** Splits block work by chunk: loads each chunk asynchronously, then runs its part on the thread that owns it. */
public final class ChunkWork {
    private ChunkWork() {}

    public static <R> CompletableFuture<List<R>> forChunks(Sync sync, World world, List<int[]> chunks, BiFunction<Integer, Integer, R> work) {
        // Start every load from the server thread, then run each part where its chunk lives.
        CompletableFuture<List<CompletableFuture<Chunk>>> loads =
                sync.global(() -> chunks.stream().map(c -> world.getChunkAtAsync(c[0], c[1])).toList());
        return loads.thenCompose(started -> {
            List<CompletableFuture<R>> parts = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                int cx = chunks.get(i)[0], cz = chunks.get(i)[1];
                parts.add(started.get(i).thenCompose(chunk -> sync.region(world, cx, cz, () -> work.apply(cx, cz))));
            }
            return CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new))
                    .thenApply(v -> parts.stream().map(CompletableFuture::join).toList());
        });
    }

    public static <R> CompletableFuture<List<R>> forEachChunk(Sync sync, World world, Box box, Function<Box, R> work) {
        return forChunks(sync, world, box.chunks(), (cx, cz) -> work.apply(box.clipToChunk(cx, cz)));
    }
}
```

`agent-paper/src/main/java/com/uxplima/craftwire/paper/world/Blocks.java`:

```java
package com.uxplima.craftwire.paper.world;

import com.uxplima.craftwire.core.AgentError;
import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;

public final class Blocks {
    private Blocks() {}

    /** Parses "stone", "minecraft:stone" or "oak_stairs[facing=east]". */
    public static BlockData parse(String spec) {
        try {
            return Bukkit.createBlockData(spec.strip());
        } catch (IllegalArgumentException e) {
            throw unknown(spec);
        }
    }

    static AgentError unknown(String spec) {
        return new AgentError("INVALID_PARAMS", "Unknown block: " + spec,
                "Use a block id such as minecraft:stone or a state such as oak_stairs[facing=east].");
    }
}
```

`agent-paper/src/main/java/com/uxplima/craftwire/paper/world/BlockMatcher.java`:

```java
package com.uxplima.craftwire.paper.world;

import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/** Matches by id ("chest") or by state ("oak_stairs[facing=east]": only the listed properties must match). */
public final class BlockMatcher implements Predicate<BlockData> {
    private final Material material;
    private final BlockData state;

    private BlockMatcher(Material material, BlockData state) {
        this.material = material;
        this.state = state;
    }

    public static BlockMatcher parse(String spec) {
        if (spec.contains("[")) return new BlockMatcher(null, Blocks.parse(spec));
        Material m = Material.matchMaterial(spec.strip());
        if (m == null || !m.isBlock()) throw Blocks.unknown(spec);
        return new BlockMatcher(m, null);
    }

    @Override
    public boolean test(BlockData data) {
        return material != null ? data.getMaterial() == material : data.matches(state);
    }
}
```

- [ ] **Step 3: Run the unit tests**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:test --console=plain`
Expected: BoxTest (5) and all earlier unit tests pass.

- [ ] **Step 4: Write the failing ITs**

`agent-paper/src/integrationTest/java/com/uxplima/craftwire/paper/it/WorldQueryIT.java`:

```java
package com.uxplima.craftwire.paper.it;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WorldQueryIT {
    static ItHub hub;

    /** Far from spawn (chunks start unloaded); gold at 1007 and 1008 sits in two different chunks. */
    @BeforeAll
    static void build() throws Exception {
        hub = ItEnv.get().hub;
        hub.result("server.eval", "{\"code\":"
                + "\"const W = server.getWorlds().get(0), M = Java.type('org.bukkit.Material');"
                + " for (let x = 1000; x <= 1002; x++) for (let z = 1000; z <= 1002; z++) W.getBlockAt(x, -50, z).setType(M.GOLD_BLOCK);"
                + " W.getBlockAt(1007, -50, 1007).setType(M.GOLD_BLOCK);"
                + " W.getBlockAt(1008, -50, 1008).setType(M.GOLD_BLOCK);"
                + " W.spawnEntity(loc(1001.5, -49, 1001.5), Java.type('org.bukkit.entity.EntityType').ARMOR_STAND); 'ok'\"}");
    }

    static JsonObject query(String paramsJson) throws Exception {
        return hub.result("world.query", paramsJson).getAsJsonObject();
    }

    @Test
    void blockReportsItsState() throws Exception {
        assertEquals("minecraft:gold_block", query("{\"action\":\"block\",\"x\":1000,\"y\":-50,\"z\":1000}").get("block").getAsString());
    }

    @Test
    void regionReturnsPaletteIndicesAndCounts() throws Exception {
        JsonObject r = query("{\"action\":\"region\",\"min\":{\"x\":1000,\"y\":-50,\"z\":1000},\"max\":{\"x\":1002,\"y\":-49,\"z\":1002}}");
        assertEquals("{\"x\":3,\"y\":2,\"z\":3}", r.get("size").toString());
        assertEquals(18, r.getAsJsonArray("blocks").size());
        assertEquals(9, r.getAsJsonObject("counts").get("minecraft:gold_block").getAsInt());
        assertEquals(9, r.getAsJsonObject("counts").get("minecraft:air").getAsInt());
        int first = r.getAsJsonArray("blocks").get(0).getAsInt();
        assertEquals("minecraft:gold_block", r.getAsJsonArray("palette").get(first).getAsString());
    }

    @Test
    void regionOverTheLimitIsRejected() throws Exception {
        JsonObject e = hub.error("world.query", "{\"action\":\"region\",\"min\":{\"x\":0,\"y\":0,\"z\":0},\"max\":{\"x\":100,\"y\":100,\"z\":100}}");
        assertEquals("QUERY_TOO_LARGE", e.get("code").getAsString());
    }

    @Test
    void findBlockSearchesAcrossChunkBorders() throws Exception {
        JsonObject r = query("{\"action\":\"find_block\",\"block\":\"gold_block\",\"min\":{\"x\":995,\"y\":-55,\"z\":995},\"max\":{\"x\":1010,\"y\":-45,\"z\":1010},\"limit\":100}");
        JsonArray m = r.getAsJsonArray("matches");
        assertEquals(11, m.size());
        assertFalse(r.get("truncated").getAsBoolean());
        assertTrue(m.toString().contains("\"x\":1008,\"y\":-50,\"z\":1008"), m.toString());
    }

    @Test
    void findBlockHonoursTheLimit() throws Exception {
        JsonObject r = query("{\"action\":\"find_block\",\"block\":\"minecraft:gold_block\",\"min\":{\"x\":995,\"y\":-55,\"z\":995},\"max\":{\"x\":1010,\"y\":-45,\"z\":1010},\"limit\":2}");
        assertEquals(2, r.getAsJsonArray("matches").size());
        assertTrue(r.get("truncated").getAsBoolean());
    }

    @Test
    void entitiesFilterByType() throws Exception {
        JsonObject r = query("{\"action\":\"entities\",\"type\":\"armor_stand\",\"min\":{\"x\":995,\"y\":-60,\"z\":995},\"max\":{\"x\":1010,\"y\":-40,\"z\":1010}}");
        JsonArray list = r.getAsJsonArray("entities");
        assertEquals(1, list.size());
        assertEquals("minecraft:armor_stand", list.get(0).getAsJsonObject().get("type").getAsString());
    }

    @Test
    void playersIsEmptyWithNobodyOnline() throws Exception {
        assertEquals(0, query("{\"action\":\"players\"}").getAsJsonArray("players").size());
    }

    @Test
    void unknownWorldIsWorldNotFound() throws Exception {
        assertEquals("WORLD_NOT_FOUND", hub.error("world.query", "{\"action\":\"block\",\"world\":\"nope\",\"x\":0,\"y\":0,\"z\":0}").get("code").getAsString());
    }

    @Test
    void unknownBlockIsInvalidParams() throws Exception {
        assertEquals("INVALID_PARAMS", hub.error("world.query",
                "{\"action\":\"find_block\",\"block\":\"not_a_block\",\"min\":{\"x\":0,\"y\":0,\"z\":0},\"max\":{\"x\":1,\"y\":1,\"z\":1}}").get("code").getAsString());
    }
}
```

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:integrationTest --tests '*WorldQueryIT' --console=plain`
Expected: all 9 FAIL with `UNKNOWN_METHOD`.

- [ ] **Step 5: Implement the handler**

`agent-paper/src/main/java/com/uxplima/craftwire/paper/handlers/WorldQueryHandler.java`:

```java
package com.uxplima.craftwire.paper.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.paper.Args;
import com.uxplima.craftwire.paper.Sync;
import com.uxplima.craftwire.paper.world.BlockMatcher;
import com.uxplima.craftwire.paper.world.Box;
import com.uxplima.craftwire.paper.world.ChunkWork;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

final class WorldQueryHandler {
    static final long MAX_REGION = 32_768;
    static final long MAX_SCAN = 4_000_000;
    static final int MAX_ENTITY_CHUNKS = 1024;

    private WorldQueryHandler() {}

    static CompletableFuture<JsonElement> handle(JsonObject p, Sync sync) {
        String action = Args.string(p, "action");
        return switch (action) {
            case "players" -> sync.global(WorldQueryHandler::players);
            case "block" -> block(p, sync);
            case "region" -> region(p, sync);
            case "entities" -> entities(p, sync);
            case "find_block" -> findBlock(p, sync);
            default -> throw Args.invalid("Unknown action: " + action);
        };
    }

    private static CompletableFuture<JsonElement> block(JsonObject p, Sync sync) {
        World w = Args.world(p);
        int x = Args.integer(p, "x"), y = Args.integer(p, "y"), z = Args.integer(p, "z");
        return ChunkWork.forEachChunk(sync, w, Box.of(x, y, z, x, y, z), part -> {
            JsonObject o = new JsonObject();
            o.addProperty("world", w.getName());
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("z", z);
            o.addProperty("block", w.getBlockAt(x, y, z).getBlockData().getAsString());
            return (JsonElement) o;
        }).thenApply(list -> list.get(0));
    }

    private record Cell(int index, String state) {}

    private static CompletableFuture<JsonElement> region(JsonObject p, Sync sync) {
        World w = Args.world(p);
        Box box = Box.from(p);
        if (box.volume() > MAX_REGION) {
            throw new AgentError("QUERY_TOO_LARGE", "The region covers " + box.volume() + " blocks; the limit is " + MAX_REGION,
                    "Query a smaller box, or use find_block to locate specific blocks in a large area.");
        }
        return ChunkWork.forEachChunk(sync, w, box, part -> readCells(w, box, part)).thenApply(parts -> assemble(box, parts));
    }

    private static List<Cell> readCells(World w, Box whole, Box part) {
        ChunkSnapshot snap = w.getChunkAt(part.minX() >> 4, part.minZ() >> 4).getChunkSnapshot(false, false, false);
        List<Cell> cells = new ArrayList<>((int) part.volume());
        for (int y = part.minY(); y <= part.maxY(); y++) {
            for (int z = part.minZ(); z <= part.maxZ(); z++) {
                for (int x = part.minX(); x <= part.maxX(); x++) {
                    int index = ((y - whole.minY()) * whole.sizeZ() + (z - whole.minZ())) * whole.sizeX() + (x - whole.minX());
                    cells.add(new Cell(index, state(snap, w, x, y, z)));
                }
            }
        }
        return cells;
    }

    private static String state(ChunkSnapshot snap, World w, int x, int y, int z) {
        if (y < w.getMinHeight() || y >= w.getMaxHeight()) return "minecraft:void_air";
        return snap.getBlockData(x & 15, y, z & 15).getAsString();
    }

    private static JsonElement assemble(Box box, List<List<Cell>> parts) {
        int[] blocks = new int[(int) box.volume()];
        Map<String, Integer> palette = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Cell> all = parts.stream().flatMap(List::stream).sorted(Comparator.comparingInt(Cell::index)).toList();
        for (Cell c : all) {
            blocks[c.index()] = palette.computeIfAbsent(c.state(), s -> palette.size());
            counts.merge(c.state(), 1, Integer::sum);
        }
        JsonObject o = box.toJson();
        JsonObject size = new JsonObject();
        size.addProperty("x", box.sizeX());
        size.addProperty("y", box.sizeY());
        size.addProperty("z", box.sizeZ());
        o.add("size", size);
        JsonArray pal = new JsonArray();
        palette.keySet().forEach(pal::add);
        o.add("palette", pal);
        JsonArray arr = new JsonArray(blocks.length);
        for (int b : blocks) arr.add(b);
        o.add("blocks", arr);
        JsonObject cnt = new JsonObject();
        counts.forEach(cnt::addProperty);
        o.add("counts", cnt);
        return o;
    }

    private static CompletableFuture<JsonElement> entities(JsonObject p, Sync sync) {
        World w = Args.world(p);
        Box box = Box.from(p);
        if (box.chunks().size() > MAX_ENTITY_CHUNKS) {
            throw new AgentError("QUERY_TOO_LARGE", "The box touches " + box.chunks().size() + " chunks; the limit is " + MAX_ENTITY_CHUNKS,
                    "Search a smaller box.");
        }
        Optional<String> type = Args.optString(p, "type")
                .map(t -> t.toLowerCase(Locale.ROOT))
                .map(t -> t.contains(":") ? t : "minecraft:" + t);
        int limit = Args.optInt(p, "limit").orElse(100);
        return ChunkWork.forEachChunk(sync, w, box, part -> {
            BoundingBox bb = new BoundingBox(part.minX(), part.minY(), part.minZ(), part.maxX() + 1, part.maxY() + 1, part.maxZ() + 1);
            List<JsonObject> found = new ArrayList<>();
            for (Entity e : w.getNearbyEntities(bb)) {
                if (type.isPresent() && !e.getType().getKey().toString().equals(type.get())) continue;
                found.add(entityJson(e));
            }
            return found;
        }).thenApply(parts -> {
            Set<String> seen = new LinkedHashSet<>();
            JsonArray list = new JsonArray();
            int total = 0;
            for (List<JsonObject> part : parts) {
                for (JsonObject e : part) {
                    if (!seen.add(e.get("uuid").getAsString())) continue;   // an entity on a chunk border is seen twice
                    total++;
                    if (list.size() < limit) list.add(e);
                }
            }
            JsonObject r = new JsonObject();
            r.add("entities", list);
            r.addProperty("truncated", total > limit);
            return (JsonElement) r;
        });
    }

    private static JsonElement players() {
        JsonArray list = new JsonArray();
        for (Player pl : Bukkit.getOnlinePlayers()) {
            JsonObject o = entityJson(pl);
            o.addProperty("world", pl.getWorld().getName());
            o.addProperty("gameMode", pl.getGameMode().name().toLowerCase(Locale.ROOT));
            o.addProperty("health", pl.getHealth());
            o.addProperty("op", pl.isOp());
            list.add(o);
        }
        JsonObject r = new JsonObject();
        r.add("players", list);
        return r;
    }

    private static JsonObject entityJson(Entity e) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", e.getUniqueId().toString());
        o.addProperty("type", e.getType().getKey().toString());
        o.addProperty("name", e.getName());
        o.addProperty("x", round(e.getLocation().getX()));
        o.addProperty("y", round(e.getLocation().getY()));
        o.addProperty("z", round(e.getLocation().getZ()));
        o.addProperty("yaw", round(e.getLocation().getYaw()));
        o.addProperty("pitch", round(e.getLocation().getPitch()));
        return o;
    }

    private static CompletableFuture<JsonElement> findBlock(JsonObject p, Sync sync) {
        World w = Args.world(p);
        Box requested = Box.from(p);
        BlockMatcher match = BlockMatcher.parse(Args.string(p, "block"));
        int limit = Args.optInt(p, "limit").orElse(100);
        Box box = requested.clampY(w.getMinHeight(), w.getMaxHeight() - 1);
        if (box == null) return CompletableFuture.completedFuture(findResult(List.of(), limit));
        if (box.volume() > MAX_SCAN) {
            throw new AgentError("QUERY_TOO_LARGE", "find_block would scan " + box.volume() + " blocks; the limit is " + MAX_SCAN,
                    "Search a smaller box.");
        }
        return ChunkWork.forEachChunk(sync, w, box, part -> scan(w, part, match, limit))
                .thenApply(parts -> findResult(parts, limit));
    }

    private static List<JsonObject> scan(World w, Box part, BlockMatcher match, int limit) {
        ChunkSnapshot snap = w.getChunkAt(part.minX() >> 4, part.minZ() >> 4).getChunkSnapshot(false, false, false);
        List<JsonObject> hits = new ArrayList<>();
        for (int y = part.minY(); y <= part.maxY(); y++) {
            for (int z = part.minZ(); z <= part.maxZ(); z++) {
                for (int x = part.minX(); x <= part.maxX(); x++) {
                    BlockData d = snap.getBlockData(x & 15, y, z & 15);
                    if (!match.test(d)) continue;
                    JsonObject hit = new JsonObject();
                    hit.addProperty("x", x);
                    hit.addProperty("y", y);
                    hit.addProperty("z", z);
                    hit.addProperty("block", d.getAsString());
                    hits.add(hit);
                    if (hits.size() > limit) return hits;   // one extra tells the caller there were more
                }
            }
        }
        return hits;
    }

    private static JsonElement findResult(List<List<JsonObject>> parts, int limit) {
        JsonArray matches = new JsonArray();
        int total = 0;
        for (List<JsonObject> part : parts) {
            for (JsonObject hit : part) {
                total++;
                if (matches.size() < limit) matches.add(hit);
            }
        }
        JsonObject r = new JsonObject();
        r.add("matches", matches);
        r.addProperty("truncated", total > limit);
        return r;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
```

`Handlers.registerAll`, add:

```java
        d.register("world.query", p -> WorldQueryHandler.handle(p, s));
```

- [ ] **Step 6: Run unit and integration suites**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:test :agent-paper:integrationTest --console=plain`
Expected: BUILD SUCCESSFUL. Every IT class passes, including WorldQueryIT (9).

- [ ] **Step 7: Commit**

```bash
git add agent-paper
git commit -m "feat(paper): world.query (block, region, entities, players, find_block) chunk by chunk"
```

---

### Task 7: world_edit with snapshots and schematics

**Files:**
- Create: `agent-paper/src/main/java/com/uxplima/craftwire/paper/world/{Placement,SnapshotStore}.java`
- Create: `agent-paper/src/main/java/com/uxplima/craftwire/paper/handlers/WorldEditHandler.java`
- Modify: `CraftwirePlugin.java` (add `snapshots()` and `structuresDir()`), `Handlers.java`
- Test: `agent-paper/src/test/java/com/uxplima/craftwire/paper/world/PlacementTest.java`
- Test: `agent-paper/src/integrationTest/java/com/uxplima/craftwire/paper/it/WorldEditIT.java`

**Interfaces:**
- Consumes: `Box`, `ChunkWork`, `Blocks`, `BlockMatcher` (Task 6), `AgentConfig.maxEditVolume()`.
- Produces: `Placement.footprint(atX, atY, atZ, sizeX, sizeY, sizeZ, StructureRotation, Mirror) → Box`, `Placement.checkName(String)`, `Placement.rotation(String)` and `Placement.mirror(String)`.
- Produces: `SnapshotStore(Path dir)` with `save(World, Box) → id`, `find(id) → Snapshot(id, world, box)` and `restore(Snapshot, World)`.
- Produces: agent method `world.edit` with actions `set_blocks`, `fill`, `snapshot`, `restore`, `save_schematic` and `paste_schematic`.
  - Results carry `snapshotId` when a snapshot was taken.
  - Errors: `PERMISSION_DISABLED`, `EDIT_TOO_LARGE`, `SNAPSHOT_NOT_FOUND`, `SCHEMATIC_NOT_FOUND` and `INVALID_PARAMS`.

- [ ] **Step 1: Write the failing unit tests**

`agent-paper/src/test/java/com/uxplima/craftwire/paper/world/PlacementTest.java`:

```java
package com.uxplima.craftwire.paper.world;

import static org.junit.jupiter.api.Assertions.*;

import com.uxplima.craftwire.core.AgentError;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.junit.jupiter.api.Test;

class PlacementTest {
    @Test
    void noRotationKeepsTheBoxAtTheOrigin() {
        assertEquals(new Box(10, 5, 10, 12, 6, 13),
                Placement.footprint(10, 5, 10, 3, 2, 4, StructureRotation.NONE, Mirror.NONE));
    }

    @Test
    void clockwise90TurnsAroundTheOrigin() {
        // vanilla: (x, z) -> (-z, x); the far corner (2, 3) lands at (-3, 2)
        assertEquals(new Box(7, 5, 10, 10, 6, 12),
                Placement.footprint(10, 5, 10, 3, 2, 4, StructureRotation.CLOCKWISE_90, Mirror.NONE));
    }

    @Test
    void counterclockwise90AndHalfTurn() {
        assertEquals(new Box(10, 0, 8, 13, 0, 10), Placement.footprint(10, 0, 10, 3, 1, 4, StructureRotation.COUNTERCLOCKWISE_90, Mirror.NONE));
        assertEquals(new Box(8, 0, 7, 10, 0, 10), Placement.footprint(10, 0, 10, 3, 1, 4, StructureRotation.CLOCKWISE_180, Mirror.NONE));
    }

    @Test
    void mirrorFrontBackFlipsX() {
        assertEquals(new Box(-2, 0, 0, 0, 0, 0), Placement.footprint(0, 0, 0, 3, 1, 1, StructureRotation.NONE, Mirror.FRONT_BACK));
    }

    @Test
    void namesRejectTraversal() {
        assertEquals("plains_house-2", Placement.checkName("plains_house-2"));
        for (String bad : new String[] {"../x", "a/b", "a\\b", "", "x".repeat(65), "c:evil"}) {
            AgentError e = assertThrows(AgentError.class, () -> Placement.checkName(bad), bad);
            assertEquals("INVALID_PARAMS", e.code());
        }
    }

    @Test
    void parsesToolRotationAndMirrorNames() {
        assertEquals(StructureRotation.CLOCKWISE_90, Placement.rotation("clockwise_90"));
        assertEquals(StructureRotation.NONE, Placement.rotation("none"));
        assertEquals(Mirror.LEFT_RIGHT, Placement.mirror("left_right"));
        assertThrows(AgentError.class, () -> Placement.rotation("sideways"));
    }
}
```

Check the expected boxes:
- CCW90: (x,z)→(z,−x). Corner (2,3)→(3,−2), so x is 10..13 and z is 8..10. ✓
- 180: (2,3)→(−2,−3), so x is 8..10 and z is 7..10. ✓

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:test --console=plain`
Expected: compilation FAILS with `cannot find symbol: class Placement`.

- [ ] **Step 2: Implement Placement and SnapshotStore**

`agent-paper/src/main/java/com/uxplima/craftwire/paper/world/Placement.java`:

```java
package com.uxplima.craftwire.paper.world;

import com.uxplima.craftwire.paper.Args;
import java.util.regex.Pattern;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;

/** Where a structure lands when placed at a point with a rotation and mirror (vanilla pivot: its origin). */
public final class Placement {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private Placement() {}

    public static String checkName(String name) {
        if (!NAME.matcher(name).matches()) {
            throw Args.invalid("Schematic names may only use letters, digits, _ and - (1-64 characters): " + name);
        }
        return name;
    }

    public static Box footprint(int atX, int atY, int atZ, int sizeX, int sizeY, int sizeZ, StructureRotation rotation, Mirror mirror) {
        int[] a = transform(0, 0, rotation, mirror);
        int[] b = transform(sizeX - 1, sizeZ - 1, rotation, mirror);
        return Box.of(atX + a[0], atY, atZ + a[1], atX + b[0], atY + sizeY - 1, atZ + b[1]);
    }

    /** Vanilla StructureTemplate.transform with pivot 0: mirror first, then rotate. */
    static int[] transform(int x, int z, StructureRotation rotation, Mirror mirror) {
        switch (mirror) {
            case LEFT_RIGHT -> z = -z;
            case FRONT_BACK -> x = -x;
            default -> { }
        }
        return switch (rotation) {
            case CLOCKWISE_90 -> new int[] {-z, x};
            case CLOCKWISE_180 -> new int[] {-x, -z};
            case COUNTERCLOCKWISE_90 -> new int[] {z, -x};
            default -> new int[] {x, z};
        };
    }

    public static StructureRotation rotation(String s) {
        return switch (s) {
            case "none" -> StructureRotation.NONE;
            case "clockwise_90" -> StructureRotation.CLOCKWISE_90;
            case "clockwise_180" -> StructureRotation.CLOCKWISE_180;
            case "counterclockwise_90" -> StructureRotation.COUNTERCLOCKWISE_90;
            default -> throw Args.invalid("Unknown rotation: " + s);
        };
    }

    public static Mirror mirror(String s) {
        return switch (s) {
            case "none" -> Mirror.NONE;
            case "left_right" -> Mirror.LEFT_RIGHT;
            case "front_back" -> Mirror.FRONT_BACK;
            default -> throw Args.invalid("Unknown mirror: " + s);
        };
    }
}
```

`agent-paper/src/main/java/com/uxplima/craftwire/paper/world/SnapshotStore.java`:

```java
package com.uxplima.craftwire.paper.world;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.uxplima.craftwire.core.AgentError;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;

/** Saves areas as vanilla structure files so they can be put back exactly; keeps the newest {@link #KEEP}. */
public final class SnapshotStore {
    static final int KEEP = 20;

    public record Snapshot(String id, String world, Box box) {}

    private final Path dir;
    private final AtomicLong counter = new AtomicLong();

    public SnapshotStore(Path dir) {
        this.dir = dir;
    }

    /** Run on the thread that owns the box, with its chunks loaded. */
    public String save(World world, Box box) throws IOException {
        Files.createDirectories(dir);
        String id = "snap-" + System.currentTimeMillis() + "-" + counter.incrementAndGet();
        Bukkit.getStructureManager().saveStructure(dir.resolve(id + ".nbt").toFile(), capture(world, box, false));
        JsonObject meta = box.toJson();
        meta.addProperty("world", world.getName());
        Files.writeString(dir.resolve(id + ".json"), meta.toString());
        prune();
        return id;
    }

    public Snapshot find(String id) {
        Path meta = dir.resolve(id + ".json");
        if (!id.matches("snap-\\d+-\\d+") || !Files.isRegularFile(meta)) {
            throw new AgentError("SNAPSHOT_NOT_FOUND", "No snapshot " + id,
                    "Use an id returned by world_edit (snapshot or a large edit); only the newest " + KEEP + " are kept.");
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(meta)).getAsJsonObject();
            return new Snapshot(id, o.get("world").getAsString(), Box.from(o));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Run on the thread that owns the box, with its chunks loaded. */
    public void restore(Snapshot snap, World world) throws IOException {
        Structure s = Bukkit.getStructureManager().loadStructure(dir.resolve(snap.id() + ".nbt").toFile());
        Box b = snap.box();
        s.place(new Location(world, b.minX(), b.minY(), b.minZ()), false, StructureRotation.NONE, Mirror.NONE, 0, 1f, new Random());
    }

    /** A structure of every block in the box (air included, so placing it back clears what was added). */
    public static Structure capture(World world, Box box, boolean entities) {
        Structure s = Bukkit.getStructureManager().createStructure();
        s.fill(new Location(world, box.minX(), box.minY(), box.minZ()), new BlockVector(box.sizeX(), box.sizeY(), box.sizeZ()), entities);
        return s;
    }

    private void prune() throws IOException {
        List<Path> metas;
        try (Stream<Path> s = Files.list(dir)) {
            metas = s.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparingLong(SnapshotStore::modified))
                    .toList();
        }
        for (int i = 0; i < metas.size() - KEEP; i++) {
            String base = metas.get(i).getFileName().toString().replace(".json", "");
            Files.deleteIfExists(metas.get(i));
            Files.deleteIfExists(dir.resolve(base + ".nbt"));
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
```

The spike showed that `Structure.fill(Location, Location, …)` treats the second corner as exclusive. This code therefore always uses the `fill(Location, BlockVector size, …)` overload.

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:test --console=plain`
Expected: PlacementTest (6) and all earlier unit tests pass.

- [ ] **Step 3: Write the failing ITs**

`agent-paper/src/integrationTest/java/com/uxplima/craftwire/paper/it/WorldEditIT.java`:

```java
package com.uxplima.craftwire.paper.it;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WorldEditIT {
    static ItHub hub;

    @BeforeAll
    static void connect() throws Exception {
        hub = ItEnv.get().hub;
    }

    static JsonObject edit(String paramsJson) throws Exception {
        return hub.result("world.edit", paramsJson).getAsJsonObject();
    }

    static String blockAt(int x, int y, int z) throws Exception {
        return hub.result("world.query", "{\"action\":\"block\",\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + "}")
                .getAsJsonObject().get("block").getAsString();
    }

    static String box(int x1, int y1, int z1, int x2, int y2, int z2) {
        return "\"min\":{\"x\":" + x1 + ",\"y\":" + y1 + ",\"z\":" + z1 + "},\"max\":{\"x\":" + x2 + ",\"y\":" + y2 + ",\"z\":" + z2 + "}";
    }

    @Test
    void setBlocksAcceptsStates() throws Exception {
        JsonObject r = edit("{\"action\":\"set_blocks\",\"blocks\":["
                + "{\"x\":3000,\"y\":-50,\"z\":3000,\"block\":\"minecraft:oak_stairs[facing=east]\"},"
                + "{\"x\":3001,\"y\":-50,\"z\":3000,\"block\":\"gold_block\"}]}");
        assertEquals(2, r.get("changed").getAsInt());
        assertTrue(blockAt(3000, -50, 3000).contains("facing=east"));
        assertEquals("minecraft:gold_block", blockAt(3001, -50, 3000));
    }

    @Test
    void fillWithReplaceOnlyChangesMatchingBlocks() throws Exception {
        edit("{\"action\":\"fill\"," + box(3010, -50, 3010, 3012, -50, 3012) + ",\"block\":\"stone\"}");
        edit("{\"action\":\"set_blocks\",\"blocks\":[{\"x\":3011,\"y\":-50,\"z\":3011,\"block\":\"gold_block\"}]}");
        JsonObject r = edit("{\"action\":\"fill\"," + box(3010, -50, 3010, 3012, -50, 3012) + ",\"block\":\"dirt\",\"replace\":\"stone\"}");
        assertEquals(8, r.get("changed").getAsInt());
        assertEquals("minecraft:gold_block", blockAt(3011, -50, 3011));
        assertEquals("minecraft:dirt", blockAt(3010, -50, 3010));
    }

    @Test
    void oversizedEditIsRejected() throws Exception {
        JsonObject e = hub.error("world.edit", "{\"action\":\"fill\"," + box(0, -60, 0, 1000, -50, 1000) + ",\"block\":\"stone\"}");
        assertEquals("EDIT_TOO_LARGE", e.get("code").getAsString());
    }

    @Test
    void largeFillTakesASnapshotThatRestores() throws Exception {
        JsonObject r = edit("{\"action\":\"fill\"," + box(3100, -40, 3100, 3139, -20, 3139) + ",\"block\":\"stone\"}");   // 33,600 blocks
        assertEquals(33_600, r.get("volume").getAsInt());
        String id = r.get("snapshotId").getAsString();
        assertEquals("minecraft:stone", blockAt(3120, -30, 3120));
        edit("{\"action\":\"restore\",\"id\":\"" + id + "\"}");
        assertEquals("minecraft:air", blockAt(3120, -30, 3120));
    }

    @Test
    void smallEditsTakeNoSnapshot() throws Exception {
        assertFalse(edit("{\"action\":\"fill\"," + box(3150, -50, 3150, 3151, -50, 3151) + ",\"block\":\"stone\"}").has("snapshotId"));
    }

    @Test
    void explicitSnapshotRoundTrip() throws Exception {
        String id = edit("{\"action\":\"snapshot\"," + box(3200, -50, 3200, 3202, -50, 3202) + "}").get("id").getAsString();
        edit("{\"action\":\"set_blocks\",\"blocks\":[{\"x\":3201,\"y\":-50,\"z\":3201,\"block\":\"gold_block\"}]}");
        edit("{\"action\":\"restore\",\"id\":\"" + id + "\"}");
        assertEquals("minecraft:air", blockAt(3201, -50, 3201));
    }

    @Test
    void unknownSnapshotIsNotFound() throws Exception {
        assertEquals("SNAPSHOT_NOT_FOUND", hub.error("world.edit", "{\"action\":\"restore\",\"id\":\"snap-1-1\"}").get("code").getAsString());
    }

    @Test
    void savedSchematicPastesWithRotation() throws Exception {
        edit("{\"action\":\"set_blocks\",\"blocks\":["
                + "{\"x\":3300,\"y\":-50,\"z\":3300,\"block\":\"gold_block\"},"
                + "{\"x\":3301,\"y\":-50,\"z\":3300,\"block\":\"iron_block\"}]}");
        edit("{\"action\":\"save_schematic\",\"name\":\"probe\"," + box(3300, -50, 3300, 3301, -50, 3300) + "}");
        JsonObject r = edit("{\"action\":\"paste_schematic\",\"name\":\"probe\",\"at\":{\"x\":3400,\"y\":-50,\"z\":3400},\"rotation\":\"clockwise_90\"}");
        assertEquals("{\"x\":3400,\"y\":-50,\"z\":3401}", r.get("max").toString());
        assertEquals("minecraft:gold_block", blockAt(3400, -50, 3400));
        assertEquals("minecraft:iron_block", blockAt(3400, -50, 3401));
    }

    @Test
    void unknownSchematicIsNotFound() throws Exception {
        assertEquals("SCHEMATIC_NOT_FOUND", hub.error("world.edit",
                "{\"action\":\"paste_schematic\",\"name\":\"never_saved\",\"at\":{\"x\":0,\"y\":0,\"z\":0}}").get("code").getAsString());
    }

    @Test
    void invalidBlockIsInvalidParams() throws Exception {
        assertEquals("INVALID_PARAMS", hub.error("world.edit",
                "{\"action\":\"fill\"," + box(0, 0, 0, 0, 0, 0) + ",\"block\":\"not_a_block\"}").get("code").getAsString());
    }
}
```

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:integrationTest --tests '*WorldEditIT' --console=plain`
Expected: all 10 FAIL with `UNKNOWN_METHOD`.

- [ ] **Step 4: Implement the handler and wire it**

`agent-paper/src/main/java/com/uxplima/craftwire/paper/handlers/WorldEditHandler.java`:

```java
package com.uxplima.craftwire.paper.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.paper.AgentConfig;
import com.uxplima.craftwire.paper.Args;
import com.uxplima.craftwire.paper.CraftwirePlugin;
import com.uxplima.craftwire.paper.Sync;
import com.uxplima.craftwire.paper.world.BlockMatcher;
import com.uxplima.craftwire.paper.world.Blocks;
import com.uxplima.craftwire.paper.world.Box;
import com.uxplima.craftwire.paper.world.ChunkWork;
import com.uxplima.craftwire.paper.world.Placement;
import com.uxplima.craftwire.paper.world.SnapshotStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;

final class WorldEditHandler {
    static final long AUTO_SNAPSHOT = 32_768;
    static final int MAX_SET_BLOCKS = 10_000;

    private record Placed(int x, int y, int z, BlockData data) {}

    private WorldEditHandler() {}

    static CompletableFuture<JsonElement> handle(JsonObject p, CraftwirePlugin plugin) {
        AgentConfig config = plugin.agentConfig();
        config.require(config.allowWorldEdit(), "allow-world-edit");
        Sync sync = plugin.sync();
        SnapshotStore snaps = plugin.snapshots();
        String action = Args.string(p, "action");
        return switch (action) {
            case "set_blocks" -> setBlocks(p, sync);
            case "fill" -> fill(p, sync, snaps, config);
            case "snapshot" -> snapshot(p, sync, snaps, config);
            case "restore" -> restore(p, sync, snaps);
            case "save_schematic" -> saveSchematic(p, sync, plugin.structuresDir(), config);
            case "paste_schematic" -> pasteSchematic(p, sync, snaps, plugin.structuresDir(), config);
            default -> throw Args.invalid("Unknown action: " + action);
        };
    }

    private static void checkVolume(Box box, AgentConfig config) {
        if (box.volume() > config.maxEditVolume()) {
            throw new AgentError("EDIT_TOO_LARGE", "The edit covers " + box.volume() + " blocks; max-edit-volume is " + config.maxEditVolume(),
                    "Split it into smaller boxes, or raise max-edit-volume in plugins/Craftwire/config.yml.");
        }
    }

    private static void checkHeight(World w, int y) {
        if (y < w.getMinHeight() || y >= w.getMaxHeight()) {
            throw Args.invalid("y=" + y + " is outside the build height " + w.getMinHeight() + ".." + (w.getMaxHeight() - 1));
        }
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    private static CompletableFuture<JsonElement> setBlocks(JsonObject p, Sync sync) {
        World w = Args.world(p);
        boolean physics = Args.bool(p, "physics", false);
        JsonArray list = Args.array(p, "blocks");
        if (list.size() > MAX_SET_BLOCKS) throw Args.invalid("At most " + MAX_SET_BLOCKS + " blocks per call");
        Map<String, BlockData> parsed = new HashMap<>();
        Map<Long, List<Placed>> byChunk = new LinkedHashMap<>();
        for (JsonElement e : list) {
            JsonObject b = e.getAsJsonObject();
            int x = Args.integer(b, "x"), y = Args.integer(b, "y"), z = Args.integer(b, "z");
            checkHeight(w, y);
            BlockData data = parsed.computeIfAbsent(Args.string(b, "block"), Blocks::parse);
            byChunk.computeIfAbsent(chunkKey(x >> 4, z >> 4), k -> new ArrayList<>()).add(new Placed(x, y, z, data));
        }
        List<int[]> chunks = byChunk.keySet().stream().map(k -> new int[] {(int) (k >> 32), (int) k.longValue()}).toList();
        return ChunkWork.forChunks(sync, w, chunks, (cx, cz) -> {
            List<Placed> here = byChunk.get(chunkKey(cx, cz));
            for (Placed b : here) w.getBlockAt(b.x(), b.y(), b.z()).setBlockData(b.data(), physics);
            return here.size();
        }).thenApply(counts -> {
            JsonObject r = new JsonObject();
            r.addProperty("changed", counts.stream().mapToInt(Integer::intValue).sum());
            return (JsonElement) r;
        });
    }

    private static CompletableFuture<JsonElement> fill(JsonObject p, Sync sync, SnapshotStore snaps, AgentConfig config) {
        World w = Args.world(p);
        Box requested = Box.from(p);
        checkVolume(requested, config);
        Box box = requested.clampY(w.getMinHeight(), w.getMaxHeight() - 1);
        if (box == null) throw Args.invalid("The box is outside the build height " + w.getMinHeight() + ".." + (w.getMaxHeight() - 1));
        BlockData data = Blocks.parse(Args.string(p, "block"));
        Optional<BlockMatcher> only = Args.optString(p, "replace").map(BlockMatcher::parse);
        boolean physics = Args.bool(p, "physics", false);
        CompletableFuture<String> snapshot = box.volume() > AUTO_SNAPSHOT ? takeSnapshot(sync, snaps, w, box) : CompletableFuture.completedFuture(null);
        return snapshot.thenCompose(id -> ChunkWork.forEachChunk(sync, w, box, part -> {
            int n = 0;
            for (int y = part.minY(); y <= part.maxY(); y++) {
                for (int z = part.minZ(); z <= part.maxZ(); z++) {
                    for (int x = part.minX(); x <= part.maxX(); x++) {
                        Block b = w.getBlockAt(x, y, z);
                        if (only.isPresent() && !only.get().test(b.getBlockData())) continue;
                        b.setBlockData(data, physics);
                        n++;
                    }
                }
            }
            return n;
        }).thenApply(counts -> {
            JsonObject r = box.toJson();
            r.addProperty("changed", counts.stream().mapToInt(Integer::intValue).sum());
            r.addProperty("volume", box.volume());
            if (id != null) r.addProperty("snapshotId", id);
            return (JsonElement) r;
        }));
    }

    /** Loads the box's chunks, then copies it on the region of its minimum corner. */
    private static CompletableFuture<String> takeSnapshot(Sync sync, SnapshotStore snaps, World w, Box box) {
        return ChunkWork.forEachChunk(sync, w, box, part -> 0)
                .thenCompose(v -> sync.region(w, box.minX() >> 4, box.minZ() >> 4, () -> snaps.save(w, box)));
    }

    private static CompletableFuture<JsonElement> snapshot(JsonObject p, Sync sync, SnapshotStore snaps, AgentConfig config) {
        World w = Args.world(p);
        Box box = Box.from(p);
        checkVolume(box, config);
        return takeSnapshot(sync, snaps, w, box).thenApply(id -> {
            JsonObject r = box.toJson();
            r.addProperty("id", id);
            r.addProperty("volume", box.volume());
            return (JsonElement) r;
        });
    }

    private static CompletableFuture<JsonElement> restore(JsonObject p, Sync sync, SnapshotStore snaps) {
        SnapshotStore.Snapshot snap = snaps.find(Args.string(p, "id"));
        World w = Bukkit.getWorld(snap.world());
        if (w == null) throw new AgentError("WORLD_NOT_FOUND", "World " + snap.world() + " is not loaded", "Load that world, then retry.");
        Box box = snap.box();
        return ChunkWork.forEachChunk(sync, w, box, part -> 0)
                .thenCompose(v -> sync.region(w, box.minX() >> 4, box.minZ() >> 4, () -> {
                    snaps.restore(snap, w);
                    JsonObject r = box.toJson();
                    r.addProperty("restored", snap.id());
                    return (JsonElement) r;
                }));
    }

    private static CompletableFuture<JsonElement> saveSchematic(JsonObject p, Sync sync, Path dir, AgentConfig config) {
        String name = Placement.checkName(Args.string(p, "name"));
        World w = Args.world(p);
        Box box = Box.from(p);
        checkVolume(box, config);
        boolean entities = Args.bool(p, "includeEntities", false);
        Path file = dir.resolve(name + ".nbt");
        return ChunkWork.forEachChunk(sync, w, box, part -> 0)
                .thenCompose(v -> sync.region(w, box.minX() >> 4, box.minZ() >> 4, () -> {
                    Files.createDirectories(dir);
                    Bukkit.getStructureManager().saveStructure(file.toFile(), SnapshotStore.capture(w, box, entities));
                    JsonObject r = box.toJson();
                    r.addProperty("name", name);
                    r.addProperty("path", file.toAbsolutePath().toString());
                    return (JsonElement) r;
                }));
    }

    private static CompletableFuture<JsonElement> pasteSchematic(JsonObject p, Sync sync, SnapshotStore snaps, Path dir, AgentConfig config) {
        String name = Placement.checkName(Args.string(p, "name"));
        Path file = dir.resolve(name + ".nbt");
        if (!Files.isRegularFile(file)) {
            throw new AgentError("SCHEMATIC_NOT_FOUND", "No saved schematic named " + name,
                    "Save one first with world_edit {action:'save_schematic'}; files live in plugins/Craftwire/structures/.");
        }
        World w = Args.world(p);
        JsonObject at = Args.object(p, "at");
        int ax = Args.integer(at, "x"), ay = Args.integer(at, "y"), az = Args.integer(at, "z");
        StructureRotation rotation = Placement.rotation(Args.optString(p, "rotation").orElse("none"));
        Mirror mirror = Placement.mirror(Args.optString(p, "mirror").orElse("none"));
        boolean entities = Args.bool(p, "includeEntities", false);
        Structure structure;
        try {
            structure = Bukkit.getStructureManager().loadStructure(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        BlockVector size = structure.getSize();
        Box box = Placement.footprint(ax, ay, az, size.getBlockX(), size.getBlockY(), size.getBlockZ(), rotation, mirror);
        checkVolume(box, config);
        CompletableFuture<String> snapshot = box.volume() > AUTO_SNAPSHOT ? takeSnapshot(sync, snaps, w, box) : CompletableFuture.completedFuture(null);
        return snapshot.thenCompose(id -> ChunkWork.forEachChunk(sync, w, box, part -> 0)
                .thenCompose(v -> sync.region(w, ax >> 4, az >> 4, () -> {
                    structure.place(new Location(w, ax, ay, az), entities, rotation, mirror, 0, 1f, new Random());
                    JsonObject r = box.toJson();
                    r.addProperty("name", name);
                    if (id != null) r.addProperty("snapshotId", id);
                    return (JsonElement) r;
                })));
    }
}
```

`CraftwirePlugin.java`:
- Add `import com.uxplima.craftwire.paper.world.SnapshotStore;`.
- Add the field `private SnapshotStore snapshots;`.
- In `onEnable`, before `Handlers.registerAll(this);`:

```java
        snapshots = new SnapshotStore(getDataFolder().toPath().resolve("snapshots"));
```

and the accessors:

```java
    public SnapshotStore snapshots() {
        return snapshots;
    }

    public Path structuresDir() {
        return getDataFolder().toPath().resolve("structures");
    }
```

`Handlers.registerAll`, add:

```java
        d.register("world.edit", p -> WorldEditHandler.handle(p, plugin));
```

- [ ] **Step 5: Run unit and integration suites**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:test :agent-paper:integrationTest --console=plain`
Expected: BUILD SUCCESSFUL. Every IT class passes, including WorldEditIT (10).

- [ ] **Step 6: Commit**

```bash
git add agent-paper
git commit -m "feat(paper): world.edit with auto-snapshots, restore and structure schematics"
```

---

### Task 8: plugin_manage

**Files:**
- Create: `agent-paper/src/main/java/com/uxplima/craftwire/paper/handlers/PluginManageHandler.java`
- Modify: `agent-paper/src/main/java/com/uxplima/craftwire/paper/handlers/PluginJson.java` (add `details`), `Handlers.java`
- Test: `agent-paper/src/integrationTest/java/com/uxplima/craftwire/paper/it/PluginManageIT.java`

**Interfaces:**
- Consumes: `PluginJson.summary` (Task 3) and the `CraftwireFixture` plugin (Task 4).
- Produces: agent method `plugin.manage {action: list|info|enable|disable, name?}`.
  - `list` returns `{plugins: [summary + description, authors]}`.
  - `info` returns details: summary plus description, authors, website, main, depend, softDepend and commands.
  - `enable`/`disable` return the summary plus `changed`.
  - Errors: `PLUGIN_NOT_FOUND` and `CANNOT_DISABLE_SELF`.

- [ ] **Step 1: Write the failing ITs**

`agent-paper/src/integrationTest/java/com/uxplima/craftwire/paper/it/PluginManageIT.java`:

```java
package com.uxplima.craftwire.paper.it;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class PluginManageIT {
    static JsonObject manage(String paramsJson) throws Exception {
        return ItEnv.get().hub.result("plugin.manage", paramsJson).getAsJsonObject();
    }

    @Test
    void listIncludesEveryPlugin() throws Exception {
        String list = manage("{\"action\":\"list\"}").getAsJsonArray("plugins").toString();
        assertTrue(list.contains("\"name\":\"Craftwire\""), list);
        assertTrue(list.contains("\"name\":\"CraftwireFixture\""), list);
    }

    @Test
    void infoShowsVersionAndCommands() throws Exception {
        JsonObject info = manage("{\"action\":\"info\",\"name\":\"craftwirefixture\"}");
        assertEquals("CraftwireFixture", info.get("name").getAsString());
        assertEquals(System.getProperty("craftwire.version"), info.get("version").getAsString());
        assertEquals("[\"cwfixture\"]", info.getAsJsonArray("commands").toString());
    }

    @Test
    void disableThenEnableRoundTrip() throws Exception {
        ItHub hub = ItEnv.get().hub;
        JsonObject off = manage("{\"action\":\"disable\",\"name\":\"CraftwireFixture\"}");
        assertFalse(off.get("enabled").getAsBoolean());
        assertTrue(off.get("changed").getAsBoolean());
        assertEquals("COMMAND_FAILED", hub.error("server.command", "{\"command\":\"cwfixture\"}").get("code").getAsString());
        JsonObject on = manage("{\"action\":\"enable\",\"name\":\"CraftwireFixture\"}");
        assertTrue(on.get("enabled").getAsBoolean());
        assertTrue(hub.result("server.command", "{\"command\":\"cwfixture\",\"collectMs\":0}").toString().contains("fixture: now"));
        assertFalse(manage("{\"action\":\"enable\",\"name\":\"CraftwireFixture\"}").get("changed").getAsBoolean());
    }

    @Test
    void craftwireCannotDisableItself() throws Exception {
        assertEquals("CANNOT_DISABLE_SELF", ItEnv.get().hub.error("plugin.manage", "{\"action\":\"disable\",\"name\":\"Craftwire\"}").get("code").getAsString());
    }

    @Test
    void unknownPluginIsNotFound() throws Exception {
        assertEquals("PLUGIN_NOT_FOUND", ItEnv.get().hub.error("plugin.manage", "{\"action\":\"info\",\"name\":\"Nope\"}").get("code").getAsString());
    }
}
```

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:integrationTest --tests '*PluginManageIT' --console=plain`
Expected: all 5 FAIL with `UNKNOWN_METHOD`.

- [ ] **Step 2: Implement**

Add to `PluginJson.java`. The new imports are `com.google.gson.JsonArray` and `java.util.List`:

```java
    static JsonObject details(Plugin p) {
        var meta = p.getPluginMeta();
        JsonObject o = summary(p);
        o.addProperty("description", meta.getDescription());
        o.add("authors", strings(meta.getAuthors()));
        o.addProperty("website", meta.getWebsite());
        o.addProperty("main", meta.getMainClass());
        o.add("depend", strings(meta.getPluginDependencies()));
        o.add("softDepend", strings(meta.getPluginSoftDependencies()));
        o.add("commands", strings(commands(p)));
        return o;
    }

    @SuppressWarnings("deprecation")   // plugin.yml commands are only exposed through the legacy description
    private static List<String> commands(Plugin p) {
        try {
            return p.getDescription().getCommands().keySet().stream().sorted().toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static JsonArray strings(List<String> values) {
        JsonArray a = new JsonArray();
        values.forEach(a::add);
        return a;
    }
```

`agent-paper/src/main/java/com/uxplima/craftwire/paper/handlers/PluginManageHandler.java`:

```java
package com.uxplima.craftwire.paper.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.paper.Args;
import com.uxplima.craftwire.paper.CraftwirePlugin;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

final class PluginManageHandler {
    private PluginManageHandler() {}

    static CompletableFuture<JsonElement> handle(JsonObject p, CraftwirePlugin self) {
        String action = Args.string(p, "action");
        return self.sync().global(() -> switch (action) {
            case "list" -> list();
            case "info" -> PluginJson.details(find(Args.string(p, "name")));
            case "enable" -> setEnabled(find(Args.string(p, "name")), true, self);
            case "disable" -> setEnabled(find(Args.string(p, "name")), false, self);
            default -> throw Args.invalid("Unknown action: " + action);
        });
    }

    private static JsonElement list() {
        JsonArray plugins = new JsonArray();
        Arrays.stream(Bukkit.getPluginManager().getPlugins())
                .sorted(Comparator.comparing(Plugin::getName, String.CASE_INSENSITIVE_ORDER))
                .forEach(pl -> {
                    JsonObject o = PluginJson.summary(pl);
                    o.addProperty("description", pl.getPluginMeta().getDescription());
                    JsonArray authors = new JsonArray();
                    pl.getPluginMeta().getAuthors().forEach(authors::add);
                    o.add("authors", authors);
                    plugins.add(o);
                });
        JsonObject r = new JsonObject();
        r.add("plugins", plugins);
        return r;
    }

    private static Plugin find(String name) {
        return Arrays.stream(Bukkit.getPluginManager().getPlugins())
                .filter(pl -> pl.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new AgentError("PLUGIN_NOT_FOUND", "No plugin named " + name,
                        "Use plugin_manage {action:'list'} for the installed plugins."));
    }

    private static JsonElement setEnabled(Plugin target, boolean enable, Plugin self) {
        if (target == self) {
            throw new AgentError("CANNOT_DISABLE_SELF", "Craftwire cannot change its own state",
                    "To stop Craftwire, remove it from plugins/ and restart the server.");
        }
        boolean before = target.isEnabled();
        if (enable && !before) Bukkit.getPluginManager().enablePlugin(target);
        if (!enable && before) Bukkit.getPluginManager().disablePlugin(target);
        JsonObject r = PluginJson.summary(target);
        r.addProperty("changed", before != target.isEnabled());
        return r;
    }
}
```

`Handlers.registerAll`, add:

```java
        d.register("plugin.manage", p -> PluginManageHandler.handle(p, plugin));
```

- [ ] **Step 3: Run unit and integration suites**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:test :agent-paper:integrationTest --console=plain`
Expected: BUILD SUCCESSFUL. All IT classes pass, including PluginManageIT (5).

- [ ] **Step 4: Commit**

```bash
git add agent-paper
git commit -m "feat(paper): plugin.manage list/info/enable/disable"
```

---

### Task 9: Client logs from the Fabric agent

**Files:**
- Modify: `agent-fabric/src/main/java/com/uxplima/craftwire/fabric/CraftwireAgent.java`
- Modify: `agent-fabric/src/gametest/java/com/uxplima/craftwire/fabric/gametest/LifecycleChecks.java`

**Interfaces:**
- Consumes: `LogCapture` (Task 1).
- Produces: `CraftwireAgent.logs() → LogCapture`. While connected, the client agent emits `log` events, which the hub `logs` tool can read with `instance: <client id>`.

- [ ] **Step 1: Write the failing gametest check**

In `LifecycleChecks.run`, after the `pauseOnLostFocus` block (before the F8 section), add:

```java
        // Client log lines are captured from mod start, so the hub's `logs` tool can read them after a connect.
        CraftwireAgent.LOGGER.info("craftwire gametest marker");
        check(agent.logs() != null && agent.logs().backlog().stream()
                        .anyMatch(e -> e.data().get("message").getAsString().equals("craftwire gametest marker")),
                "client log lines should be captured");
```

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-fabric:runClientGameTest --console=plain`
Expected: compilation FAILS with `cannot find symbol: method logs()`.

- [ ] **Step 2: Implement**

`CraftwireAgent.java`:
- Add `import com.uxplima.craftwire.core.LogCapture;` and the field `private LogCapture logs;`.
- First line of `start()`:

```java
        logs = LogCapture.install(1000);
```

In `onHubConnected`, after the `LOGGER.info(...)` line:

```java
        if (hub != null) logs.attach((data, time) -> hub.notifyEvent("log", data, time));
```

In `onHubDisconnected`, first line:

```java
        if (logs != null) logs.detach();
```

and the accessor:

```java
    public LogCapture logs() {
        return logs;
    }
```

If `:agent-fabric:compileJava` reports `class file for org.apache.logging.log4j.core.appender.AbstractAppender not found`, add `compileOnly "org.apache.logging.log4j:log4j-core:${log4j_version}"` to `agent-fabric/build.gradle` and ledger a ruling.

- [ ] **Step 3: Run the Fabric suites**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-core:test :agent-fabric:test :agent-fabric:runClientGameTest --console=plain`
Expected: BUILD SUCCESSFUL. All gametest groups pass, including the new log check.

- [ ] **Step 4: Commit**

```bash
git add agent-fabric
git commit -m "feat(fabric): stream client log lines to the hub"
```

---

### Task 10: Docs, skills, CI and version 0.2.0

**Files:**
- Modify: `protocol/PROTOCOL.md`, `README.md`, `claude-plugin/skills/craftwire/SKILL.md`
- Modify: `.github/workflows/ci.yml`
- Modify: `gradle.properties`, `hub/package.json`, `hub/package-lock.json`, `hub/src/version.ts`, `claude-plugin/.claude-plugin/plugin.json`, `claude-plugin/.mcp.json`
- Test: `hub/test/plugin-manifest.test.ts` (existing lockstep checks)

**Interfaces:**
- Produces: version `0.2.0` everywhere, CI jobs `java` (now including agent-paper and test-fixtures) and `paper-it`.

- [ ] **Step 1: Bump the version and watch the lockstep test catch a miss**

Change `hub/package.json` `"version"` to `"0.2.0"` first, then run `cd hub && npx vitest run test/plugin-manifest.test.ts`.
Expected: FAIL with `plugin version is lockstep with the hub` and `.mcp.json starts the pinned hub via npx`.

Now set `0.2.0` in:
- `gradle.properties` (`craftwire_version=0.2.0`)
- `hub/src/version.ts` (`HUB_VERSION = "0.2.0"`)
- the two top-level `"version"` fields of `hub/package-lock.json`
- `claude-plugin/.claude-plugin/plugin.json`
- `claude-plugin/.mcp.json` (`craftwire@0.2.0`)

Also update `plugin.json` `description` to: `"Craftwire by UXPLIMA: let Claude see and drive Minecraft (screenshots, camera, GUIs, chat, input) and Paper servers (commands, scripts, world edits, logs)."`

Run: `cd hub && npx vitest run && npm run typecheck`
Expected: PASS.

- [ ] **Step 2: Protocol and README**

Append to `protocol/PROTOCOL.md`:

```markdown
Event types (M2): `log` `{level, logger, thread, message, thrown?}` (both agents; replayed from a 1000-line backlog on every connect), `player` `{action: "join"|"quit", name, uuid}` and `chat` `{text, kind: "chat", sender}` from the server agent.

Server methods (M2, `agentKind: "server"`):
- `server.command` `{command, asPlayer?, collectMs?}` → `{command, success, output[], sender?, note?}`
- `server.eval` `{code, timeoutMs?, reset?, at?: {world?, x, z}}` → `{result, output}`
- `world.query` `{action: block|region|entities|players|find_block, world?, …}`
- `world.edit` `{action: set_blocks|fill|snapshot|restore|save_schematic|paste_schematic, world?, …}` → results carry `snapshotId` when a snapshot was taken
- `server.info` `{}` → `{name, version, minecraftVersion, tps[3], mspt, memory, players, worlds[], plugins[]}`
- `plugin.manage` `{action: list|info|enable|disable, name?}`
```

In `README.md`:
- Add a "Paper server" section after the client mod section:
  - Install: drop `craftwire-paper-<version>.jar` into `plugins/`. The first start downloads GraalJS through Paper's library loader and needs network access.
  - Configure: `plugins/Craftwire/config.yml` holds the four switches.
  - Warning: development servers only.
- Add the M2 tools to the tool list: `server_command`, `server_eval`, `world_query`, `world_edit`, `server_info`, `plugin_manage` and `logs`.

- [ ] **Step 3: Skill**

`claude-plugin/skills/craftwire/SKILL.md`:
- Change the `description:` line to:

```
description: Use when driving Minecraft or a Paper server through the craftwire MCP tools (screenshot, camera, gui_*, input, chat, server_command, server_eval, world_query, world_edit, logs, wait_for) — covers the reliable order of calls, menus, server scripting, safe world edits and recovering from errors.
```

Then add before `## Errors`:

```markdown
## Server (Paper + Craftwire plugin)
- `server_info` first: TPS, plugins, worlds.
- `server_command` returns the console feedback. Raise `collectMs` (max 5000) for plugins that answer late. Use the `minecraft:` prefix when a plugin overrides a vanilla command (e.g. `minecraft:tp`); gamerules are snake_case in 26.x (`advance_time`).
- `server_eval` for anything without a command: `server`, `player(name)`, `plugin(name)`, `loc(x,y,z)`, `Java.type(...)`, `print(...)`. Scripts run on the server thread with a 5 s default timeout, so keep loops small. Keep values on `globalThis`; top-level `let/const` cannot be re-declared on the next run.
- `world_query` before editing. `world_edit` edits over 32768 blocks return a `snapshotId`; `world_edit {action:"restore", id}` undoes them. Take an explicit `snapshot` before any risky change.
- `logs {level:"WARN"}` after (re)enabling a plugin; stack traces arrive folded into one entry.

## Player commands through `chat`
- `chat {action:"command"}` strips one leading `/`, so WorldEdit commands keep their double slash: send `//pos1`.
```

- [ ] **Step 4: CI**

`.github/workflows/ci.yml`. In the `java` job, change the run line to:

```yaml
      - run: ./gradlew :agent-core:test :agent-fabric:test :agent-fabric:build :agent-paper:test :agent-paper:build :test-fixtures:build
```

and add the job:

```yaml
  paper-it:
    runs-on: ubuntu-latest
    needs: java
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 25
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew :agent-paper:integrationTest
      - if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: paper-it-logs
          path: agent-paper/build/it/server/logs/
```

- [ ] **Step 5: Full local verification**

Run: `cd hub && npx vitest run && npm run typecheck && npm run build`
Then: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-core:test :agent-fabric:test :agent-fabric:build :agent-paper:test :agent-paper:build :test-fixtures:build :agent-paper:integrationTest --console=plain`
Then: `claude plugin validate .` and `claude plugin validate claude-plugin`
Expected: all green. `agent-paper/build/libs/craftwire-paper-0.2.0.jar` exists.

- [ ] **Step 6: Commit and push**

```bash
git add -A
git commit -m "docs, ci: M2 server tools, paper integration job, version 0.2.0"
git push -u origin feat/m2-server-agent
```

Expected: the GitHub Actions jobs `hub`, `java`, `client-gametest` and `paper-it` are green (`gh run watch`).

---

### Task 11: Manual end-to-end check on the user's test server

Not automatable in M2 (see Deviation 4). The real hub and the real plugin run on the user's server at `C:/Users/pc/Desktop/server`. **Ask the user before copying the jar into their server or restarting it.**

**Files:**
- Create: `docs/acceptance/m2-server-tools.md`

- [ ] **Step 1: Prepare.** Build the hub (`cd hub && npm run build`) and the plugin (Task 10 jar). Ask the user to stop their server. Copy `craftwire-paper-0.2.0.jar` into `C:/Users/pc/Desktop/server/plugins/` and have the user start the server.
- [ ] **Step 2: Drive it through the real hub.** Use the scratch MCP driver (`e2e/driver.mjs` in the scratchpad: the real `hub/dist/cli.js` over stdio, HTTP `127.0.0.1:47900`) and record each call and its outcome:
  1. `list_instances` shows `server-1` (name `server`, Minecraft 26.2).
  2. `server_info` lists uxmBuilders, uxmEssentials, WorldEdit and Craftwire.
  3. `server_command {command:"time query gametime"}` returns its output line.
  4. `server_eval {code:"plugin('uxmBuilders').getPluginMeta().getVersion()"}` returns `1.0.0`.
  5. `logs {level:"WARN"}` returns the startup warnings, including the Craftwire production warning.
  6. In an empty area far from builds, run `world_edit snapshot`, then `fill` stone, then `world_query block` (stone), then `restore`, then `world_query block` (air).
  7. With the user's Minecraft client joined: `wait_for {condition:"player_join"}` fires, `logs` with the client instance id returns client lines, and `world_query {action:"players"}` lists the player.
- [ ] **Step 3: Write the record.** Write `docs/acceptance/m2-server-tools.md` (date, versions, the table of calls and outcomes, any defects), then commit and push.

```bash
git add docs/acceptance/m2-server-tools.md
git commit -m "docs: M2 manual end-to-end run on a real Paper server"
git push
```
