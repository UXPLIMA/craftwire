# Craftwire M3 — Dev Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **This project runs it inline (superpowers:executing-plans): the user does not want subagents.**

**Goal:** Let Claude run a local Paper server from the hub (`server_process`), build and redeploy a plugin in one call (`plugin_deploy`, from a project folder or a ready jar), and diagnose a broken setup with `craftwire doctor`.

**Architecture:** Everything new lives in the hub (`hub/src/dev/`). A `ServerManager` spawns Java directly, watches the console for `Done (`, and waits for the Craftwire plugin to connect. It recognises its agent by the `serverDir` and `pid` that the Paper agent now sends in `hello`. Servers started elsewhere are "external": they are only stopped with `takeOver:true`, through the agent's `server.command stop`. `plugin_deploy` runs the build in a shell, parses compiler errors, picks the plugin jar by reading `plugin.yml` from the zip, swaps it into `plugins/` (backup kept) while the server is stopped, restarts, then asks the agent whether the plugin enabled. `craftwire doctor` talks to the running hub through a new token-checked `status` request on the same WebSocket.

**Tech Stack:**
- Hub: Node ≥ 20, TypeScript, `@modelcontextprotocol/sdk`, `ws`, `zod`, Vitest. No new npm dependencies, so the zip reader and the glob matcher are hand-written.
- Agents: Java 25, Paper API 26.2, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-10-05-craftwire-design.md` (§4 "Dev loop (hub)", §6 errors, §8 `craftwire doctor` and skills, §9 M3).

## Global Constraints

- Hub dependencies stay `@modelcontextprotocol/sdk`, `ws`, `zod`. Node ≥ 20 at runtime. Test-only code may use Node 22 APIs (`zlib.crc32`).
- Every listener stays on `127.0.0.1`. Agents open no ports.
- **Never accept the Minecraft EULA.** Never write `eula.txt` in a user's server. Test suites may write it only in their own throwaway server folders, as the M2 integration tests already do.
- `protocolVersion` stays `1`: the `serverDir`, `pid` and `status` additions are optional and additive.
- Errors keep the shape `{code, message, hint}`, plus an optional `details` object for structured data (build errors, console tail).
- Lockstep version `0.3.0` everywhere: gradle.properties, hub package.json and lock, version.ts, plugin.json, .mcp.json, marketplace.json, README.
- Windows and Linux both work:
  - spawn Java directly (no shell) for servers;
  - builds run in a shell;
  - paths are compared case-insensitively on Windows.
- Local Gradle needs `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1"` (the system JAVA_HOME is JDK 21).
- Paper 26.x needs Java 25+ (`MIN_JAVA = 25`).

## Review Focus

1. **Path spelling differs between the agent and the caller.** On Windows Paper reports `C:\Users\pc\Desktop\server`, while Claude may pass `c:/users/pc/desktop/server`. These must match (`samePath`; test in Task 3).
2. **Jars are locked on Windows.** A running server's plugin jars cannot be deleted or replaced. The deploy must stop the server before swapping, or stage the jar in `plugins/update/` (test in Task 5, `running:true`).
3. **A server the hub did not start.** It must never be stopped without `takeOver:true`, and `plugin_deploy` must refuse before it runs the build (tests in Tasks 3 and 5).
4. **The hub exits while it manages a server.** The server gets a graceful `stop`, not an orphaned headless JVM (test `shutdown` in Task 3, wired in the CLI in Task 4).
5. **CRLF build output from Windows Gradle.** It must still parse into `file:line` errors (test in Task 2).

---

## File Structure

| File | Responsibility |
|---|---|
| `agent-core/.../Hello.java` | Hello record gains `serverDir`, `pid` (4-arg constructor kept) |
| `agent-core/.../RpcCodec.java` | Writes `serverDir`/`pid` only when set |
| `agent-paper/.../CraftwirePlugin.java` | Sends working dir and pid |
| `protocol/fixtures/valid-hello-server.json`, `valid-status-request.json` | New fixtures |
| `hub/src/protocol.ts` | `HelloParams` optional fields, `StatusRequest` |
| `hub/src/agents.ts` | `InstanceInfo.serverDir/pid`, status request, refused-agent memory |
| `hub/src/errors.ts` | `details` on `CraftwireError` |
| `hub/src/dev/paths.ts` | `pathKey`, `samePath` |
| `hub/src/dev/build-errors.ts` | `parseBuildErrors` (javac, kotlinc, Maven) |
| `hub/src/dev/jar.ts` | `readZipEntry`, `pluginInfoOfJar`, `pluginJarsNamed` |
| `hub/src/dev/launch.ts` | start-script parsing, `resolveLaunch`, Java probe, EULA check |
| `hub/src/dev/server-manager.ts` | `ServerManager` (start/stop/restart/status/shutdown, external servers) |
| `hub/src/dev/deploy.ts` | build detection/run, jar discovery, install, `deploy` orchestration |
| `hub/src/tools/dev-tools.ts` | `server_process`, `plugin_deploy` MCP tools |
| `hub/src/doctor.ts` | `runDoctor`, `hubStatus`, `formatChecks` |
| `hub/src/cli.ts` | `doctor` subcommand, ServerManager wiring and shutdown |
| `hub/test/helpers/zip.ts`, `servers.ts`, `fake-paper.mjs` | Test helpers |
| `hub/test-e2e/*`, `hub/vitest.e2e.config.ts` | Real-Paper dev-loop E2E |
| `claude-plugin/skills/paper-plugin-dev/SKILL.md` | New skill |

---

### Task 1: Agents report `serverDir` and `pid`; hub answers `status`

**Files:**
- Modify:
  - `agent-core/src/main/java/com/uxplima/craftwire/core/Hello.java`
  - `agent-core/src/main/java/com/uxplima/craftwire/core/RpcCodec.java:13-26`
  - `agent-paper/src/main/java/com/uxplima/craftwire/paper/CraftwirePlugin.java:59`
  - `hub/src/protocol.ts`
  - `hub/src/agents.ts`
  - `hub/test/helpers/fakeAgent.ts`
  - `agent-paper/src/integrationTest/java/com/uxplima/craftwire/paper/it/ConnectionIT.java`
- Create:
  - `protocol/fixtures/valid-hello-server.json`
  - `protocol/fixtures/valid-status-request.json`
  - `hub/test/agents-status.test.ts`
- Test:
  - `agent-core/src/test/java/com/uxplima/craftwire/core/ProtocolFixturesTest.java`
  - `hub/test/agents-status.test.ts`
  - `hub/test/protocol.test.ts` (picks up the fixtures by itself)

**Interfaces:**
- Produces (Java): `record Hello(String agentKind, String agentVersion, String mcVersion, String instanceName, String serverDir, Long pid)` plus `Hello(String, String, String, String)`.
- Produces (TS):
  - `InstanceInfo.serverDir?: string`, `InstanceInfo.pid?: number`.
  - `interface RejectedAgent { time: number; code: string; agentKind: string; agentVersion: string; instanceName: string; protocolVersion: number }`.
  - `interface HubStatus { hubVersion: string; protocolVersion: number; instances: InstanceInfo[]; rejected: RejectedAgent[] }`.
  - `AgentServer.rejectedAgents(): RejectedAgent[]`.
  - Wire: `{"jsonrpc":"2.0","id":N,"method":"status","params":{"token":T}}` → `{id:N, result: HubStatus}` then close 1000; a wrong token gets `error.data.code UNAUTHORIZED` and close 4001.
  - `connectFakeAgent` opts gain `agentVersion?`, `serverDir?`, `pid?`.

- [ ] **Step 1: Write the failing Java fixture test**

Create `protocol/fixtures/valid-hello-server.json`:

```json
{"jsonrpc":"2.0","id":0,"method":"hello","params":{"token":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","agentKind":"server","agentVersion":"0.3.0","protocolVersion":1,"mcVersion":"26.2","instanceName":"server","serverDir":"/srv/paper","pid":4242}}
```

Create `protocol/fixtures/valid-status-request.json`:

```json
{"jsonrpc":"2.0","id":0,"method":"status","params":{"token":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}
```

Add to `ProtocolFixturesTest` (after `helloMatchesFixtureExactly`):

```java
    @Test
    void serverHelloCarriesServerDirAndPid() throws Exception {
        JsonObject fixture = JsonParser.parseString(Files.readString(FIXTURES.resolve("valid-hello-server.json"))).getAsJsonObject();
        String encoded = RpcCodec.hello("a".repeat(64), new Hello("server", "0.3.0", "26.2", "server", "/srv/paper", 4242L));
        assertEquals(fixture, JsonParser.parseString(encoded));
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-core:test --tests '*ProtocolFixturesTest*'`
Expected: compilation FAIL, "constructor Hello in record Hello cannot be applied to given types".

- [ ] **Step 3: Implement the Java side**

`Hello.java`:

```java
package com.uxplima.craftwire.core;

/** First message an agent sends. {@code serverDir} and {@code pid} are set by server agents only. */
public record Hello(String agentKind, String agentVersion, String mcVersion, String instanceName, String serverDir, Long pid) {
    public Hello(String agentKind, String agentVersion, String mcVersion, String instanceName) {
        this(agentKind, agentVersion, mcVersion, instanceName, null, null);
    }
}
```

In `RpcCodec.hello`, after `params.addProperty("instanceName", h.instanceName());`:

```java
        if (h.serverDir() != null) params.addProperty("serverDir", h.serverDir());
        if (h.pid() != null) params.addProperty("pid", h.pid());
```

In `CraftwirePlugin.hello()` (add `import java.nio.file.Path;`):

```java
        return new Hello("server", getPluginMeta().getVersion(), Bukkit.getMinecraftVersion(), config.instanceName(),
                Path.of("").toAbsolutePath().toString(), ProcessHandle.current().pid());
```

- [ ] **Step 4: Run the Java tests**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-core:test :agent-paper:test :agent-fabric:compileJava`
Expected: BUILD SUCCESSFUL. The 4-arg client hello still matches `valid-hello.json`.

- [ ] **Step 5: Write the failing hub tests**

`hub/test/agents-status.test.ts`:

```ts
import WebSocket from "ws";
import { afterEach, describe, expect, it } from "vitest";
import { AgentServer } from "../src/agents.js";
import { HUB_VERSION, PROTOCOL_VERSION } from "../src/version.js";
import { connectFakeAgent } from "./helpers/fakeAgent.js";

const TOKEN = "b".repeat(64);
let agents: AgentServer | undefined;

afterEach(async () => {
  await agents?.close();
  agents = undefined;
});

async function start(): Promise<number> {
  agents = new AgentServer({ token: TOKEN, port: 0 });
  return agents.listen();
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function ask(port: number, token: string): Promise<{ reply: any; code: number }> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/`);
    let reply: unknown;
    ws.once("open", () => ws.send(JSON.stringify({ jsonrpc: "2.0", id: 7, method: "status", params: { token } })));
    ws.on("message", (raw) => { reply = JSON.parse(String(raw)); });
    ws.once("close", (code) => resolve({ reply, code }));
    ws.once("error", reject);
  });
}

describe("hub status and agent identity", () => {
  it("records serverDir and pid sent in hello", async () => {
    const port = await start();
    await connectFakeAgent(port, { token: TOKEN, kind: "server", serverDir: "/srv/paper", pid: 4242 });
    expect(agents!.instances()[0]).toMatchObject({ kind: "server", serverDir: "/srv/paper", pid: 4242 });
  });

  it("answers a status request with versions and instances, then closes", async () => {
    const port = await start();
    await connectFakeAgent(port, { token: TOKEN, name: "Steve" });
    const { reply, code } = await ask(port, TOKEN);
    expect(code).toBe(1000);
    expect(reply.id).toBe(7);
    expect(reply.result).toMatchObject({ hubVersion: HUB_VERSION, protocolVersion: PROTOCOL_VERSION, rejected: [] });
    expect(reply.result.instances.map((i: { name: string }) => i.name)).toEqual(["Steve"]);
  });

  it("refuses a status request with the wrong token", async () => {
    const port = await start();
    const { reply, code } = await ask(port, "c".repeat(64));
    expect(code).toBe(4001);
    expect(reply.error.data.code).toBe("UNAUTHORIZED");
  });

  it("remembers refused agents so doctor can report them", async () => {
    const port = await start();
    await expect(connectFakeAgent(port, { token: TOKEN, kind: "server", agentVersion: "9.0.0", protocolVersion: 99 })).rejects.toThrow();
    expect(agents!.rejectedAgents()).toEqual([
      expect.objectContaining({ code: "PROTOCOL_MISMATCH", agentKind: "server", agentVersion: "9.0.0", protocolVersion: 99 }),
    ]);
  });
});
```

In `hub/test/helpers/fakeAgent.ts`:
- Widen the opts type to `{ token: string; kind?: "client" | "server"; name?: string; protocolVersion?: number; agentVersion?: string; serverDir?: string; pid?: number }`.
- Replace the hello params with:

```ts
      params: {
        token: opts.token, agentKind: opts.kind ?? "client", agentVersion: opts.agentVersion ?? "0.1.0",
        protocolVersion: opts.protocolVersion ?? 1, mcVersion: "26.2", instanceName: opts.name ?? "Tester",
        ...(opts.serverDir !== undefined ? { serverDir: opts.serverDir } : {}),
        ...(opts.pid !== undefined ? { pid: opts.pid } : {}),
      },
```

- [ ] **Step 6: Run them to verify they fail**

Run: `cd hub && npx vitest run test/agents-status.test.ts test/protocol.test.ts`
Expected: FAIL:
- the serverDir/pid test fails because the instance lacks `serverDir`;
- the status test closes with code 4003;
- `rejectedAgents` is not a function;
- `valid-status-request.json is accepted` fails.

- [ ] **Step 7: Implement the hub side**

`hub/src/protocol.ts`:
- Add to `HelloParams` (after `instanceName`):

```ts
  serverDir: z.string().optional(),
  pid: z.number().int().positive().optional(),
```

- Add after `HelloRequest`:

```ts
export const StatusRequest = z.object({
  jsonrpc: z.literal("2.0"),
  id: RpcId,
  method: z.literal("status"),
  params: z.object({ token: z.string().min(1) }),
});
export type StatusRequest = z.infer<typeof StatusRequest>;
```

- Change `AgentMessage` to `z.union([HelloRequest, StatusRequest, RpcResponse, EventNotification])`.

`hub/src/agents.ts`:
- Imports: add `StatusRequest` to the protocol import, and `HUB_VERSION` next to `PROTOCOL_VERSION`.
- Extend `InstanceInfo`:

```ts
  /** Server agents: the server's working directory and JVM pid (agents ≥ 0.3.0). */
  serverDir?: string;
  pid?: number;
```

- Add the exported types:

```ts
export interface RejectedAgent {
  time: number;
  code: string;
  agentKind: string;
  agentVersion: string;
  instanceName: string;
  protocolVersion: number;
}

export interface HubStatus {
  hubVersion: string;
  protocolVersion: number;
  instances: InstanceInfo[];
  rejected: RejectedAgent[];
}
```

- Add the field `private readonly rejected = new RingBuffer<RejectedAgent>(20);`.
- In `onConnection`, right after the JSON parse succeeds and before `HelloRequest.safeParse`:

```ts
      const status = StatusRequest.safeParse(parsed);
      if (status.success) {
        this.answerStatus(socket, status.data);
        return;
      }
```

- In the `fail` closure, first line:

```ts
        this.rejected.push({
          time: Date.now(), code, agentKind: params.agentKind, agentVersion: params.agentVersion,
          instanceName: params.instanceName, protocolVersion: params.protocolVersion,
        });
```

- Build `info` with the optional fields:

```ts
      const info: InstanceInfo = {
        id: `${params.agentKind}-${this.counters[params.agentKind]}`,
        kind: params.agentKind,
        name: params.instanceName,
        agentVersion: params.agentVersion,
        mcVersion: params.mcVersion,
        connectedAt: Date.now(),
        ...(params.serverDir !== undefined ? { serverDir: params.serverDir } : {}),
        ...(params.pid !== undefined ? { pid: params.pid } : {}),
      };
```

- New methods:

```ts
  private answerStatus(socket: WebSocket, req: StatusRequest): void {
    if (!tokensEqual(req.params.token, this.opts.token)) {
      socket.send(JSON.stringify({
        jsonrpc: "2.0", id: req.id,
        error: { code: -32001, message: "Invalid token", data: { code: "UNAUTHORIZED", hint: "hub.json changed; run the command again." } },
      }));
      socket.close(CLOSE_UNAUTHORIZED, "UNAUTHORIZED");
      return;
    }
    const result: HubStatus = { hubVersion: HUB_VERSION, protocolVersion: PROTOCOL_VERSION, instances: this.instances(), rejected: this.rejectedAgents() };
    socket.send(JSON.stringify({ jsonrpc: "2.0", id: req.id, result }), () => socket.close(1000, "status"));
  }

  rejectedAgents(): RejectedAgent[] {
    return this.rejected.toArray();
  }
```

- [ ] **Step 8: Run the hub tests**

Run: `cd hub && npx vitest run && npm run typecheck`
Expected: all pass. The fixture tests count 10 files.

- [ ] **Step 9: Pin it in the Paper integration test**

In `ConnectionIT.helloIdentifiesThePaperAgent`, append (add `import java.nio.file.Path;`):

```java
        Path expected = Path.of(System.getProperty("craftwire.itDir"), "server");
        assertEquals(expected.toRealPath(), Path.of(h.get("serverDir").getAsString()).toRealPath());
        long pid = h.get("pid").getAsLong();
        assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "pid " + pid + " is not alive");
```

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:integrationTest --tests '*ConnectionIT*'`
Expected: 5/5 PASS.

- [ ] **Step 10: Commit**

```bash
git add agent-core agent-paper protocol/fixtures hub/src/protocol.ts hub/src/agents.ts hub/test/helpers/fakeAgent.ts hub/test/agents-status.test.ts
git commit -m "feat(protocol): server agents send serverDir and pid; hub answers status requests"
```

---

### Task 2: Error details, build-error parser, plugin-jar reader

**Files:**
- Modify: `hub/src/errors.ts`
- Create:
  - `hub/src/dev/build-errors.ts`
  - `hub/src/dev/jar.ts`
  - `hub/test/helpers/zip.ts`
- Test:
  - `hub/test/errors.test.ts` (add one case)
  - `hub/test/build-errors.test.ts`
  - `hub/test/jar.test.ts`

**Interfaces:**
- Produces:
  - `new CraftwireError(code, message, hint?, details?: Record<string, unknown>)`. `toJSON()` includes `details` when set.
  - `interface BuildError { file: string; line: number; column?: number; message: string }`.
  - `parseBuildErrors(lines: string[], max = 50): BuildError[]`.
  - `readZipEntry(file: string, entryName: string): Buffer | undefined`.
  - `interface PluginJarInfo { name: string; version?: string; descriptor: "paper-plugin.yml" | "plugin.yml" }`.
  - `pluginInfoOfJar(file: string): PluginJarInfo | undefined`.
  - `pluginJarsNamed(pluginsDir: string, name: string): string[]`.
  - Test helper `makeZip(file: string, entries: Record<string, string>, opts?: { deflate?: boolean }): string`.

- [ ] **Step 1: Write the failing tests**

Append to `hub/test/errors.test.ts` (inside the existing top-level `describe`, or a new one; it imports `CraftwireError` and `toToolError` from `../src/errors.js`):

```ts
describe("error details", () => {
  it("carries structured details into the tool error", () => {
    const err = new CraftwireError("BUILD_FAILED", "Build failed", "Fix it", { errors: [{ file: "A.java", line: 3 }] });
    const payload = JSON.parse((toToolError(err).content[0] as { text: string }).text);
    expect(payload).toEqual({ code: "BUILD_FAILED", message: "Build failed", hint: "Fix it", details: { errors: [{ file: "A.java", line: 3 }] } });
  });
});
```

`hub/test/build-errors.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { parseBuildErrors } from "../src/dev/build-errors.js";

describe("parseBuildErrors", () => {
  it("reads javac errors with their symbol and location lines", () => {
    const out = [
      "> Task :compileJava",
      "/p/src/main/java/a/Foo.java:3: error: cannot find symbol",
      "        Bar b;",
      "        ^",
      "  symbol:   class Bar",
      "  location: class Foo",
      "1 error",
    ];
    expect(parseBuildErrors(out)).toEqual([
      { file: "/p/src/main/java/a/Foo.java", line: 3, message: "cannot find symbol; symbol: class Bar; location: class Foo" },
    ]);
  });

  it("handles Windows paths and CRLF output", () => {
    const out = ["C:\\dev\\plugin\\src\\Foo.java:12: error: ';' expected\r", "1 error\r"];
    expect(parseBuildErrors(out)).toEqual([{ file: "C:\\dev\\plugin\\src\\Foo.java", line: 12, message: "';' expected" }]);
  });

  it("reads both Kotlin formats", () => {
    const out = [
      "e: file:///home/u/p/src/Main.kt:7:13 Unresolved reference 'foo'.",
      "e: file:///C:/dev/p/src/Other.kt:2:1 Expecting a top level declaration",
      "e: /home/u/p/src/Old.kt: (4, 9): Type mismatch",
    ];
    expect(parseBuildErrors(out)).toEqual([
      { file: "/home/u/p/src/Main.kt", line: 7, column: 13, message: "Unresolved reference 'foo'." },
      { file: "C:/dev/p/src/Other.kt", line: 2, column: 1, message: "Expecting a top level declaration" },
      { file: "/home/u/p/src/Old.kt", line: 4, column: 9, message: "Type mismatch" },
    ]);
  });

  it("reads Maven errors once even though Maven repeats them", () => {
    const line = "[ERROR] /p/src/main/java/a/Foo.java:[3,9] cannot find symbol";
    expect(parseBuildErrors(["[INFO] Compiling", line, "[ERROR]   symbol:   class Bar", line])).toEqual([
      { file: "/p/src/main/java/a/Foo.java", line: 3, column: 9, message: "cannot find symbol" },
    ]);
  });

  it("ignores warnings and stops at max", () => {
    const out = ["/p/A.java:1: warning: [deprecation] x", ...Array.from({ length: 5 }, (_, i) => `/p/B.java:${i + 1}: error: e${i}`)];
    expect(parseBuildErrors(out, 3).map((e) => e.line)).toEqual([1, 2, 3]);
  });
});
```

`hub/test/helpers/zip.ts`:

```ts
import { writeFileSync } from "node:fs";
import { crc32, deflateRawSync } from "node:zlib";

/** Writes a minimal zip with stored (default) or deflated entries, for jar-reading tests. */
export function makeZip(file: string, entries: Record<string, string>, opts: { deflate?: boolean } = {}): string {
  const locals: Buffer[] = [];
  const centrals: Buffer[] = [];
  let offset = 0;
  const method = opts.deflate ? 8 : 0;
  for (const [name, text] of Object.entries(entries)) {
    const raw = Buffer.from(text, "utf8");
    const data = opts.deflate ? deflateRawSync(raw) : raw;
    const nameBuf = Buffer.from(name, "utf8");
    const crc = crc32(raw);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(method, 8);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(raw.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(method, 10);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(data.length, 20);
    central.writeUInt32LE(raw.length, 24);
    central.writeUInt16LE(nameBuf.length, 28);
    central.writeUInt32LE(offset, 42);
    locals.push(local, nameBuf, data);
    centrals.push(central, nameBuf);
    offset += 30 + nameBuf.length + data.length;
  }
  const cd = Buffer.concat(centrals);
  const eocd = Buffer.alloc(22);
  const n = Object.keys(entries).length;
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(n, 8);
  eocd.writeUInt16LE(n, 10);
  eocd.writeUInt32LE(cd.length, 12);
  eocd.writeUInt32LE(offset, 16);
  writeFileSync(file, Buffer.concat([...locals, cd, eocd]));
  return file;
}
```

`hub/test/jar.test.ts`:

```ts
import { existsSync, mkdirSync, mkdtempSync, readdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { pluginInfoOfJar, pluginJarsNamed, readZipEntry } from "../src/dev/jar.js";
import { makeZip } from "./helpers/zip.js";

const tmp = () => mkdtempSync(join(tmpdir(), "cw-jar-"));

describe("plugin jars", () => {
  it("reads plugin.yml from a stored jar", () => {
    const jar = makeZip(join(tmp(), "demo.jar"), { "META-INF/MANIFEST.MF": "Manifest-Version: 1.0\n", "plugin.yml": "name: Demo\nversion: 1.2.3\nmain: a.B\n" });
    expect(pluginInfoOfJar(jar)).toEqual({ name: "Demo", version: "1.2.3", descriptor: "plugin.yml" });
  });

  it("prefers paper-plugin.yml and handles quotes, comments and deflate", () => {
    const jar = makeZip(join(tmp(), "fancy.jar"), {
      "plugin.yml": "name: Legacy\n",
      "paper-plugin.yml": "name: \"Fancy\" # the name\nversion: '2.0'\n",
    }, { deflate: true });
    expect(pluginInfoOfJar(jar)).toEqual({ name: "Fancy", version: "2.0", descriptor: "paper-plugin.yml" });
    expect(readZipEntry(jar, "plugin.yml")?.toString("utf8")).toBe("name: Legacy\n");
    expect(readZipEntry(jar, "missing.yml")).toBeUndefined();
  });

  it("returns undefined for non-plugins and non-zips", () => {
    const dir = tmp();
    expect(pluginInfoOfJar(makeZip(join(dir, "lib.jar"), { "a/B.class": "x" }))).toBeUndefined();
    writeFileSync(join(dir, "text.jar"), "not a zip");
    expect(pluginInfoOfJar(join(dir, "text.jar"))).toBeUndefined();
  });

  it("finds installed jars of a plugin case-insensitively", () => {
    const plugins = join(tmp(), "plugins");
    mkdirSync(plugins);
    makeZip(join(plugins, "craftwire-paper-0.2.0.jar"), { "plugin.yml": "name: Craftwire\n" });
    makeZip(join(plugins, "other.jar"), { "plugin.yml": "name: Other\n" });
    writeFileSync(join(plugins, "notes.txt"), "craftwire");
    expect(pluginJarsNamed(plugins, "craftwire")).toEqual([join(plugins, "craftwire-paper-0.2.0.jar")]);
    expect(pluginJarsNamed(join(plugins, "missing"), "Craftwire")).toEqual([]);
  });

  const libs = join(__dirname, "..", "..", "agent-paper", "build", "libs");
  const real = existsSync(libs) ? readdirSync(libs).find((f) => /^craftwire-paper-.*\.jar$/.test(f)) : undefined;
  it.runIf(real !== undefined)("reads the real Craftwire plugin jar", () => {
    expect(pluginInfoOfJar(join(libs, real!))?.name).toBe("Craftwire");
  });
});
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd hub && npx vitest run test/errors.test.ts test/build-errors.test.ts test/jar.test.ts`
Expected: FAIL:
- the errors test lacks `details`;
- the other two files fail to import `../src/dev/build-errors.js` and `../src/dev/jar.js`.

- [ ] **Step 3: Implement**

`hub/src/errors.ts`, the class becomes:

```ts
export class CraftwireError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly hint?: string,
    readonly details?: Record<string, unknown>,
  ) {
    super(message);
    this.name = "CraftwireError";
  }

  toJSON(): { code: string; message: string; hint?: string; details?: Record<string, unknown> } {
    const out: { code: string; message: string; hint?: string; details?: Record<string, unknown> } = { code: this.code, message: this.message };
    if (this.hint !== undefined) out.hint = this.hint;
    if (this.details !== undefined) out.details = this.details;
    return out;
  }
}
```

`hub/src/dev/build-errors.ts`:

```ts
export interface BuildError {
  file: string;
  line: number;
  column?: number;
  message: string;
}

const JAVAC = /^(.+?\.java):(\d+): error: (.+)$/;
const KOTLIN_URL = /^e: (file:\/\/\S+?\.kts?):(\d+):(\d+) (.+)$/;
const KOTLIN_OLD = /^e: (.+?\.kts?): \((\d+), (\d+)\): (.+)$/;
const MAVEN = /^\[ERROR\] (.+?\.(?:java|kt)):\[(\d+),(\d+)\] (.+)$/;
// javac prints these under "cannot find symbol" and similar errors.
const DETAIL = /^\s+(symbol|location):\s*(.+)$/;

/** Compiler errors from Gradle (javac, kotlinc) or Maven output, deduplicated, at most `max`. */
export function parseBuildErrors(lines: string[], max = 50): BuildError[] {
  const clean = lines.map((l) => l.replace(/\r$/, ""));
  const out: BuildError[] = [];
  const seen = new Set<string>();
  for (let i = 0; i < clean.length && out.length < max; i++) {
    const err = matchLine(clean[i]!);
    if (!err) continue;
    if (JAVAC.test(clean[i]!)) {
      for (let j = i + 1; j < Math.min(clean.length, i + 5) && !matchLine(clean[j]!); j++) {
        const d = DETAIL.exec(clean[j]!);
        if (d) err.message += `; ${d[1]}: ${d[2]!.trim()}`;
      }
    }
    const key = `${err.file}:${err.line}:${err.message}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(err);
  }
  return out;
}

function matchLine(line: string): BuildError | undefined {
  let m = JAVAC.exec(line);
  if (m) return { file: m[1]!, line: Number(m[2]), message: m[3]! };
  m = KOTLIN_URL.exec(line);
  if (m) return { file: fileUrlToPath(m[1]!), line: Number(m[2]), column: Number(m[3]), message: m[4]! };
  m = KOTLIN_OLD.exec(line);
  if (m) return { file: m[1]!, line: Number(m[2]), column: Number(m[3]), message: m[4]! };
  m = MAVEN.exec(line);
  if (m) return { file: m[1]!, line: Number(m[2]), column: Number(m[3]), message: m[4]! };
  return undefined;
}

// url.fileURLToPath rejects drive-less paths on Windows, so build output from either OS is decoded by hand.
function fileUrlToPath(url: string): string {
  const p = decodeURIComponent(url.slice("file://".length));
  return /^\/[A-Za-z]:\//.test(p) ? p.slice(1) : p;
}
```

`hub/src/dev/jar.ts`:

```ts
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { inflateRawSync } from "node:zlib";

/** One entry of a zip/jar, or undefined when absent. Stored and deflated entries; no zip64 (plugin jars are small). */
export function readZipEntry(file: string, entryName: string): Buffer | undefined {
  const buf = readFileSync(file);
  const eocd = findEndOfCentralDirectory(buf);
  if (eocd < 0) throw new Error(`${file} is not a zip file`);
  const count = buf.readUInt16LE(eocd + 10);
  let p = buf.readUInt32LE(eocd + 16);
  for (let n = 0; n < count; n++) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error(`${file}: corrupt central directory`);
    const method = buf.readUInt16LE(p + 10);
    const size = buf.readUInt32LE(p + 20);
    const nameLen = buf.readUInt16LE(p + 28);
    const extraLen = buf.readUInt16LE(p + 30);
    const commentLen = buf.readUInt16LE(p + 32);
    const local = buf.readUInt32LE(p + 42);
    if (buf.toString("utf8", p + 46, p + 46 + nameLen) === entryName) {
      // Sizes come from the central directory: jars written with data descriptors leave them 0 in the local header.
      const start = local + 30 + buf.readUInt16LE(local + 26) + buf.readUInt16LE(local + 28);
      const data = buf.subarray(start, start + size);
      if (method === 0) return Buffer.from(data);
      if (method === 8) return inflateRawSync(data);
      throw new Error(`${file}: unsupported compression ${method} for ${entryName}`);
    }
    p += 46 + nameLen + extraLen + commentLen;
  }
  return undefined;
}

function findEndOfCentralDirectory(buf: Buffer): number {
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65557); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) return i;
  }
  return -1;
}

export interface PluginJarInfo {
  name: string;
  version?: string;
  descriptor: "paper-plugin.yml" | "plugin.yml";
}

/** Name and version from a jar's paper-plugin.yml or plugin.yml; undefined when the file is not a plugin jar. */
export function pluginInfoOfJar(file: string): PluginJarInfo | undefined {
  for (const descriptor of ["paper-plugin.yml", "plugin.yml"] as const) {
    let entry: Buffer | undefined;
    try {
      entry = readZipEntry(file, descriptor);
    } catch {
      return undefined;
    }
    if (!entry) continue;
    const text = entry.toString("utf8");
    const name = yamlScalar(text, "name");
    if (!name) return undefined;
    const version = yamlScalar(text, "version");
    return version === undefined ? { name, descriptor } : { name, version, descriptor };
  }
  return undefined;
}

function yamlScalar(text: string, key: string): string | undefined {
  const m = new RegExp(`^${key}:[ \\t]*(.*)$`, "m").exec(text);
  if (!m) return undefined;
  const raw = m[1]!.replace(/\s+#.*$/, "").trim();
  const value = raw.replace(/^(['"])(.*)\1$/, "$2").trim();
  return value === "" ? undefined : value;
}

/** Top-level jars in `pluginsDir` whose plugin name equals `name`, ignoring case. */
export function pluginJarsNamed(pluginsDir: string, name: string): string[] {
  if (!existsSync(pluginsDir)) return [];
  const want = name.toLowerCase();
  return readdirSync(pluginsDir, { withFileTypes: true })
    .filter((e) => e.isFile() && e.name.toLowerCase().endsWith(".jar"))
    .map((e) => join(pluginsDir, e.name))
    .filter((f) => pluginInfoOfJar(f)?.name.toLowerCase() === want)
    .sort();
}
```

- [ ] **Step 4: Run the tests**

Run: `cd hub && npx vitest run test/errors.test.ts test/build-errors.test.ts test/jar.test.ts && npm run typecheck`
Expected: all PASS (the real-jar case runs locally, where `agent-paper/build/libs` exists).

- [ ] **Step 5: Commit**

```bash
git add hub/src/errors.ts hub/src/dev/build-errors.ts hub/src/dev/jar.ts hub/test/helpers/zip.ts hub/test/errors.test.ts hub/test/build-errors.test.ts hub/test/jar.test.ts
git commit -m "feat(hub): error details, compiler-error parser and plugin jar reader"
```

---

### Task 3: Launch resolution and the ServerManager

**Files:**
- Create:
  - `hub/src/dev/paths.ts`
  - `hub/src/dev/launch.ts`
  - `hub/src/dev/server-manager.ts`
  - `hub/test/helpers/fake-paper.mjs`
  - `hub/test/helpers/servers.ts`
- Test:
  - `hub/test/launch.test.ts`
  - `hub/test/server-manager.test.ts`

**Interfaces:**
- Consumes (Task 1): `AgentServer` events `connected`/`disconnected` with `InstanceInfo.serverDir/pid`, plus `agents.request(id, "server.command", {command:"stop"})`.
- Consumes (Task 2): `pluginJarsNamed`, `CraftwireError(…, details)`.
- Produces:
  - **paths.ts:** `pathKey(p: string): string`, `samePath(a: string, b: string): boolean`.
  - **launch.ts:**
    - `MIN_JAVA = 25`, `DEFAULT_JVM_ARGS`, `EULA_HINT`;
    - `tokenize(line)`;
    - `interface ScriptLaunch { java: string; jvmArgs: string[]; jar: string; serverArgs: string[] }`, `parseStartScript(text): ScriptLaunch | undefined`;
    - `interface LaunchParams { java?: string; jvmArgs?: string[]; jar?: string }`;
    - `interface Launch { command: string; args: string[]; jar: string; source: string }`, `resolveLaunch(serverDir, p?): Launch`;
    - `javaMajorVersion(text): number | undefined`, `probeJavaMajor(java): Promise<number>`;
    - `eulaAccepted(dir): boolean`.
  - **server-manager.ts:**
    - `type ServerState`, `interface StartOptions extends LaunchParams { serverDir: string; timeoutMs?: number }`, `type RestartOptions = Partial<StartOptions> & { takeOver?: boolean }`;
    - `interface ServerStatus`, `interface ExternalServer { instance; name; serverDir; pid? }`, `interface StopResult`;
    - `class ServerManager(opts: { agents; home; javaMajor?; agentWaitMs?; stopTimeoutMs? })` with:
      - `start(o)`, `stop(serverDir?, {takeOver?})`, `restart(o, between?)`;
      - `status(serverDir?, tail?)` → `{servers: ServerStatus[], external: ExternalServer[]}`;
      - `runningState(dir)` → `"managed"|"external"|"none"`;
      - `external(dir)`, `externals()`, `resolveDir(serverDir?)`, `shutdown()`.
    - Helpers: `diagnoseCrash(lines)`, `definedOnly(o)`, `pidAlive(pid)`, `waitUntil(check, ms, everyMs?)`, `notManagedError(ext)`.
  - **Test helpers:** `FAKE_PAPER`, `makeServerDir({eula?, craftwire?})`, `fakeLaunch(mode)`.

- [ ] **Step 1: Write the test helpers**

`hub/test/helpers/fake-paper.mjs`:

```js
// Stands in for a Paper server in hub tests: prints Paper's startup and Done lines and obeys "stop" on stdin.
// --mode=ok|agent|crash|hang|nostop. In agent mode it also connects to the hub the way the Craftwire plugin does.
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { createInterface } from "node:readline";
import WebSocket from "ws";

const mode = (process.argv.find((a) => a.startsWith("--mode=")) ?? "--mode=ok").slice("--mode=".length);
const say = (line) => new Promise((resolve) => process.stdout.write(line + "\n", resolve));
let socket;

async function shutdown() {
  await say("Stopping server");
  socket?.close();
  process.exit(0);
}

function connectAgent() {
  const { port, token } = JSON.parse(readFileSync(join(process.env.CRAFTWIRE_HOME, "hub.json"), "utf8"));
  socket = new WebSocket(`ws://127.0.0.1:${port}/`);
  return new Promise((resolve, reject) => {
    socket.once("error", reject);
    socket.once("open", () => socket.send(JSON.stringify({
      jsonrpc: "2.0", id: 0, method: "hello",
      params: { token, agentKind: "server", agentVersion: "0.3.0", protocolVersion: 1, mcVersion: "26.2", instanceName: "fake", serverDir: process.cwd(), pid: process.pid },
    })));
    socket.on("message", (raw) => {
      const msg = JSON.parse(String(raw));
      if (msg.id === 0) return resolve();
      if (msg.method === undefined) return;
      const send = (body) => socket.send(JSON.stringify({ jsonrpc: "2.0", id: msg.id, ...body }));
      if (msg.method === "server.command" && msg.params.command === "stop") {
        send({ result: { command: "stop", success: true, output: [] } });
        void shutdown();
        return;
      }
      if (msg.method === "plugin.manage" && msg.params.action === "info") {
        const want = String(msg.params.name).toLowerCase();
        const jar = readdirSync("plugins").find((f) => f.toLowerCase().includes(want));
        if (!jar) return send({ error: { code: -32000, message: `No plugin named ${msg.params.name}`, data: { code: "PLUGIN_NOT_FOUND" } } });
        return send({ result: { name: msg.params.name, version: "1.0", enabled: true, jar } });
      }
      send({ error: { code: -32601, message: `unknown ${msg.method}`, data: { code: "UNKNOWN_METHOD" } } });
    });
  });
}

await say("Starting minecraft server version 26.2");
if (mode === "crash") {
  await say("**** FAILED TO BIND TO PORT!");
  process.exit(1);
}
createInterface({ input: process.stdin }).on("line", (line) => {
  if (line.trim() === "stop" && mode !== "nostop") void shutdown();
});
if (mode === "agent") await connectAgent();
if (mode !== "hang") await say('Done (1.234s)! For help, type "help"');
setInterval(() => {}, 1 << 30);
```

`hub/test/helpers/servers.ts`:

```ts
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { makeZip } from "./zip.js";

export const FAKE_PAPER = join(__dirname, "fake-paper.mjs");

/** A throwaway server folder: empty server.jar, eula.txt (true unless told otherwise), plugins/, optionally a Craftwire jar. */
export function makeServerDir(o: { eula?: boolean; craftwire?: boolean } = {}): string {
  const dir = mkdtempSync(join(tmpdir(), "cw-srv-"));
  mkdirSync(join(dir, "plugins"));
  writeFileSync(join(dir, "server.jar"), "");
  writeFileSync(join(dir, "eula.txt"), `eula=${o.eula ?? true}\n`);
  if (o.craftwire) makeZip(join(dir, "plugins", "craftwire.jar"), { "plugin.yml": "name: Craftwire\nversion: 0.3.0\nmain: a.B\n" });
  return dir;
}

/** Launch parameters that run fake-paper.mjs with node: node ignores -jar/--nogui after the script path. */
export const fakeLaunch = (mode: "ok" | "agent" | "crash" | "hang" | "nostop") =>
  ({ java: process.execPath, jvmArgs: [FAKE_PAPER, `--mode=${mode}`], jar: "server.jar" });
```

- [ ] **Step 2: Write the failing launch tests**

`hub/test/launch.test.ts`:

```ts
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { samePath } from "../src/dev/paths.js";
import { eulaAccepted, javaMajorVersion, parseStartScript, resolveLaunch, tokenize } from "../src/dev/launch.js";

const AIKAR = "java -Xms6144M -Xmx6144M --add-modules=jdk.incubator.vector -XX:+UseG1GC -Dusing.aikars.flags=https://mcflags.emc.gs -jar server.jar --nogui";
const dir = (files: Record<string, string>) => {
  const d = mkdtempSync(join(tmpdir(), "cw-launch-"));
  for (const [f, text] of Object.entries(files)) writeFileSync(join(d, f), text);
  return d;
};

describe("start scripts", () => {
  it("tokenizes with double quotes", () => {
    expect(tokenize(`"C:\\Program Files\\Java\\bin\\java.exe" -Xmx2G  -jar "my server.jar"`)).toEqual(["C:\\Program Files\\Java\\bin\\java.exe", "-Xmx2G", "-jar", "my server.jar"]);
  });

  it("reads the java line of a typical start.bat", () => {
    expect(parseStartScript(`@echo off\r\n${AIKAR}\r\npause\r\n`)).toEqual({
      java: "java",
      jvmArgs: ["-Xms6144M", "-Xmx6144M", "--add-modules=jdk.incubator.vector", "-XX:+UseG1GC", "-Dusing.aikars.flags=https://mcflags.emc.gs"],
      jar: "server.jar",
      serverArgs: ["--nogui"],
    });
  });

  it("joins continuation lines and reads sh scripts", () => {
    expect(parseStartScript("#!/bin/sh\nexec java -Xmx4G \\\n  -jar paper-26.2.jar nogui\n")).toEqual({ java: "java", jvmArgs: ["-Xmx4G"], jar: "paper-26.2.jar", serverArgs: ["nogui"] });
    expect(parseStartScript("java -Xmx1G ^\r\n -jar server.jar\r\n")?.jar).toBe("server.jar");
  });

  it("gives up on variables it cannot expand", () => {
    expect(parseStartScript("java -Xmx%RAM% -jar server.jar\n")).toBeUndefined();
    expect(parseStartScript('"$JAVA" -jar server.jar\n')).toBeUndefined();
    expect(parseStartScript("echo hi\n")).toBeUndefined();
  });
});

describe("resolveLaunch", () => {
  it("uses the start script's flags, forces UTF-8 console output and adds --nogui once", () => {
    const d = dir({ "start.bat": AIKAR, "start.sh": AIKAR, "server.jar": "" });
    const l = resolveLaunch(d);
    expect(l.command).toBe("java");
    expect(l.source).toMatch(/^start\.(bat|sh)$/);
    expect(l.args.slice(0, 2)).toEqual(["-Xms6144M", "-Xmx6144M"]);
    expect(l.args).toContain("-Dstdout.encoding=UTF-8");
    expect(l.args.slice(-3)).toEqual(["-jar", join(d, "server.jar"), "--nogui"]);
    expect(l.args.filter((a) => a.includes("nogui"))).toHaveLength(1);
  });

  it("lets parameters override the script", () => {
    const d = dir({ "start.sh": AIKAR, "start.bat": AIKAR, "server.jar": "", "other.jar": "" });
    const l = resolveLaunch(d, { java: "/opt/jdk25/bin/java", jvmArgs: ["-Xmx1G"], jar: "other.jar" });
    expect(l).toMatchObject({ command: "/opt/jdk25/bin/java", source: "jvmArgs", jar: join(d, "other.jar") });
    expect(l.args[0]).toBe("-Xmx1G");
  });

  it("falls back to defaults and a single paper jar", () => {
    const l = resolveLaunch(dir({ "paper-26.2-130.jar": "" }));
    expect(l.source).toBe("defaults");
    expect(l.args).toContain("-Xmx2G");
    expect(l.jar).toMatch(/paper-26\.2-130\.jar$/);
  });

  it("fails clearly without a server jar", () => {
    expect(() => resolveLaunch(dir({}))).toThrow(expect.objectContaining({ code: "SERVER_JAR_NOT_FOUND" }));
  });
});

describe("environment checks", () => {
  it("parses java -version output", () => {
    expect(javaMajorVersion('openjdk version "26.0.1" 2026-07-21\nOpenJDK Runtime Environment')).toBe(26);
    expect(javaMajorVersion('openjdk version "25" 2025-09-16')).toBe(25);
    expect(javaMajorVersion('java version "1.8.0_391"')).toBe(8);
    expect(javaMajorVersion("garbage")).toBeUndefined();
  });

  it("checks the EULA without changing it", () => {
    expect(eulaAccepted(dir({ "eula.txt": "#By changing...\neula=true\n" }))).toBe(true);
    expect(eulaAccepted(dir({ "eula.txt": "eula=false\n" }))).toBe(false);
    expect(eulaAccepted(dir({}))).toBe(false);
  });

  it.runIf(process.platform === "win32")("compares Windows paths ignoring case and slash style", () => {
    expect(samePath("C:\\Users\\pc\\Desktop\\server", "c:/users/pc/desktop/server/")).toBe(true);
  });
});
```

- [ ] **Step 3: Run it to verify it fails**

Run: `cd hub && npx vitest run test/launch.test.ts`
Expected: FAIL, "Failed to load url ../src/dev/launch.js".

- [ ] **Step 4: Implement paths.ts and launch.ts**

`hub/src/dev/paths.ts`:

```ts
import { resolve } from "node:path";

/** Absolute path used as a map key: resolved (no trailing separator, native slashes), case-folded on Windows. */
export function pathKey(p: string): string {
  const r = resolve(p);
  return process.platform === "win32" ? r.toLowerCase() : r;
}

export const samePath = (a: string, b: string): boolean => pathKey(a) === pathKey(b);
```

`hub/src/dev/launch.ts`:

```ts
import { spawn } from "node:child_process";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { isAbsolute, join, resolve } from "node:path";
import { CraftwireError } from "../errors.js";

export const MIN_JAVA = 25;
export const DEFAULT_JVM_ARGS = ["-Xms1G", "-Xmx2G"];
export const EULA_HINT =
  "Craftwire never accepts the Minecraft EULA for you. Ask the user to read https://aka.ms/MinecraftEULA and, if they agree, set eula=true in eula.txt themselves.";

const SCRIPTS = process.platform === "win32"
  ? ["start.bat", "start.cmd", "run.bat", "start.sh", "run.sh"]
  : ["start.sh", "run.sh", "start.bat", "start.cmd", "run.bat"];
const JAVA_EXE = /^(?:.*[\\/])?javaw?(?:\.exe)?$/i;

/** Splits a command line on whitespace, honouring single and double quotes. */
export function tokenize(line: string): string[] {
  const out: string[] = [];
  let cur = "";
  let quote: string | undefined;
  let has = false;
  for (const ch of line) {
    if (quote) {
      if (ch === quote) quote = undefined;
      else cur += ch;
      continue;
    }
    if (ch === '"' || ch === "'") {
      quote = ch;
      has = true;
    } else if (/\s/.test(ch)) {
      if (has) out.push(cur);
      cur = "";
      has = false;
    } else {
      cur += ch;
      has = true;
    }
  }
  if (has) out.push(cur);
  return out;
}

export interface ScriptLaunch {
  java: string;
  jvmArgs: string[];
  jar: string;
  serverArgs: string[];
}

/** The first `java … -jar X` line of a start script; undefined when there is none or it uses variables. */
export function parseStartScript(text: string): ScriptLaunch | undefined {
  const joined = text.replace(/\^\r?\n/g, " ").replace(/\\\r?\n/g, " ");
  for (const raw of joined.split(/\r?\n/)) {
    const line = raw.trim();
    if (line === "" || /^(?:::|rem\b|#|@?echo\b)/i.test(line)) continue;
    const tokens = tokenize(line);
    const at = tokens.findIndex((t) => JAVA_EXE.test(t));
    if (at === -1) continue;
    const jarAt = tokens.indexOf("-jar", at + 1);
    if (jarAt === -1 || jarAt + 1 >= tokens.length) continue;
    if (tokens.slice(at).some((t) => /[%$]/.test(t))) return undefined;
    return { java: tokens[at]!, jvmArgs: tokens.slice(at + 1, jarAt), jar: tokens[jarAt + 1]!, serverArgs: tokens.slice(jarAt + 2) };
  }
  return undefined;
}

export interface LaunchParams {
  java?: string;
  jvmArgs?: string[];
  jar?: string;
}

export interface Launch {
  command: string;
  args: string[];
  jar: string;
  /** Where the JVM flags came from: "jvmArgs", a script file name, or "defaults". */
  source: string;
}

/** The java command for a server folder: parameters first, then its start script, then defaults. */
export function resolveLaunch(serverDir: string, p: LaunchParams = {}): Launch {
  const script = SCRIPTS.find((s) => existsSync(join(serverDir, s)));
  const parsed = script ? parseStartScript(readFileSync(join(serverDir, script), "utf8")) : undefined;
  const jarName = p.jar ?? parsed?.jar ?? defaultJar(serverDir);
  if (!jarName) throw new CraftwireError("SERVER_JAR_NOT_FOUND", `No server jar in ${serverDir}`, "Put the Paper jar there as server.jar, or pass jar.");
  const jar = resolve(serverDir, jarName);
  if (!existsSync(jar)) throw new CraftwireError("SERVER_JAR_NOT_FOUND", `${jar} does not exist`, "Check the jar name, or pass jar.");
  const jvm = p.jvmArgs ?? parsed?.jvmArgs ?? DEFAULT_JVM_ARGS;
  // Windows JVMs write a pipe in the ANSI code page; the hub decodes UTF-8.
  const encoding = jvm.some((a) => a.startsWith("-Dstdout.encoding")) ? [] : ["-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8"];
  const serverArgs = (parsed?.serverArgs ?? []).filter((a) => a !== "nogui" && a !== "--nogui");
  let command = p.java ?? parsed?.java ?? "java";
  if (/[\\/]/.test(command) && !isAbsolute(command)) command = resolve(serverDir, command);
  return {
    command,
    args: [...jvm, ...encoding, "-jar", jar, ...serverArgs, "--nogui"],
    jar,
    source: p.jvmArgs ? "jvmArgs" : parsed ? script! : "defaults",
  };
}

function defaultJar(dir: string): string | undefined {
  if (existsSync(join(dir, "server.jar"))) return "server.jar";
  if (!existsSync(dir)) return undefined;
  const papers = readdirSync(dir).filter((f) => /^paper.*\.jar$/i.test(f));
  return papers.length === 1 ? papers[0] : undefined;
}

/** Major version from `java -version` output ("1.8" style included). */
export function javaMajorVersion(text: string): number | undefined {
  const m = /version "(\d+)(?:\.(\d+))?/.exec(text);
  if (!m) return undefined;
  return m[1] === "1" && m[2] ? Number(m[2]) : Number(m[1]);
}

export function probeJavaMajor(java: string): Promise<number> {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(java, ["-version"], { windowsHide: true });
    let out = "";
    child.stdout.on("data", (d) => (out += d));
    child.stderr.on("data", (d) => (out += d));
    child.once("error", () => reject(new CraftwireError("JAVA_NOT_FOUND", `Cannot run ${java}`, `Install Java ${MIN_JAVA}+ or pass java: the full path to a java executable.`)));
    child.once("close", () => {
      const major = javaMajorVersion(out);
      if (major === undefined) reject(new CraftwireError("JAVA_NOT_FOUND", `${java} -version printed no version`, `Pass java: the full path to a Java ${MIN_JAVA}+ executable.`));
      else resolvePromise(major);
    });
  });
}

export function eulaAccepted(serverDir: string): boolean {
  const file = join(serverDir, "eula.txt");
  return existsSync(file) && /^\s*eula\s*=\s*true\s*$/im.test(readFileSync(file, "utf8"));
}
```

- [ ] **Step 5: Run the launch tests**

Run: `cd hub && npx vitest run test/launch.test.ts`
Expected: PASS.

- [ ] **Step 6: Write the failing ServerManager tests**

`hub/test/server-manager.test.ts`:

```ts
import { spawn } from "node:child_process";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { AgentServer } from "../src/agents.js";
import { writeHubConfig } from "../src/config.js";
import { diagnoseCrash, pidAlive, ServerManager, type ServerManagerOptions, waitUntil } from "../src/dev/server-manager.js";
import { FAKE_PAPER, fakeLaunch, makeServerDir } from "./helpers/servers.js";

const TOKEN = "d".repeat(64);
const cleanups: (() => Promise<void>)[] = [];
afterEach(async () => {
  while (cleanups.length) await cleanups.pop()!();
});

async function setup(o: Partial<ServerManagerOptions> = {}) {
  const home = mkdtempSync(join(tmpdir(), "cw-home-"));
  const agents = new AgentServer({ token: TOKEN, port: 0 });
  const port = await agents.listen();
  writeHubConfig({ port, token: TOKEN }, home);
  const servers = new ServerManager({ agents, home, javaMajor: async () => 25, agentWaitMs: 500, stopTimeoutMs: 3000, ...o });
  cleanups.push(async () => { await servers.shutdown(); await agents.close(); });
  return { home, agents, servers };
}

describe("ServerManager", () => {
  it("starts a server, waits for Done and stops it with the console stop command", async () => {
    const { servers } = await setup();
    const dir = makeServerDir();
    const s = await servers.start({ serverDir: dir, ...fakeLaunch("ok") });
    expect(s.state).toBe("running");
    expect(s.consoleTail.join("\n")).toContain("Done (");
    expect(s.warning).toMatch(/not in plugins/);
    expect(s.launch?.args).toContain("-Dstdout.encoding=UTF-8");
    const stopped = await servers.stop(dir);
    expect(stopped).toMatchObject({ stopped: true, external: false, forced: false, exitCode: 0 });
    expect(servers.status(dir).servers[0]!.state).toBe("stopped");
  });

  it("refuses to start without an accepted EULA and leaves eula.txt alone", async () => {
    const { servers } = await setup();
    const dir = makeServerDir({ eula: false });
    await expect(servers.start({ serverDir: dir, ...fakeLaunch("ok") })).rejects.toMatchObject({ code: "EULA_NOT_ACCEPTED", hint: expect.stringContaining("aka.ms/MinecraftEULA") });
    expect(readFileSync(join(dir, "eula.txt"), "utf8")).toBe("eula=false\n");
  });

  it("refuses Java older than 25", async () => {
    const { servers } = await setup({ javaMajor: async () => 21 });
    await expect(servers.start({ serverDir: makeServerDir(), ...fakeLaunch("ok") })).rejects.toMatchObject({ code: "JAVA_TOO_OLD" });
  });

  it("diagnoses a crash during startup from the console", async () => {
    const { servers } = await setup();
    const dir = makeServerDir();
    const err = await servers.start({ serverDir: dir, ...fakeLaunch("crash") }).catch((e: unknown) => e);
    expect(err).toMatchObject({ code: "PORT_IN_USE", details: { exitCode: 1 } });
    expect((err as { details: { consoleTail: string[] } }).details.consoleTail.join("\n")).toContain("FAILED TO BIND");
    expect(servers.status(dir).servers[0]!.state).toBe("crashed");
  });

  it("times out without killing the server, which can still be stopped", async () => {
    const { servers } = await setup();
    const dir = makeServerDir();
    await expect(servers.start({ serverDir: dir, ...fakeLaunch("hang"), timeoutMs: 1000 })).rejects.toMatchObject({ code: "TIMEOUT" });
    expect(servers.status(dir).servers[0]!.state).toBe("starting");
    expect(await servers.stop(dir)).toMatchObject({ forced: false });
  });

  it("kills a server that ignores stop", async () => {
    const { servers } = await setup({ stopTimeoutMs: 1000 });
    const dir = makeServerDir();
    await servers.start({ serverDir: dir, ...fakeLaunch("nostop") });
    expect(await servers.stop(dir)).toMatchObject({ forced: true });
  });

  it("waits for the Craftwire agent when the plugin is installed", async () => {
    const { servers, agents } = await setup();
    const dir = makeServerDir({ craftwire: true });
    const s = await servers.start({ serverDir: dir, ...fakeLaunch("agent") });
    expect(s.agent).toMatch(/^server-\d+$/);
    expect(s.warning).toBeUndefined();
    expect(agents.instances()[0]).toMatchObject({ id: s.agent, pid: s.pid });
  });

  it("warns when the plugin is installed but never connects", async () => {
    const { servers } = await setup();
    const s = await servers.start({ serverDir: makeServerDir({ craftwire: true }), ...fakeLaunch("ok") });
    expect(s.state).toBe("running");
    expect(s.agent).toBeNull();
    expect(s.warning).toMatch(/did not connect/);
  });

  it("treats a connected server it did not start as external and stops it only with takeOver", async () => {
    const { servers, agents, home } = await setup();
    const dir = makeServerDir();
    const child = spawn(process.execPath, [FAKE_PAPER, "--mode=agent"], { cwd: dir, env: { ...process.env, CRAFTWIRE_HOME: home }, stdio: "pipe" });
    cleanups.push(async () => { child.kill(); });
    expect(await waitUntil(() => agents.instances().length === 1, 5000)).toBe(true);
    expect(servers.status().external).toEqual([expect.objectContaining({ serverDir: dir, pid: child.pid })]);
    expect(servers.runningState(dir)).toBe("external");
    await expect(servers.start({ serverDir: dir, ...fakeLaunch("ok") })).rejects.toMatchObject({ code: "SERVER_ALREADY_RUNNING" });
    await expect(servers.stop(dir)).rejects.toMatchObject({ code: "NOT_MANAGED" });
    expect(await servers.stop(dir, { takeOver: true })).toMatchObject({ stopped: true, external: true });
    expect(pidAlive(child.pid!)).toBe(false);
  });

  it("restarts with the previous options and runs `between` while the server is down", async () => {
    const { servers } = await setup();
    const dir = makeServerDir();
    await servers.start({ serverDir: dir, ...fakeLaunch("ok") });
    let during = "";
    const s = await servers.restart({ serverDir: dir }, async () => { during = servers.status(dir).servers[0]!.state; });
    expect(during).toBe("stopped");
    expect(s.state).toBe("running");
    expect(s.launch?.args).toContain("--mode=ok");
  });

  it("resolves the server folder when only one is known", async () => {
    const { servers } = await setup();
    expect(() => servers.resolveDir()).toThrow(expect.objectContaining({ code: "INVALID_PARAMS" }));
    const dir = makeServerDir();
    await servers.start({ serverDir: dir, ...fakeLaunch("ok") });
    expect(servers.resolveDir()).toBe(resolve(dir));
  });

  it("stops managed servers on shutdown", async () => {
    const { servers } = await setup();
    const dir = makeServerDir();
    await servers.start({ serverDir: dir, ...fakeLaunch("ok") });
    await servers.shutdown();
    expect(servers.status(dir).servers[0]!.state).toBe("stopped");
  });

  it("names known crash causes", () => {
    expect(diagnoseCrash(["java.io.IOException: session.lock: already locked (possibly by other Minecraft instance?)"]).code).toBe("WORLD_LOCKED");
    expect(diagnoseCrash(["UnsupportedClassVersionError: class file version 69.0"]).code).toBe("JAVA_TOO_OLD");
    expect(diagnoseCrash(["something odd"]).code).toBe("SERVER_CRASHED");
  });
});
```

- [ ] **Step 7: Run it to verify it fails**

Run: `cd hub && npx vitest run test/server-manager.test.ts`
Expected: FAIL, "Failed to load url ../src/dev/server-manager.js".

- [ ] **Step 8: Implement the ServerManager**

`hub/src/dev/server-manager.ts`:

```ts
import { type ChildProcess, spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { join, resolve } from "node:path";
import { createInterface } from "node:readline";
import { setTimeout as sleep } from "node:timers/promises";
import type { AgentServer, InstanceInfo } from "../agents.js";
import { CraftwireError } from "../errors.js";
import { RingBuffer } from "../ringbuffer.js";
import { pluginJarsNamed } from "./jar.js";
import { EULA_HINT, eulaAccepted, type Launch, type LaunchParams, MIN_JAVA, probeJavaMajor, resolveLaunch } from "./launch.js";
import { pathKey, samePath } from "./paths.js";

export type ServerState = "starting" | "running" | "stopping" | "stopped" | "crashed";

export interface StartOptions extends LaunchParams {
  serverDir: string;
  timeoutMs?: number;
}

export type RestartOptions = Partial<StartOptions> & { takeOver?: boolean };

export interface ServerStatus {
  serverDir: string;
  state: ServerState;
  pid?: number;
  startedAt?: number;
  readyMs?: number;
  exitCode?: number | null;
  agent?: string | null;
  launch?: Launch;
  warning?: string;
  consoleTail: string[];
}

export interface ExternalServer {
  instance: string;
  name: string;
  serverDir: string;
  pid?: number;
}

export interface StopResult {
  serverDir: string;
  stopped: true;
  external: boolean;
  forced: boolean;
  exitCode?: number | null;
}

export interface ServerManagerOptions {
  agents: AgentServer;
  /** Passed to the server as CRAFTWIRE_HOME so its plugin finds this hub's hub.json. */
  home: string;
  javaMajor?: (java: string) => Promise<number>;
  agentWaitMs?: number;
  stopTimeoutMs?: number;
}

interface Managed {
  dir: string;
  options: StartOptions;
  state: ServerState;
  console: RingBuffer<string>;
  child?: ChildProcess;
  exited?: Promise<number | null>;
  startedAt?: number;
  readyMs?: number;
  exitCode?: number | null;
  agent?: string | null;
  onAgent?: () => void;
  launch?: Launch;
  warning?: string;
}

const DONE = /\bDone \([\d.,]+s\)!/;
const LIVE: readonly ServerState[] = ["starting", "running", "stopping"];
const isLive = (m: Managed | undefined): m is Managed => m !== undefined && LIVE.includes(m.state);

const CRASHES: [RegExp, string, string][] = [
  [/FAILED TO BIND TO PORT/i, "PORT_IN_USE", "Another process uses the server port: stop it, or change server-port in server.properties."],
  [/session\.lock|already locked/i, "WORLD_LOCKED", "Another server is using this world: stop it first (server_process {action:'status'} lists external servers)."],
  [/UnsupportedClassVersionError|requires running the server with Java/i, "JAVA_TOO_OLD", `Paper 26.x needs Java ${MIN_JAVA}+: pass java with the path to a newer Java.`],
  [/agree to the EULA/i, "EULA_NOT_ACCEPTED", EULA_HINT],
];

export function diagnoseCrash(lines: string[]): { code: string; hint: string } {
  const text = lines.join("\n");
  for (const [re, code, hint] of CRASHES) if (re.test(text)) return { code, hint };
  return { code: "SERVER_CRASHED", hint: "Read consoleTail (and logs/latest.log in the server folder) for the cause." };
}

export function definedOnly<T extends object>(o: T): Partial<T> {
  return Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined)) as Partial<T>;
}

export function pidAlive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch (e) {
    return (e as NodeJS.ErrnoException).code === "EPERM";
  }
}

export async function waitUntil(check: () => boolean, ms: number, everyMs = 250): Promise<boolean> {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (check()) return true;
    await sleep(everyMs);
  }
  return check();
}

export function notManagedError(ext: ExternalServer): CraftwireError {
  return new CraftwireError("NOT_MANAGED",
    `The server in ${ext.serverDir} was started outside Craftwire (${ext.instance}${ext.pid !== undefined ? `, pid ${ext.pid}` : ""})`,
    "Ask the user first; then pass takeOver:true to stop it (it runs under the hub after a restart).");
}

async function race<T extends string>(ps: Promise<T>[], ms: number, onTimeout: T): Promise<T> {
  let timer: NodeJS.Timeout | undefined;
  const timeout = new Promise<T>((r) => { timer = setTimeout(() => r(onTimeout), ms); });
  try {
    return await Promise.race([...ps, timeout]);
  } finally {
    clearTimeout(timer);
  }
}

/** Runs local Paper servers for the hub and recognises servers started elsewhere by their agent's serverDir. */
export class ServerManager {
  private readonly servers = new Map<string, Managed>();
  private queue: Promise<unknown> = Promise.resolve();

  constructor(private readonly opts: ServerManagerOptions) {
    opts.agents.on("connected", (i: InstanceInfo) => {
      if (i.kind !== "server" || i.serverDir === undefined) return;
      const m = this.servers.get(pathKey(i.serverDir));
      if (!isLive(m)) return;
      m.agent = i.id;
      m.onAgent?.();
    });
    opts.agents.on("disconnected", (i: InstanceInfo) => {
      for (const m of this.servers.values()) if (m.agent === i.id) m.agent = null;
    });
  }

  start(o: StartOptions): Promise<ServerStatus> {
    return this.exclusive(() => this.doStart(o));
  }

  stop(serverDir?: string, o: { takeOver?: boolean } = {}): Promise<StopResult> {
    return this.exclusive(() => this.doStop(this.resolveDir(serverDir), o.takeOver ?? false));
  }

  /** Stops the server if it runs, calls `between` while it is down, then starts it with the previous options. */
  restart(o: RestartOptions, between?: () => Promise<void>): Promise<ServerStatus> {
    return this.exclusive(async () => {
      const { takeOver, ...start } = o;
      const dir = this.resolveDir(start.serverDir);
      if (this.runningState(dir) !== "none") await this.doStop(dir, takeOver ?? false);
      await between?.();
      return this.doStart({ ...start, serverDir: dir });
    });
  }

  status(serverDir?: string, tail = 20): { servers: ServerStatus[]; external: ExternalServer[] } {
    const keep = (dir: string) => serverDir === undefined || samePath(dir, serverDir);
    return {
      servers: [...this.servers.values()].filter((m) => keep(m.dir)).map((m) => this.snapshot(m, tail)),
      external: this.externals().filter((e) => keep(e.serverDir)),
    };
  }

  runningState(serverDir: string): "managed" | "external" | "none" {
    if (isLive(this.servers.get(pathKey(serverDir)))) return "managed";
    return this.external(serverDir) ? "external" : "none";
  }

  external(serverDir: string): ExternalServer | undefined {
    return this.externals().find((e) => samePath(e.serverDir, serverDir));
  }

  externals(): ExternalServer[] {
    return this.opts.agents.instances()
      .filter((i): i is InstanceInfo & { serverDir: string } => i.kind === "server" && i.serverDir !== undefined)
      .filter((i) => !isLive(this.servers.get(pathKey(i.serverDir))))
      .map((i) => ({ instance: i.id, name: i.name, serverDir: i.serverDir, ...(i.pid !== undefined ? { pid: i.pid } : {}) }));
  }

  /** The explicit folder, else the only running (managed or external) server, else the only one ever started. */
  resolveDir(serverDir?: string): string {
    if (serverDir) return resolve(serverDir);
    const running = [
      ...[...this.servers.values()].filter(isLive).map((m) => m.dir),
      ...this.externals().map((e) => resolve(e.serverDir)),
    ];
    const pool = running.length ? running : [...this.servers.values()].map((m) => m.dir);
    if (pool.length === 1) return pool[0]!;
    if (pool.length === 0) throw new CraftwireError("INVALID_PARAMS", "No server is known yet", "Pass serverDir: the folder that contains the Paper jar.");
    throw new CraftwireError("AMBIGUOUS_SERVER", `${pool.length} servers are known`, `Pass serverDir: one of ${pool.join(", ")}.`);
  }

  /** Stops every server this hub started; called when the hub exits. */
  async shutdown(): Promise<void> {
    const live = [...this.servers.values()].filter(isLive);
    await Promise.all(live.map((m) => this.doStop(m.dir, false).catch(() => undefined)));
  }

  private exclusive<T>(fn: () => Promise<T>): Promise<T> {
    const run = this.queue.then(fn, fn);
    this.queue = run.catch(() => undefined);
    return run;
  }

  private async doStart(o: StartOptions): Promise<ServerStatus> {
    const dir = resolve(o.serverDir);
    const opts: StartOptions = { ...this.servers.get(pathKey(dir))?.options, ...definedOnly(o), serverDir: dir };
    if (!existsSync(dir)) throw new CraftwireError("SERVER_DIR_NOT_FOUND", `${dir} does not exist`, "Pass serverDir: the folder that contains the Paper jar.");
    const state = this.runningState(dir);
    if (state === "managed") throw new CraftwireError("SERVER_ALREADY_RUNNING", `The server in ${dir} is already running`, "Use server_process {action:'status'} or {action:'restart'}.");
    if (state === "external") {
      const ext = this.external(dir)!;
      throw new CraftwireError("SERVER_ALREADY_RUNNING", `The server in ${dir} is already running outside Craftwire (${ext.instance})`,
        "Keep using it as it is, or (after asking the user) server_process {action:'restart', takeOver:true} to run it under the hub.");
    }
    if (!eulaAccepted(dir)) throw new CraftwireError("EULA_NOT_ACCEPTED", `The Minecraft EULA is not accepted in ${join(dir, "eula.txt")}`, EULA_HINT);
    const launch = resolveLaunch(dir, opts);
    const major = await (this.opts.javaMajor ?? probeJavaMajor)(launch.command);
    if (major < MIN_JAVA) throw new CraftwireError("JAVA_TOO_OLD", `${launch.command} is Java ${major}; Paper 26.x needs Java ${MIN_JAVA}+`, `Pass java: the path to a Java ${MIN_JAVA}+ executable.`);
    const expectAgent = pluginJarsNamed(join(dir, "plugins"), "Craftwire").length > 0;

    const m: Managed = { dir, options: opts, state: "starting", console: new RingBuffer(2000), startedAt: Date.now(), launch, agent: null };
    this.servers.set(pathKey(dir), m);
    const agentSeen = new Promise<"agent">((r) => { m.onAgent = () => r("agent"); });
    let sawDone!: () => void;
    const done = new Promise<"done">((r) => { sawDone = () => r("done"); });

    const child = spawn(launch.command, launch.args, {
      cwd: dir,
      env: { ...process.env, CRAFTWIRE_HOME: this.opts.home },
      stdio: ["pipe", "pipe", "pipe"],
      windowsHide: true,
    });
    m.child = child;
    child.stdin.on("error", () => {});
    for (const stream of [child.stdout, child.stderr]) {
      createInterface({ input: stream, crlfDelay: Infinity }).on("line", (line) => {
        m.console.push(line);
        if (DONE.test(line)) sawDone();
      });
    }
    m.exited = new Promise((res) => {
      let settled = false;
      const settle = (code: number | null) => {
        if (settled) return;
        settled = true;
        m.exitCode = code;
        m.state = m.state === "stopping" || (m.state === "running" && code === 0) ? "stopped" : "crashed";
        res(code);
      };
      child.once("error", (e) => { m.console.push(`[craftwire] cannot run ${launch.command}: ${e.message}`); settle(null); });
      // "close" (not "exit") so every console line is read before the crash is diagnosed.
      child.once("close", (code) => settle(code));
    });
    const exit = m.exited.then(() => "exit" as const);

    const first = await race<"done" | "exit" | "timeout">([done, exit], opts.timeoutMs ?? 300_000, "timeout");
    if (first === "exit") throw this.crashError(m);
    if (first === "timeout") {
      throw new CraftwireError("TIMEOUT", `The server did not finish starting within ${opts.timeoutMs ?? 300_000} ms`,
        "It is still starting: watch server_process {action:'status'} (consoleTail), or stop it.", { consoleTail: m.console.toArray().slice(-40) });
    }
    if (expectAgent && !m.agent) {
      const r = await race<"agent" | "exit" | "timeout">([agentSeen, exit], this.opts.agentWaitMs ?? 20_000, "timeout");
      if (r === "exit") throw this.crashError(m);
      if (r === "timeout") m.warning = "The Craftwire plugin is in plugins/ but did not connect to this hub; check logs/latest.log for Craftwire errors.";
    }
    if (!expectAgent) m.warning = "The Craftwire plugin is not in plugins/, so server tools and logs are unavailable here. Install it with plugin_deploy {jar:'<craftwire-paper jar>'}.";
    m.state = "running";
    m.readyMs = Date.now() - m.startedAt!;
    return this.snapshot(m, 10);
  }

  private crashError(m: Managed): CraftwireError {
    const lines = m.console.toArray();
    const { code, hint } = diagnoseCrash(lines);
    return new CraftwireError(code, `The server exited during startup (exit code ${m.exitCode})`, hint, { exitCode: m.exitCode, consoleTail: lines.slice(-40) });
  }

  private async doStop(dir: string, takeOver: boolean): Promise<StopResult> {
    const m = this.servers.get(pathKey(dir));
    if (isLive(m) && m.child && m.exited) {
      m.state = "stopping";
      m.child.stdin?.write("stop\n");
      let forced = false;
      if ((await race<"exit" | "timeout">([m.exited.then(() => "exit" as const)], this.opts.stopTimeoutMs ?? 90_000, "timeout")) === "timeout") {
        forced = true;
        m.child.kill("SIGKILL");
        await m.exited;
      }
      return { serverDir: dir, stopped: true, external: false, forced, exitCode: m.exitCode ?? null };
    }
    const ext = this.external(dir);
    if (!ext) throw new CraftwireError("SERVER_NOT_RUNNING", `No server is running in ${dir}`, "Start it with server_process {action:'start'}.");
    if (!takeOver) throw notManagedError(ext);
    await this.stopExternal(ext);
    return { serverDir: dir, stopped: true, external: true, forced: false };
  }

  private async stopExternal(ext: ExternalServer): Promise<void> {
    await this.opts.agents.request(ext.instance, "server.command", { command: "stop" }, 10_000).catch(() => undefined);
    const ms = this.opts.stopTimeoutMs ?? 90_000;
    const still = "The server may still be saving; check it, then retry.";
    if (ext.pid !== undefined) {
      const pid = ext.pid;
      if (!(await waitUntil(() => !pidAlive(pid), ms))) throw new CraftwireError("TIMEOUT", `pid ${pid} is still running ${ms} ms after stop`, still);
      return;
    }
    if (!(await waitUntil(() => !this.opts.agents.instances().some((i) => i.id === ext.instance), ms))) {
      throw new CraftwireError("TIMEOUT", `${ext.instance} is still connected ${ms} ms after stop`, still);
    }
    await sleep(5000); // agents before 0.3.0 send no pid: give the JVM time to release the world and plugin jars
  }

  private snapshot(m: Managed, tail: number): ServerStatus {
    const s: ServerStatus = { serverDir: m.dir, state: m.state, consoleTail: tail > 0 ? m.console.toArray().slice(-tail) : [] };
    if (m.child?.pid !== undefined && isLive(m)) s.pid = m.child.pid;
    if (m.startedAt !== undefined) s.startedAt = m.startedAt;
    if (m.readyMs !== undefined) s.readyMs = m.readyMs;
    if (m.exitCode !== undefined) s.exitCode = m.exitCode;
    if (m.agent !== undefined) s.agent = m.agent;
    if (m.launch) s.launch = m.launch;
    if (m.warning) s.warning = m.warning;
    return s;
  }
}
```

- [ ] **Step 9: Run the tests**

Run: `cd hub && npx vitest run test/launch.test.ts test/server-manager.test.ts && npm run typecheck`
Expected: all PASS. Run the manager suite twice to catch timing flakes: `npx vitest run test/server-manager.test.ts` again → PASS.

- [ ] **Step 10: Commit**

```bash
git add hub/src/dev/paths.ts hub/src/dev/launch.ts hub/src/dev/server-manager.ts hub/test/helpers/fake-paper.mjs hub/test/helpers/servers.ts hub/test/launch.test.ts hub/test/server-manager.test.ts
git commit -m "feat(hub): ServerManager runs local Paper servers and recognises external ones"
```

---

### Task 4: `server_process` tool and hub wiring

**Files:**
- Create: `hub/src/tools/dev-tools.ts`
- Modify:
  - `hub/src/tools/registry.ts` (ToolContext)
  - `hub/src/server.ts`
  - `hub/src/cli.ts`
  - `hub/test/helpers/hub.ts`
  - `hub/test/cli.test.ts:28-34`
- Test: `hub/test/dev-tools.test.ts`

**Interfaces:**
- Consumes (Task 3): `ServerManager`, `definedOnly`.
- Produces:
  - `ToolContext.servers: ServerManager`.
  - `registerDevTools(server, ctx)` (Task 5 adds `plugin_deploy` to it).
  - `startHub(opts?: { writeHubJson?: boolean; javaMajor?: number; agentWaitMs?: number; stopTimeoutMs?: number })`, which returns `servers` as well.
  - Tool `server_process {action, serverDir?, jvmArgs?, jar?, java?, takeOver=false, timeoutMs=300000, tail=20}`.

- [ ] **Step 1: Write the failing tool tests**

`hub/test/dev-tools.test.ts`:

```ts
import { afterEach, describe, expect, it } from "vitest";
import { json, startHub } from "./helpers/hub.js";
import { fakeLaunch, makeServerDir } from "./helpers/servers.js";

let hub: Awaited<ReturnType<typeof startHub>> | undefined;
afterEach(async () => {
  await hub?.close();
  hub = undefined;
});

describe("server_process", () => {
  it("starts a server with its agent, reports status and stops it", async () => {
    hub = await startHub({ writeHubJson: true, javaMajor: 25, agentWaitMs: 2000 });
    const dir = makeServerDir({ craftwire: true });
    const started = json(await hub.call("server_process", { action: "start", serverDir: dir, ...fakeLaunch("agent") }));
    expect(started).toMatchObject({ state: "running", agent: "server-1" });
    const inst = json(await hub.call("list_instances")).instances[0];
    expect(inst).toMatchObject({ id: "server-1", pid: started.pid });
    const status = json(await hub.call("server_process", { action: "status", tail: 5 }));
    expect(status.servers[0]).toMatchObject({ state: "running", agent: "server-1" });
    expect(status.servers[0].consoleTail.length).toBeLessThanOrEqual(5);
    expect(json(await hub.call("server_process", { action: "stop" }))).toMatchObject({ stopped: true, forced: false });
  });

  it("asks for serverDir when no server is known", async () => {
    hub = await startHub({ javaMajor: 25 });
    const r = await hub.call("server_process", { action: "start" });
    expect(r.isError).toBe(true);
    expect(json(r).code).toBe("INVALID_PARAMS");
  });

  it("passes the EULA refusal through with its hint", async () => {
    hub = await startHub({ javaMajor: 25 });
    const r = await hub.call("server_process", { action: "start", serverDir: makeServerDir({ eula: false }), ...fakeLaunch("ok") });
    expect(json(r)).toMatchObject({ code: "EULA_NOT_ACCEPTED", hint: expect.stringContaining("never accepts") });
  });
});
```

Update `hub/test/cli.test.ts`. The first test becomes:

```ts
  it("exposes all M1, M2 and M3 tools", async () => {
    const names = (await client.listTools()).tools.map((t) => t.name).sort();
    expect(names).toEqual([
      "camera", "chat", "client_settings", "get_request_status", "gui_action", "gui_read",
      "hud_read", "input", "list_instances", "logs", "player_state", "plugin_manage", "screenshot",
      "server_command", "server_eval", "server_info", "server_process", "wait_for", "world_edit", "world_query",
    ]);
  });
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd hub && npx vitest run test/dev-tools.test.ts test/cli.test.ts`
Expected: FAIL:
- `startHub` ignores the options, so `server_process` is unknown and `isError` is set with a "Tool server_process not found" style message;
- the cli list lacks `server_process`.

- [ ] **Step 3: Implement**

`hub/src/tools/registry.ts`:
- Add `import type { ServerManager } from "../dev/server-manager.js";`.
- Add `servers: ServerManager;` to `ToolContext`.

`hub/src/tools/dev-tools.ts`:

```ts
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { definedOnly } from "../dev/server-manager.js";
import { defineTool, ok, type ToolContext } from "./registry.js";

export function registerDevTools(server: McpServer, ctx: ToolContext): void {
  defineTool(server, ctx, "server_process",
    "Start, stop, restart or inspect a local Paper server run by the hub. start waits for the console's `Done (` line and, when the Craftwire plugin is in plugins/, for the plugin to connect (result `agent`). JVM flags and the jar come from the folder's start.bat/start.sh unless jvmArgs/jar are given. The EULA is never accepted for you: on EULA_NOT_ACCEPTED ask the user. A server started outside Craftwire (status → external) is stopped or restarted only with takeOver:true — ask the user first. Servers started here stop when the hub exits.",
    {
      action: z.enum(["start", "stop", "restart", "status"]),
      serverDir: z.string().optional().describe("Server folder (contains the Paper jar). Optional when only one server is known."),
      jvmArgs: z.array(z.string()).optional().describe("JVM flags, replacing the start script's."),
      jar: z.string().optional().describe("Server jar, relative to serverDir."),
      java: z.string().optional().describe("Java executable. Paper 26.x needs Java 25+."),
      takeOver: z.boolean().default(false).describe("Allow stopping a server that was started outside Craftwire."),
      timeoutMs: z.number().int().min(1000).max(900_000).default(300_000).describe("How long start/restart waits for the server to be ready."),
      tail: z.number().int().min(0).max(500).default(20).describe("status: console lines to include per server."),
    },
    async (a, c) => {
      const launch = definedOnly({ java: a.java, jvmArgs: a.jvmArgs, jar: a.jar });
      switch (a.action) {
        case "status":
          return ok(c.servers.status(a.serverDir, a.tail));
        case "start":
          return ok(await c.servers.start({ ...launch, serverDir: c.servers.resolveDir(a.serverDir), timeoutMs: a.timeoutMs }));
        case "stop":
          return ok(await c.servers.stop(a.serverDir, { takeOver: a.takeOver }));
        case "restart":
          return ok(await c.servers.restart({ ...launch, serverDir: a.serverDir, timeoutMs: a.timeoutMs, takeOver: a.takeOver }));
      }
    });
}
```

`hub/src/server.ts`:
- Import `registerDevTools` and call it after `registerLogTools(server, ctx);`.
- Insert into INSTRUCTIONS before the "After an action…" line:

```ts
  "Dev loop: server_process starts/stops a local Paper server; plugin_deploy builds a plugin project (or takes a jar), installs it, restarts the server and reports whether it enabled.",
```

`hub/src/cli.ts`:
- Add `import { ServerManager } from "./dev/server-manager.js";`.
- After `agents.on("disconnected", …)`:

```ts
  const servers = new ServerManager({ agents, home });
  const server = createCraftwireServer({ agents, ops: new OperationTracker(), audit: new AuditLog(join(home, "logs")), servers });
```

  This replaces the old `createCraftwireServer` line.
- The shutdown body becomes:

```ts
    // Servers started by server_process get a graceful stop so their worlds are saved.
    void servers.shutdown().finally(() => agents.close()).finally(() => process.exit(0));
```

- Next to the stdin listeners, add `process.once("SIGINT", shutdown); process.once("SIGTERM", shutdown);`.

`hub/test/helpers/hub.ts`:
- Add the imports `writeHubConfig` from `../../src/config.js` and `ServerManager` from `../../src/dev/server-manager.js`.
- The function becomes:

```ts
export async function startHub(opts: { writeHubJson?: boolean; javaMajor?: number; agentWaitMs?: number; stopTimeoutMs?: number } = {}) {
  const home = mkdtempSync(join(tmpdir(), "cw-hub-"));
  const agents = new AgentServer({ token: TOKEN, port: 0, requestTimeoutMs: 2000 });
  const port = await agents.listen();
  if (opts.writeHubJson) writeHubConfig({ port, token: TOKEN }, home);
  const servers = new ServerManager({
    agents, home,
    ...(opts.javaMajor !== undefined ? { javaMajor: async () => opts.javaMajor! } : {}),
    agentWaitMs: opts.agentWaitMs ?? 2000,
    stopTimeoutMs: opts.stopTimeoutMs ?? 10_000,
  });
  const server = createCraftwireServer({ agents, ops: new OperationTracker(), audit: new AuditLog(join(home, "logs")), servers });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  await server.connect(serverTransport);
  const client = new Client({ name: "test", version: "0.0.0" });
  await client.connect(clientTransport);
  const call = (name: string, args: Record<string, unknown> = {}) =>
    client.callTool({ name, arguments: args }) as Promise<CallToolResult>;
  return {
    agents, servers, port, token: TOKEN, home, client, call,
    close: async () => { await servers.shutdown(); await client.close(); await server.close(); await agents.close(); },
  };
}
```

- [ ] **Step 4: Run the hub suite**

Run: `cd hub && npx vitest run && npm run typecheck`
Expected: all PASS, cli test included (20 tools).

- [ ] **Step 5: Commit**

```bash
git add hub/src/tools/dev-tools.ts hub/src/tools/registry.ts hub/src/server.ts hub/src/cli.ts hub/test/helpers/hub.ts hub/test/dev-tools.test.ts hub/test/cli.test.ts
git commit -m "feat(hub): server_process tool; hub stops its servers on exit"
```

---

### Task 5: `plugin_deploy`

**Files:**
- Create: `hub/src/dev/deploy.ts`
- Modify:
  - `hub/src/tools/log-tools.ts` (export `rank`)
  - `hub/src/tools/dev-tools.ts`
  - `hub/test/cli.test.ts`
- Test:
  - `hub/test/deploy.test.ts`
  - `hub/test/dev-tools.test.ts` (add a `plugin_deploy` describe)

**Interfaces:**
- Consumes:
  - from Task 2: `parseBuildErrors`, `pluginInfoOfJar`, `pluginJarsNamed`, `PluginJarInfo`;
  - from Task 3: `ServerManager.resolveDir/runningState/external/restart`, `notManagedError`, `samePath`;
  - from M2: `groupLogs`, `LogLine`.
- Produces:
  - `detectBuild(projectDir, platform?): { tool: "gradle" | "maven"; command: string } | undefined`.
  - `runBuild(projectDir, command, {timeoutMs, javaHome?}): Promise<BuildOutcome>`, where `BuildOutcome = { command; exitCode: number | null; ms; timedOut; output: string[] }`.
  - `globToRegExp(pattern)`, `expandGlob(baseDir, pattern, maxDepth?)`.
  - `DEFAULT_JAR_GLOBS`, `findBuiltJar(projectDir, jarGlob?): { path: string; plugin: PluginJarInfo }`.
  - `installPluginJar(serverDir, jar, plugin, {running}): InstallResult`, where `InstallResult = { installed: string; replaced: string[]; backupDir?: string; needsRestart: boolean }`.
  - `pluginProblems(lines: LogLine[], name, limit?)`.
  - `deploy(args: DeployArgs, servers, agents): Promise<Record<string, unknown>>`.
  - Tool `plugin_deploy`.
  - log-tools exports `rank`.

- [ ] **Step 1: Write the failing unit tests**

`hub/test/deploy.test.ts`:

```ts
import { existsSync, mkdirSync, mkdtempSync, readdirSync, utimesSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { detectBuild, expandGlob, findBuiltJar, installPluginJar, pluginProblems, runBuild } from "../src/dev/deploy.js";
import { pluginInfoOfJar } from "../src/dev/jar.js";
import { makeZip } from "./helpers/zip.js";

const tmp = () => mkdtempSync(join(tmpdir(), "cw-deploy-"));
const NODE = `"${process.execPath}"`;
const touch = (dir: string, ...files: string[]) => { for (const f of files) { mkdirSync(join(dir, f, ".."), { recursive: true }); writeFileSync(join(dir, f), ""); } return dir; };
const jar = (file: string, name: string, version = "1.0") => { mkdirSync(join(file, ".."), { recursive: true }); return makeZip(file, { "plugin.yml": `name: ${name}\nversion: ${version}\n` }); };
const age = (file: string, secondsAgo: number) => { const t = Date.now() / 1000 - secondsAgo; utimesSync(file, t, t); };

describe("detectBuild", () => {
  it("prefers the Gradle wrapper, skips tests and fits the platform", () => {
    const d = touch(tmp(), "gradlew", "gradlew.bat", "build.gradle.kts");
    expect(detectBuild(d, "win32")).toEqual({ tool: "gradle", command: "gradlew.bat build -x test --console=plain" });
    expect(detectBuild(d, "linux")).toEqual({ tool: "gradle", command: "sh ./gradlew build -x test --console=plain" });
  });

  it("falls back to gradle, then Maven, then nothing", () => {
    expect(detectBuild(touch(tmp(), "build.gradle"), "linux")?.command).toBe("gradle build -x test --console=plain");
    expect(detectBuild(touch(tmp(), "pom.xml", "mvnw.cmd"), "win32")?.command).toBe("mvnw.cmd -B package -DskipTests");
    expect(detectBuild(touch(tmp(), "pom.xml"), "linux")).toEqual({ tool: "maven", command: "mvn -B package -DskipTests" });
    expect(detectBuild(tmp(), "linux")).toBeUndefined();
  });
});

describe("runBuild", () => {
  it("collects output and the exit code, with JAVA_HOME set when asked", async () => {
    const r = await runBuild(tmp(), `${NODE} -e "console.log(process.env.JAVA_HOME); process.exit(3)"`, { timeoutMs: 20_000, javaHome: "/opt/jdk-25" });
    expect(r).toMatchObject({ exitCode: 3, timedOut: false });
    expect(r.output).toContain("/opt/jdk-25");
  });

  it("kills a build that runs too long", async () => {
    const r = await runBuild(tmp(), `${NODE} -e "setInterval(() => {}, 1000)"`, { timeoutMs: 500 });
    expect(r.timedOut).toBe(true);
  });
});

describe("jar discovery", () => {
  it("matches globs relative to the project", () => {
    const d = touch(tmp(), "build/libs/a.jar", "mod/build/libs/b.jar", "node_modules/x/build/libs/c.jar");
    expect(expandGlob(d, "**/build/libs/*.jar").map((f) => f.slice(d.length + 1).replace(/\\/g, "/"))).toEqual(["build/libs/a.jar", "mod/build/libs/b.jar"]);
  });

  it("picks the newest plugin jar and skips sources/javadoc jars and libraries", () => {
    const d = tmp();
    age(jar(join(d, "build/libs/demo-1.0.jar"), "Demo", "1.0"), 60);
    jar(join(d, "build/libs/demo-1.1.jar"), "Demo", "1.1");
    jar(join(d, "build/libs/demo-1.1-sources.jar"), "Demo", "1.1");
    makeZip(join(d, "build/libs/lib.jar"), { "a/B.class": "x" });
    expect(findBuiltJar(d)).toMatchObject({ path: join(d, "build/libs/demo-1.1.jar"), plugin: { name: "Demo", version: "1.1" } });
  });

  it("asks for jarGlob when jars of different plugins are found", () => {
    const d = tmp();
    jar(join(d, "a/build/libs/a.jar"), "Alpha");
    jar(join(d, "b/build/libs/b.jar"), "Beta");
    expect(() => findBuiltJar(d)).toThrow(expect.objectContaining({ code: "AMBIGUOUS_JAR" }));
    expect(findBuiltJar(d, "b/build/libs/*.jar").plugin.name).toBe("Beta");
    expect(() => findBuiltJar(tmp())).toThrow(expect.objectContaining({ code: "JAR_NOT_FOUND" }));
  });
});

describe("installPluginJar", () => {
  it("replaces the old jar of the same plugin and keeps a backup", () => {
    const server = tmp();
    const old = jar(join(server, "plugins/demo-1.0.jar"), "Demo", "1.0");
    jar(join(server, "plugins/other.jar"), "Other");
    const fresh = jar(join(tmp(), "demo-1.1.jar"), "Demo", "1.1");
    const r = installPluginJar(server, fresh, pluginInfoOfJar(fresh)!, { running: false });
    expect(r).toEqual({ installed: join(server, "plugins", "demo-1.1.jar"), replaced: [old], backupDir: join(server, "plugins", ".craftwire-backup"), needsRestart: false });
    expect(readdirSync(join(server, "plugins")).sort()).toEqual([".craftwire-backup", "demo-1.1.jar", "other.jar"]);
    expect(existsSync(join(server, "plugins", ".craftwire-backup", "demo-1.0.jar"))).toBe(true);
  });

  it("stages the jar in plugins/update under the old name while the server runs (jars are locked on Windows)", () => {
    const server = tmp();
    const old = jar(join(server, "plugins/demo-1.0.jar"), "Demo", "1.0");
    const fresh = jar(join(tmp(), "demo-1.1.jar"), "Demo", "1.1");
    const r = installPluginJar(server, fresh, pluginInfoOfJar(fresh)!, { running: true });
    expect(r).toEqual({ installed: join(server, "plugins", "update", "demo-1.0.jar"), replaced: [], needsRestart: true });
    expect(existsSync(old)).toBe(true);
  });
});

describe("pluginProblems", () => {
  it("keeps WARN+ lines from or about the plugin", () => {
    const line = (level: string, logger: string, message: string) => ({ time: 1, level, logger, message });
    const lines = [line("WARN", "Demo", "config missing"), line("INFO", "Demo", "enabled"), line("ERROR", "Server", "Error occurred while enabling Demo v1.1"), line("WARN", "Other", "noise")];
    expect(pluginProblems(lines, "demo").map((l) => l.message)).toEqual(["config missing", "Error occurred while enabling Demo v1.1"]);
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd hub && npx vitest run test/deploy.test.ts`
Expected: FAIL, "Failed to load url ../src/dev/deploy.js".

- [ ] **Step 3: Implement deploy.ts**

In `hub/src/tools/log-tools.ts`, change `const rank =` to `export const rank =`.

`hub/src/dev/deploy.ts`:

```ts
import { spawn } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync, readdirSync, renameSync, statSync } from "node:fs";
import { basename, delimiter, dirname, join, resolve } from "node:path";
import { createInterface } from "node:readline";
import type { AgentServer } from "../agents.js";
import { CraftwireError } from "../errors.js";
import { RingBuffer } from "../ringbuffer.js";
import { groupLogs, type LogLine, rank } from "../tools/log-tools.js";
import { parseBuildErrors } from "./build-errors.js";
import { type PluginJarInfo, pluginInfoOfJar, pluginJarsNamed } from "./jar.js";
import { samePath } from "./paths.js";
import { notManagedError, type ServerManager } from "./server-manager.js";

export interface DetectedBuild {
  tool: "gradle" | "maven";
  command: string;
}

/** Gradle (wrapper first) or Maven, packaging without running tests. */
export function detectBuild(projectDir: string, platform: NodeJS.Platform = process.platform): DetectedBuild | undefined {
  const has = (f: string) => existsSync(join(projectDir, f));
  const win = platform === "win32";
  const gradlew = win ? (has("gradlew.bat") ? "gradlew.bat" : undefined) : has("gradlew") ? "sh ./gradlew" : undefined;
  if (gradlew) return { tool: "gradle", command: `${gradlew} build -x test --console=plain` };
  if (has("build.gradle") || has("build.gradle.kts")) return { tool: "gradle", command: "gradle build -x test --console=plain" };
  const mvnw = win ? (has("mvnw.cmd") ? "mvnw.cmd" : undefined) : has("mvnw") ? "sh ./mvnw" : undefined;
  if (mvnw) return { tool: "maven", command: `${mvnw} -B package -DskipTests` };
  if (has("pom.xml")) return { tool: "maven", command: "mvn -B package -DskipTests" };
  return undefined;
}

export interface BuildOutcome {
  command: string;
  exitCode: number | null;
  ms: number;
  timedOut: boolean;
  output: string[];
}

/** Runs `command` in a shell in `projectDir`; keeps the last 5000 output lines. */
export function runBuild(projectDir: string, command: string, o: { timeoutMs: number; javaHome?: string }): Promise<BuildOutcome> {
  const env = { ...process.env };
  if (o.javaHome) {
    env.JAVA_HOME = o.javaHome;
    const pathVar = Object.keys(env).find((k) => k.toUpperCase() === "PATH") ?? "PATH";
    env[pathVar] = join(o.javaHome, "bin") + delimiter + (env[pathVar] ?? "");
  }
  const started = Date.now();
  const output = new RingBuffer<string>(5000);
  return new Promise((resolvePromise) => {
    // POSIX: own process group so a timeout can kill the whole tree.
    const child = spawn(command, { cwd: projectDir, env, shell: true, windowsHide: true, detached: process.platform !== "win32" });
    for (const stream of [child.stdout, child.stderr]) {
      createInterface({ input: stream, crlfDelay: Infinity }).on("line", (l) => output.push(l));
    }
    let timedOut = false;
    const timer = setTimeout(() => { timedOut = true; killTree(child.pid); }, o.timeoutMs);
    child.once("error", (e) => output.push(`[craftwire] ${e.message}`));
    child.once("close", (code) => {
      clearTimeout(timer);
      resolvePromise({ command, exitCode: code, ms: Date.now() - started, timedOut, output: output.toArray() });
    });
  });
}

export function killTree(pid: number | undefined): void {
  if (pid === undefined) return;
  if (process.platform === "win32") {
    spawn("taskkill", ["/pid", String(pid), "/T", "/F"], { windowsHide: true, stdio: "ignore" });
    return;
  }
  try {
    process.kill(-pid, "SIGKILL");
  } catch {
    // already gone
  }
}

const SKIP_DIRS = new Set(["node_modules", ".git", ".gradle", ".idea"]);

/** `*` and `?` stay inside one path segment; `**` spans segments. Matched against '/'-separated relative paths. */
export function globToRegExp(pattern: string): RegExp {
  const p = pattern.replace(/\\/g, "/");
  let re = "";
  for (let i = 0; i < p.length; i++) {
    const ch = p[i]!;
    if (ch === "*" && p[i + 1] === "*") {
      const slash = p[i + 2] === "/";
      re += slash ? "(?:.*/)?" : ".*";
      i += slash ? 2 : 1;
    } else if (ch === "*") re += "[^/]*";
    else if (ch === "?") re += "[^/]";
    else re += ch.replace(/[.+^${}()|[\]\\]/g, "\\$&");
  }
  return new RegExp(`^${re}$`, process.platform === "win32" ? "i" : "");
}

export function expandGlob(baseDir: string, pattern: string, maxDepth = 6): string[] {
  const re = globToRegExp(pattern);
  const out: string[] = [];
  const walk = (dir: string, rel: string, depth: number) => {
    let entries;
    try {
      entries = readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entries) {
      const r = rel ? `${rel}/${e.name}` : e.name;
      if (e.isDirectory()) {
        if (depth < maxDepth && !SKIP_DIRS.has(e.name)) walk(join(dir, e.name), r, depth + 1);
      } else if (re.test(r)) out.push(join(dir, e.name));
    }
  };
  walk(baseDir, "", 0);
  return out.sort();
}

export const DEFAULT_JAR_GLOBS = ["build/libs/*.jar", "*/build/libs/*.jar", "target/*.jar", "*/target/*.jar"];
const NOT_PLUGIN_JAR = /(?:-sources|-javadoc|-plain)\.jar$|^original-/i;

/** The built plugin jar: newest among jars carrying a plugin descriptor; several plugins need jarGlob. */
export function findBuiltJar(projectDir: string, jarGlob?: string): { path: string; plugin: PluginJarInfo } {
  const globs = jarGlob ? [jarGlob] : DEFAULT_JAR_GLOBS;
  const files = [...new Set(globs.flatMap((g) => expandGlob(projectDir, g)))].filter((f) => !NOT_PLUGIN_JAR.test(basename(f)));
  const plugins = files.flatMap((path) => {
    const plugin = pluginInfoOfJar(path);
    if (!plugin) return [];
    const st = statSync(path);
    return [{ path, plugin, mtime: st.mtimeMs, size: st.size }];
  });
  if (plugins.length === 0) {
    throw new CraftwireError("JAR_NOT_FOUND", `No plugin jar found in ${projectDir}`, "Pass jarGlob (relative to projectDir), e.g. 'build/libs/*-all.jar'.", { looked: globs, jars: files });
  }
  const names = [...new Set(plugins.map((p) => p.plugin.name))];
  if (names.length > 1) {
    throw new CraftwireError("AMBIGUOUS_JAR", `Jars of ${names.length} plugins were found: ${names.join(", ")}`, "Pass jarGlob to pick one, e.g. 'my-plugin/build/libs/*.jar'.",
      { jars: plugins.map((p) => ({ path: p.path, plugin: p.plugin.name })) });
  }
  plugins.sort((a, b) => b.mtime - a.mtime || b.size - a.size);
  return { path: plugins[0]!.path, plugin: plugins[0]!.plugin };
}

export interface InstallResult {
  installed: string;
  replaced: string[];
  backupDir?: string;
  needsRestart: boolean;
}

/**
 * Puts `jar` into serverDir/plugins, moving older jars of the same plugin to plugins/.craftwire-backup/.
 * While the server runs its jars are open (locked on Windows), so the jar is staged in plugins/update/
 * under the old jar's name, which Paper swaps in on the next start.
 */
export function installPluginJar(serverDir: string, jar: string, plugin: PluginJarInfo, o: { running: boolean }): InstallResult {
  const pluginsDir = join(serverDir, "plugins");
  mkdirSync(pluginsDir, { recursive: true });
  const existing = pluginJarsNamed(pluginsDir, plugin.name).filter((p) => !samePath(p, jar));
  if (o.running) {
    const target = existing.length ? join(pluginsDir, "update", basename(existing[0]!)) : join(pluginsDir, basename(jar));
    mkdirSync(dirname(target), { recursive: true });
    copyFileSync(jar, target);
    return { installed: target, replaced: [], needsRestart: true };
  }
  const installed = join(pluginsDir, basename(jar));
  if (existing.length === 0) {
    if (!samePath(installed, jar)) copyFileSync(jar, installed);
    return { installed, replaced: [], needsRestart: false };
  }
  const backupDir = join(pluginsDir, ".craftwire-backup");
  mkdirSync(backupDir, { recursive: true });
  for (const old of existing) renameSync(old, join(backupDir, basename(old)));
  copyFileSync(jar, installed);
  return { installed, replaced: existing, backupDir, needsRestart: false };
}

/** WARN and worse lines logged by the plugin or naming it. */
export function pluginProblems(lines: LogLine[], name: string, limit = 20): LogLine[] {
  const n = name.toLowerCase();
  return lines
    .filter((l) => rank(l.level) >= rank("WARN"))
    .filter((l) => l.logger.toLowerCase() === n || `${l.message}\n${l.thrown ?? ""}`.toLowerCase().includes(n))
    .slice(-limit);
}

export interface DeployArgs {
  projectDir?: string;
  jar?: string;
  buildCommand?: string;
  jarGlob?: string;
  javaHome?: string;
  serverDir?: string;
  restart: boolean;
  takeOver: boolean;
  buildTimeoutMs: number;
  timeoutMs: number;
}

export async function deploy(a: DeployArgs, servers: ServerManager, agents: AgentServer): Promise<Record<string, unknown>> {
  if ((a.projectDir === undefined) === (a.jar === undefined)) {
    throw new CraftwireError("INVALID_PARAMS", "Pass exactly one of projectDir or jar", "projectDir builds the project first; jar installs a ready jar.");
  }
  const serverDir = servers.resolveDir(a.serverDir);
  const before = servers.runningState(serverDir);
  // Refuse before building: a long build that ends in NOT_MANAGED wastes minutes.
  if (before === "external" && a.restart && !a.takeOver) throw notManagedError(servers.external(serverDir)!);

  const result: Record<string, unknown> = { serverDir };
  let jarPath: string;
  let plugin: PluginJarInfo;
  if (a.projectDir !== undefined) {
    const projectDir = resolve(a.projectDir);
    if (!existsSync(projectDir)) throw new CraftwireError("PROJECT_NOT_FOUND", `${projectDir} does not exist`, "Pass projectDir: the plugin project's root folder.");
    const command = a.buildCommand ?? detectBuild(projectDir)?.command;
    if (!command) throw new CraftwireError("BUILD_NOT_DETECTED", `No Gradle or Maven build in ${projectDir}`, "Pass buildCommand, e.g. 'gradlew.bat shadowJar'.");
    const build = await runBuild(projectDir, command, { timeoutMs: a.buildTimeoutMs, ...(a.javaHome ? { javaHome: a.javaHome } : {}) });
    if (build.timedOut) {
      throw new CraftwireError("BUILD_FAILED", `${command} did not finish within ${a.buildTimeoutMs} ms`, "Raise buildTimeoutMs, or run the build once by hand to warm the caches.", { command, outputTail: build.output.slice(-60) });
    }
    if (build.exitCode !== 0) {
      const errors = parseBuildErrors(build.output);
      throw new CraftwireError("BUILD_FAILED",
        errors.length ? `Build failed with ${errors.length} compiler error(s)` : `Build failed (exit code ${build.exitCode})`,
        errors.length ? "Fix each file:line in details.errors and deploy again." : "No compiler errors were recognised; read details.outputTail.",
        { command, exitCode: build.exitCode, errors, outputTail: build.output.slice(-60) });
    }
    const built = findBuiltJar(projectDir, a.jarGlob);
    jarPath = built.path;
    plugin = built.plugin;
    result.build = { command, ms: build.ms };
  } else {
    jarPath = resolve(a.jar!);
    if (!existsSync(jarPath)) throw new CraftwireError("JAR_NOT_FOUND", `${jarPath} does not exist`, "Pass the path of a built plugin jar.");
    const info = pluginInfoOfJar(jarPath);
    if (!info) throw new CraftwireError("NOT_A_PLUGIN", `${jarPath} has no plugin.yml or paper-plugin.yml`, "Pick the plugin jar (for shaded builds usually the -all jar).");
    plugin = info;
  }
  result.jar = jarPath;
  result.plugin = plugin.version === undefined ? { name: plugin.name } : { name: plugin.name, version: plugin.version };

  if (!a.restart) {
    const install = installPluginJar(serverDir, jarPath, plugin, { running: before !== "none" });
    return { ...result, install, restarted: false, ...(install.needsRestart ? { hint: "Restart the server (server_process {action:'restart'}) to load the new jar." } : {}) };
  }

  let install: InstallResult | undefined;
  const status = await servers.restart({ serverDir, timeoutMs: a.timeoutMs, takeOver: a.takeOver }, async () => {
    install = installPluginJar(serverDir, jarPath, plugin, { running: false });
  });
  result.install = install;
  result.restarted = true;
  result.server = { state: status.state, agent: status.agent ?? null, readyMs: status.readyMs };
  if (status.warning) result.warning = status.warning;
  if (status.agent) {
    const loaded = await agents.request(status.agent, "plugin.manage", { action: "info", name: plugin.name })
      .catch((e: unknown) => ({ error: e instanceof CraftwireError ? e.toJSON() : String(e) }));
    result.loaded = loaded;
    result.problems = pluginProblems(groupLogs(agents.events(status.agent)), plugin.name);
    if ((loaded as { enabled?: boolean }).enabled !== true) result.hint = "The jar is installed but the plugin is not enabled: read problems, or logs {level:'WARN'}.";
  }
  return result;
}
```

- [ ] **Step 4: Run the unit tests**

Run: `cd hub && npx vitest run test/deploy.test.ts && npm run typecheck`
Expected: PASS.

- [ ] **Step 5: Write the failing tool tests**

Append to `hub/test/dev-tools.test.ts`. Extend the imports:

```ts
import { spawn } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { waitUntil } from "../src/dev/server-manager.js";
import { FAKE_PAPER } from "./helpers/servers.js";
import { makeZip } from "./helpers/zip.js";
```

Then add:

```ts
describe("plugin_deploy", () => {
  const NODE = `"${process.execPath}"`;
  const pluginJar = (dir: string, file: string, name: string) => {
    mkdirSync(dir, { recursive: true });
    return makeZip(join(dir, file), { "plugin.yml": `name: ${name}\nversion: 1.0\n` });
  };

  it("installs a ready jar, restarts the server and reports the plugin enabled", async () => {
    hub = await startHub({ writeHubJson: true, javaMajor: 25, agentWaitMs: 2000 });
    const dir = makeServerDir({ craftwire: true });
    await hub.call("server_process", { action: "start", serverDir: dir, ...fakeLaunch("agent") });
    const first = json(await hub.call("plugin_deploy", { jar: pluginJar(mkdtempSync(join(tmpdir(), "cw-j-")), "demo-1.0.jar", "Demo") }));
    expect(first).toMatchObject({ plugin: { name: "Demo" }, restarted: true, server: { state: "running", agent: "server-2" }, loaded: { enabled: true } });
    const second = json(await hub.call("plugin_deploy", { jar: pluginJar(mkdtempSync(join(tmpdir(), "cw-j-")), "demo-1.1.jar", "Demo") }));
    expect(second.install.replaced).toEqual([join(dir, "plugins", "demo-1.0.jar")]);
    expect(existsSync(join(dir, "plugins", ".craftwire-backup", "demo-1.0.jar"))).toBe(true);
  });

  it("builds a project, then finds and installs its jar", async () => {
    hub = await startHub({ writeHubJson: true, javaMajor: 25, agentWaitMs: 2000 });
    const dir = makeServerDir({ craftwire: true });
    await hub.call("server_process", { action: "start", serverDir: dir, ...fakeLaunch("agent") });
    const project = mkdtempSync(join(tmpdir(), "cw-proj-"));
    pluginJar(join(project, "build", "libs"), "built-1.0.jar", "Built");
    const r = json(await hub.call("plugin_deploy", { projectDir: project, buildCommand: `${NODE} -e "process.exit(0)"` }));
    expect(r).toMatchObject({ plugin: { name: "Built" }, build: { command: expect.stringContaining("process.exit(0)") }, loaded: { enabled: true } });
  });

  it("returns parsed compiler errors when the build fails", async () => {
    hub = await startHub({ javaMajor: 25 });
    const r = await hub.call("plugin_deploy", {
      projectDir: mkdtempSync(join(tmpdir(), "cw-proj-")), serverDir: makeServerDir(),
      buildCommand: `${NODE} -e "console.log('/p/src/Foo.java:7: error: boom'); process.exit(1)"`,
    });
    expect(json(r)).toMatchObject({ code: "BUILD_FAILED", details: { exitCode: 1, errors: [{ file: "/p/src/Foo.java", line: 7, message: "boom" }] } });
  });

  it("refuses to restart a server it did not start before running the build", async () => {
    hub = await startHub({ writeHubJson: true, javaMajor: 25 });
    const dir = makeServerDir();
    const child = spawn(process.execPath, [FAKE_PAPER, "--mode=agent"], { cwd: dir, env: { ...process.env, CRAFTWIRE_HOME: hub.home }, stdio: "pipe" });
    try {
      await waitUntil(() => hub!.agents.instances().length === 1, 5000);
      const project = mkdtempSync(join(tmpdir(), "cw-proj-"));
      const r = await hub.call("plugin_deploy", { projectDir: project, buildCommand: `${NODE} -e "require('fs').writeFileSync('built.txt', '')"` });
      expect(json(r).code).toBe("NOT_MANAGED");
      expect(existsSync(join(project, "built.txt"))).toBe(false);
    } finally {
      child.kill();
    }
  });
});
```

In `hub/test/cli.test.ts`, add `"plugin_deploy"` to the expected list (alphabetically after `"player_state"`; 21 tools).

- [ ] **Step 6: Run them to verify they fail**

Run: `cd hub && npx vitest run test/dev-tools.test.ts test/cli.test.ts`
Expected: the new `plugin_deploy` cases FAIL (tool not found) and the cli list mismatches.

- [ ] **Step 7: Register the tool**

In `hub/src/tools/dev-tools.ts`, add `import { deploy } from "../dev/deploy.js";`. Then, inside `registerDevTools` after `server_process`:

```ts
  defineTool(server, ctx, "plugin_deploy",
    "Build a Paper plugin project (Gradle wrapper `build -x test` or Maven `package -DskipTests`, auto-detected) or take a ready jar, install it into the server's plugins/ (older jars of the same plugin move to plugins/.craftwire-backup/), restart the server (starting it if it is down) and report `loaded` (enabled, version) plus `problems` (its WARN/ERROR log lines). A failed build returns BUILD_FAILED with details.errors as file:line. restart:false on a running server stages the jar in plugins/update/ for the next start.",
    {
      projectDir: z.string().optional().describe("Plugin project root to build."),
      jar: z.string().optional().describe("A ready plugin jar to install instead of building."),
      buildCommand: z.string().optional().describe("Shell command run in projectDir instead of the detected build, e.g. 'gradlew.bat shadowJar'."),
      jarGlob: z.string().optional().describe("Which built jar, relative to projectDir, e.g. 'build/libs/*-all.jar'. Needed when several plugins are built."),
      javaHome: z.string().optional().describe("JAVA_HOME for the build."),
      serverDir: z.string().optional().describe("Server folder. Optional when only one server is known."),
      restart: z.boolean().default(true),
      takeOver: z.boolean().default(false).describe("Allow stopping a server that was started outside Craftwire (ask the user first)."),
      buildTimeoutMs: z.number().int().min(10_000).max(1_800_000).default(600_000),
      timeoutMs: z.number().int().min(1000).max(900_000).default(300_000).describe("How long the restart waits for the server."),
    },
    async (a, c) => ok(await deploy(a, c.servers, c.agents)));
```

- [ ] **Step 8: Run the hub suite**

Run: `cd hub && npx vitest run && npm run typecheck`
Expected: all PASS.

- [ ] **Step 9: Commit**

```bash
git add hub/src/dev/deploy.ts hub/src/tools/log-tools.ts hub/src/tools/dev-tools.ts hub/test/deploy.test.ts hub/test/dev-tools.test.ts hub/test/cli.test.ts
git commit -m "feat(hub): plugin_deploy builds or takes a jar, swaps it in and verifies it enabled"
```

---

### Task 6: `craftwire doctor`

**Files:**
- Create: `hub/src/doctor.ts`
- Modify: `hub/src/cli.ts`, `hub/test/cli.test.ts`
- Test: `hub/test/doctor.test.ts`

**Interfaces:**
- Consumes:
  - from Task 1: the status request and `HubStatus`;
  - from Task 2: `pluginJarsNamed`, `pluginInfoOfJar`;
  - from Task 3: `resolveLaunch`, `eulaAccepted`, `probeJavaMajor`, `MIN_JAVA`.
- Produces:
  - `interface Check { status: "ok" | "warn" | "fail"; label: string; fix?: string }`.
  - `hubStatus(port, token, timeoutMs?): Promise<HubStatus>`.
  - `runDoctor(o: { home: string; serverDir?: string; nodeVersion?: string; javaMajor?: (java: string) => Promise<number> }): Promise<Check[]>`.
  - `formatChecks(checks): string`.
  - CLI: `craftwire doctor [--server <dir>]`, which exits 1 if any check fails; `craftwire --version`.

- [ ] **Step 1: Write the failing tests**

`hub/test/doctor.test.ts`:

```ts
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { AgentServer } from "../src/agents.js";
import { writeHubConfig } from "../src/config.js";
import { type Check, formatChecks, runDoctor } from "../src/doctor.js";
import { HUB_VERSION } from "../src/version.js";
import { connectFakeAgent } from "./helpers/fakeAgent.js";
import { makeZip } from "./helpers/zip.js";

const TOKEN = "e".repeat(64);
const java25 = async () => 25;
let agents: AgentServer | undefined;
afterEach(async () => { await agents?.close(); agents = undefined; });
const home = () => mkdtempSync(join(tmpdir(), "cw-doc-"));
const find = (checks: Check[], text: string) => checks.find((c) => c.label.includes(text));

describe("craftwire doctor", () => {
  it("reports the running hub, its agents, version drift and refused agents", async () => {
    const h = home();
    agents = new AgentServer({ token: TOKEN, port: 0 });
    const port = await agents.listen();
    writeHubConfig({ port, token: TOKEN }, h);
    await connectFakeAgent(port, { token: TOKEN, kind: "server", name: "srv", agentVersion: HUB_VERSION });
    await connectFakeAgent(port, { token: TOKEN, name: "Steve", agentVersion: "0.1.0" });
    await connectFakeAgent(port, { token: TOKEN, kind: "server", name: "old", agentVersion: "0.0.1", protocolVersion: 0 }).catch(() => undefined);
    const checks = await runDoctor({ home: h, javaMajor: java25 });
    expect(find(checks, `hub ${HUB_VERSION} is running`)?.status).toBe("ok");
    expect(find(checks, "server-1 (srv")?.status).toBe("ok");
    expect(find(checks, "client-1 (Steve")).toMatchObject({ status: "warn", fix: expect.stringContaining(`Agent mod to ${HUB_VERSION}`) });
    expect(find(checks, "was refused: PROTOCOL_MISMATCH")?.status).toBe("fail");
    expect(find(checks, "Java 25")?.status).toBe("ok");
  });

  it("warns when hub.json is missing or nothing answers", async () => {
    expect(find(await runDoctor({ home: home(), javaMajor: java25 }), "hub.json is missing")?.status).toBe("warn");
    const h = home();
    const port = await new Promise<number>((res) => { const s = createServer().listen(0, "127.0.0.1", () => { const p = (s.address() as { port: number }).port; s.close(() => res(p)); }); });
    writeHubConfig({ port, token: TOKEN }, h);
    expect(find(await runDoctor({ home: h, javaMajor: java25 }), "no hub answers")?.status).toBe("warn");
  });

  it("fails on an old Node and warns on an old Java", async () => {
    const checks = await runDoctor({ home: home(), nodeVersion: "18.19.0", javaMajor: async () => 21 });
    expect(find(checks, "Node 18.19.0")?.status).toBe("fail");
    expect(find(checks, "Java 21")?.status).toBe("warn");
  });

  it("checks a server folder without changing it", async () => {
    const dir = mkdtempSync(join(tmpdir(), "cw-doc-srv-"));
    mkdirSync(join(dir, "plugins"));
    writeFileSync(join(dir, "server.jar"), "");
    writeFileSync(join(dir, "eula.txt"), "eula=false\n");
    makeZip(join(dir, "plugins", "craftwire-paper-0.1.0.jar"), { "plugin.yml": "name: Craftwire\nversion: 0.1.0\n" });
    const checks = await runDoctor({ home: home(), serverDir: dir, javaMajor: java25 });
    expect(find(checks, "server jar")?.status).toBe("ok");
    expect(find(checks, "EULA not accepted")).toMatchObject({ status: "warn", fix: expect.stringContaining("aka.ms/MinecraftEULA") });
    expect(find(checks, "Craftwire plugin 0.1.0")?.status).toBe("warn");
  });

  it("formats checks with fixes under them", () => {
    expect(formatChecks([{ status: "ok", label: "a" }, { status: "warn", label: "b", fix: "do c" }])).toBe("[ok] a\n[warn] b\n       -> do c\n");
  });
});
```

In `hub/test/cli.test.ts`:
- Add `import { execFileSync } from "node:child_process";` to the existing child_process import (`execFileSync, execSync, spawn`).
- Add `import { HUB_VERSION } from "../src/version.js";`.
- Add:

```ts
  it("doctor reports the running hub", () => {
    const out = execFileSync(process.execPath, [join(hubDir, "dist", "cli.js"), "doctor"], { env: { ...process.env, CRAFTWIRE_HOME: home }, encoding: "utf8" });
    expect(out).toContain(`[ok] hub ${HUB_VERSION} is running`);
  });
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd hub && npx vitest run test/doctor.test.ts test/cli.test.ts`
Expected: FAIL. `../src/doctor.js` is missing. The cli treats `doctor` as a normal MCP start: stdin closes at once, the hub exits, and the output lacks `[ok] hub`.

- [ ] **Step 3: Implement**

`hub/src/doctor.ts`:

```ts
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import WebSocket from "ws";
import type { HubStatus } from "./agents.js";
import { pluginInfoOfJar, pluginJarsNamed } from "./dev/jar.js";
import { eulaAccepted, MIN_JAVA, probeJavaMajor, resolveLaunch } from "./dev/launch.js";
import { HUB_VERSION } from "./version.js";

export interface Check {
  status: "ok" | "warn" | "fail";
  label: string;
  fix?: string;
}

/** Asks a running hub for its status over the agent WebSocket (token from hub.json). */
export function hubStatus(port: number, token: string, timeoutMs = 3000): Promise<HubStatus> {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(`ws://127.0.0.1:${port}/`);
    const timer = setTimeout(() => { socket.terminate(); reject(new Error("no answer")); }, timeoutMs);
    socket.once("error", (e) => { clearTimeout(timer); reject(e); });
    socket.once("open", () => socket.send(JSON.stringify({ jsonrpc: "2.0", id: 0, method: "status", params: { token } })));
    socket.once("message", (raw) => {
      clearTimeout(timer);
      const msg = JSON.parse(String(raw)) as { result?: HubStatus; error?: { message?: string } };
      socket.close();
      if (msg.result) resolve(msg.result);
      else reject(new Error(msg.error?.message ?? "unexpected answer"));
    });
  });
}

export interface DoctorOptions {
  home: string;
  serverDir?: string;
  nodeVersion?: string;
  javaMajor?: (java: string) => Promise<number>;
}

export async function runDoctor(o: DoctorOptions): Promise<Check[]> {
  const checks: Check[] = [];
  const node = o.nodeVersion ?? process.versions.node;
  checks.push(Number(node.split(".")[0]) >= 20
    ? { status: "ok", label: `Node ${node}` }
    : { status: "fail", label: `Node ${node} is too old`, fix: "Install Node 20 or newer." });

  const file = join(o.home, "hub.json");
  let cfg: { port?: unknown; token?: unknown } | undefined;
  try {
    cfg = JSON.parse(readFileSync(file, "utf8")) as { port?: unknown; token?: unknown };
  } catch {
    cfg = undefined;
  }
  if (!cfg || typeof cfg.port !== "number" || typeof cfg.token !== "string") {
    checks.push({ status: "warn", label: `${file} is missing or unreadable`, fix: "The hub writes it when it starts: open Claude Code with the craftwire plugin enabled (check /mcp)." });
  } else {
    checks.push({ status: "ok", label: `hub.json: port ${cfg.port}` });
    try {
      checks.push(...hubChecks(await hubStatus(cfg.port, cfg.token)));
    } catch {
      checks.push({ status: "warn", label: `no hub answers on 127.0.0.1:${cfg.port}`, fix: "The hub runs inside Claude Code: open Claude Code with the craftwire plugin enabled (check /mcp)." });
    }
  }

  try {
    const major = await (o.javaMajor ?? probeJavaMajor)("java");
    checks.push(major >= MIN_JAVA
      ? { status: "ok", label: `java on PATH is Java ${major}` }
      : { status: "warn", label: `java on PATH is Java ${major}`, fix: `Paper 26.x needs Java ${MIN_JAVA}+: install it, or pass java to server_process.` });
  } catch {
    checks.push({ status: "warn", label: "java is not on PATH", fix: `Install Java ${MIN_JAVA}+ to run Paper 26.x servers.` });
  }
  if (o.serverDir) checks.push(...serverChecks(o.serverDir));
  return checks;
}

function hubChecks(s: HubStatus): Check[] {
  const out: Check[] = [s.hubVersion === HUB_VERSION
    ? { status: "ok", label: `hub ${s.hubVersion} is running` }
    : { status: "warn", label: `hub ${s.hubVersion} is running, this command is ${HUB_VERSION}`, fix: "Restart Claude Code so both use the same version." }];
  if (s.instances.length === 0) out.push({ status: "warn", label: "no game or server is connected", fix: "Start Minecraft with the Craftwire Agent mod, or a Paper server with the Craftwire plugin." });
  for (const i of s.instances) {
    const what = `${i.id} (${i.name}, ${i.kind === "client" ? "mod" : "plugin"} ${i.agentVersion}, Minecraft ${i.mcVersion})`;
    out.push(i.agentVersion === s.hubVersion
      ? { status: "ok", label: what }
      : { status: "warn", label: `${what} differs from hub ${s.hubVersion}`, fix: `Update the Craftwire ${i.kind === "client" ? "Agent mod" : "plugin"} to ${s.hubVersion}.` });
  }
  for (const r of s.rejected) {
    out.push({
      status: "fail",
      label: `${r.agentKind} agent ${r.agentVersion} (${r.instanceName}) was refused: ${r.code}`,
      fix: r.code === "PROTOCOL_MISMATCH" ? "Use the same Craftwire version for the hub, the mod and the plugin." : "Restart the game or server so it re-reads hub.json.",
    });
  }
  return out;
}

function serverChecks(dir: string): Check[] {
  if (!existsSync(dir)) return [{ status: "fail", label: `${dir} does not exist`, fix: "Pass --server <folder with the Paper jar>." }];
  const out: Check[] = [];
  try {
    const l = resolveLaunch(dir);
    out.push({ status: "ok", label: `server jar ${l.jar} (JVM flags from ${l.source})` });
  } catch (e) {
    out.push({ status: "fail", label: (e as Error).message, fix: "Put the Paper jar in the folder as server.jar." });
  }
  out.push(eulaAccepted(dir)
    ? { status: "ok", label: "EULA accepted" }
    : { status: "warn", label: "EULA not accepted (eula.txt)", fix: "Read https://aka.ms/MinecraftEULA and set eula=true yourself; Craftwire never does it for you." });
  const jars = pluginJarsNamed(join(dir, "plugins"), "Craftwire");
  if (jars.length === 0) {
    out.push({ status: "warn", label: "Craftwire plugin is not in plugins/", fix: `Copy craftwire-paper-${HUB_VERSION}.jar into plugins/ (or use plugin_deploy {jar}).` });
  } else {
    const v = pluginInfoOfJar(jars[0]!)?.version;
    out.push(v === HUB_VERSION
      ? { status: "ok", label: `Craftwire plugin ${v}` }
      : { status: "warn", label: `Craftwire plugin ${v ?? "?"} differs from hub ${HUB_VERSION}`, fix: `Install craftwire-paper-${HUB_VERSION}.jar.` });
    if (jars.length > 1) out.push({ status: "warn", label: `${jars.length} Craftwire jars in plugins/`, fix: "Keep only one." });
  }
  return out;
}

export function formatChecks(checks: Check[]): string {
  return checks.map((c) => `[${c.status}] ${c.label}${c.fix ? `\n       -> ${c.fix}` : ""}`).join("\n") + "\n";
}
```

`hub/src/cli.ts`:
- Add the imports `import { resolve } from "node:path";` (merged with `join`) and `import { formatChecks, runDoctor } from "./doctor.js";`.
- Replace the bottom `main().catch(...)` with:

```ts
async function doctor(args: string[]): Promise<void> {
  const at = args.indexOf("--server");
  const serverDir = at >= 0 ? args[at + 1] : undefined;
  const checks = await runDoctor({ home: craftwireHome(), ...(serverDir ? { serverDir: resolve(serverDir) } : {}) });
  process.stdout.write(`craftwire doctor ${HUB_VERSION}\n${formatChecks(checks)}`);
  process.exit(checks.some((c) => c.status === "fail") ? 1 : 0);
}

const argv = process.argv.slice(2);
const fatal = (e: unknown) => {
  log(`fatal: ${e instanceof Error ? e.stack ?? e.message : String(e)}`);
  process.exit(1);
};
if (argv[0] === "doctor") doctor(argv.slice(1)).catch(fatal);
else if (argv[0] === "--version") process.stdout.write(`${HUB_VERSION}\n`);
else main().catch(fatal);
```

- [ ] **Step 4: Run the hub suite**

Run: `cd hub && npx vitest run && npm run typecheck`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add hub/src/doctor.ts hub/src/cli.ts hub/test/doctor.test.ts hub/test/cli.test.ts
git commit -m "feat(hub): craftwire doctor checks Node, Java, the hub, agents and a server folder"
```

---

### Task 7: Dev-loop end-to-end test against a real Paper server

**Files:**
- Create:
  - `hub/test-e2e/paper.ts`
  - `hub/test-e2e/dev-loop.e2e.test.ts`
  - `hub/vitest.e2e.config.ts`
- Modify:
  - `hub/package.json` (script `test:e2e`)
  - `.gitignore` (`hub/.e2e/`)
  - `.github/workflows/ci.yml` (job `dev-loop-e2e`)

**Interfaces:**
- Consumes:
  - `startHub({writeHubJson, agentWaitMs, stopTimeoutMs})` and `json` from Task 4;
  - the tools from Tasks 4 and 5;
  - jars from `./gradlew :agent-paper:build :test-fixtures:build`.
- Produces: `ensurePaper(cacheDir, props): Promise<string>`, `gradleProperties()`, `repoRoot`, `freePort()`.

- [ ] **Step 1: Write the E2E helpers and test**

`hub/test-e2e/paper.ts`:

```ts
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { createServer } from "node:net";
import { join } from "node:path";

export const repoRoot = join(__dirname, "..", "..");

export function gradleProperties(): Record<string, string> {
  const out: Record<string, string> = {};
  for (const line of readFileSync(join(repoRoot, "gradle.properties"), "utf8").split(/\r?\n/)) {
    const m = /^([\w.]+)=(.*)$/.exec(line.trim());
    if (m) out[m[1]!] = m[2]!;
  }
  return out;
}

const sha256 = (b: Buffer) => createHash("sha256").update(b).digest("hex");
const AGENT = "craftwire-e2e-tests (https://github.com/uxplima/craftwire)";

/** The Paper build pinned in gradle.properties (same pin as the Java integration tests), checksum-verified. */
export async function ensurePaper(cacheDir: string, props: Record<string, string>): Promise<string> {
  const mc = props.minecraft_version!;
  const build = props.paper_build!;
  const sum = props.paper_sha256!;
  const jar = join(cacheDir, `paper-${mc}-${build}.jar`);
  if (existsSync(jar) && sha256(readFileSync(jar)) === sum) return jar;
  mkdirSync(cacheDir, { recursive: true });
  const metaRes = await fetch(`https://fill.papermc.io/v3/projects/paper/versions/${mc}/builds/${build}`, { headers: { "User-Agent": AGENT } });
  const meta = (await metaRes.json()) as { downloads: Record<string, { url: string }> };
  const body = Buffer.from(await (await fetch(meta.downloads["server:default"]!.url, { headers: { "User-Agent": AGENT } })).arrayBuffer());
  if (sha256(body) !== sum) throw new Error(`Paper jar checksum mismatch: ${sha256(body)}`);
  writeFileSync(`${jar}.part`, body);
  renameSync(`${jar}.part`, jar);
  return jar;
}

export function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const s = createServer();
    s.once("error", reject);
    s.listen(0, "127.0.0.1", () => {
      const port = (s.address() as { port: number }).port;
      s.close(() => resolve(port));
    });
  });
}
```

`hub/test-e2e/dev-loop.e2e.test.ts`:

```ts
import { copyFileSync, existsSync, mkdirSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { json, startHub } from "../test/helpers/hub.js";
import { ensurePaper, freePort, gradleProperties, repoRoot } from "./paper.js";

const props = gradleProperties();
const e2eDir = join(repoRoot, "hub", ".e2e");
const serverDir = join(e2eDir, "server");
const pluginJar = process.env.CRAFTWIRE_E2E_PLUGIN ?? join(repoRoot, "agent-paper", "build", "libs", `craftwire-paper-${props.craftwire_version}.jar`);
const fixtureJar = process.env.CRAFTWIRE_E2E_FIXTURE ?? join(repoRoot, "test-fixtures", "build", "libs", `craftwire-test-fixtures-${props.craftwire_version}.jar`);
const javaHome = process.env.CRAFTWIRE_E2E_JAVA_HOME ?? process.env.JAVA_HOME;
let hub: Awaited<ReturnType<typeof startHub>>;

beforeAll(async () => {
  for (const f of [pluginJar, fixtureJar]) {
    if (!existsSync(f)) throw new Error(`${f} is missing: run ./gradlew :agent-paper:build :test-fixtures:build first`);
  }
  const paper = await ensurePaper(join(e2eDir, "cache"), props);
  const plugins = join(serverDir, "plugins");
  mkdirSync(plugins, { recursive: true });
  // Keep libraries/ and cache/ between runs (GraalJS download); reset worlds and plugins.
  for (const f of readdirSync(serverDir)) if (f.startsWith("world")) rmSync(join(serverDir, f), { recursive: true, force: true });
  for (const f of readdirSync(plugins)) if (f.endsWith(".jar") || f === "update" || f === ".craftwire-backup" || f === "Craftwire") rmSync(join(plugins, f), { recursive: true, force: true });
  copyFileSync(paper, join(serverDir, "paper.jar"));
  copyFileSync(pluginJar, join(plugins, "craftwire-paper.jar"));
  writeFileSync(join(serverDir, "eula.txt"), "eula=true\n"); // this suite's own throwaway server, like the Java ITs
  writeFileSync(join(serverDir, "server.properties"), [
    "server-ip=127.0.0.1", `server-port=${await freePort()}`, "online-mode=false", "level-type=minecraft\\:flat",
    "generate-structures=false", "view-distance=4", "simulation-distance=4", "max-players=4", "motd=craftwire-e2e",
  ].join("\n") + "\n");
  const java = javaHome ? join(javaHome, "bin", process.platform === "win32" ? "java.exe" : "java") : "java";
  writeFileSync(join(serverDir, process.platform === "win32" ? "start.bat" : "start.sh"), `"${java}" -Xmx2G -jar paper.jar --nogui\n`);
  hub = await startHub({ writeHubJson: true, agentWaitMs: 60_000, stopTimeoutMs: 120_000 });
});

afterAll(async () => {
  await hub?.close();
});

describe("dev loop against a real Paper server", () => {
  it("starts the server from its start script and waits for the Craftwire plugin", async () => {
    const r = json(await hub.call("server_process", { action: "start", serverDir, timeoutMs: 600_000 }));
    expect(r).toMatchObject({ state: "running", agent: expect.stringMatching(/^server-\d+$/) });
    expect(r.launch.source).toMatch(/^start\.(bat|sh)$/);
    const inst = json(await hub.call("list_instances")).instances.find((i: { id: string }) => i.id === r.agent);
    expect(inst.pid).toBe(r.pid);
  });

  it("deploys a ready jar, restarts and reports the plugin enabled", async () => {
    const r = json(await hub.call("plugin_deploy", { jar: fixtureJar, serverDir, timeoutMs: 600_000 }));
    expect(r).toMatchObject({ plugin: { name: "CraftwireFixture" }, server: { state: "running" }, loaded: { enabled: true } });
    const out = json(await hub.call("server_command", { command: "cwfixture", collectMs: 500 }));
    expect(out.output.join("\n")).toContain("fixture: now");
  });

  it("builds a Gradle project and redeploys it", async () => {
    const gradlew = process.platform === "win32" ? "gradlew.bat" : "sh ./gradlew";
    const r = json(await hub.call("plugin_deploy", {
      projectDir: repoRoot, buildCommand: `${gradlew} :test-fixtures:jar --console=plain`, jarGlob: "test-fixtures/build/libs/*.jar",
      ...(javaHome ? { javaHome } : {}), serverDir, timeoutMs: 600_000,
    }));
    expect(r.build.command).toContain(":test-fixtures:jar");
    expect(r.install.replaced).toHaveLength(1);
    expect(r.loaded.enabled).toBe(true);
  });

  it("stops the server gracefully", async () => {
    expect(json(await hub.call("server_process", { action: "stop", serverDir }))).toMatchObject({ stopped: true, forced: false });
    expect(json(await hub.call("server_process", { action: "status" })).servers[0].state).toBe("stopped");
  });
});
```

`hub/vitest.e2e.config.ts`:

```ts
import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    include: ["test-e2e/**/*.e2e.test.ts"],
    testTimeout: 900_000,
    hookTimeout: 600_000,
    fileParallelism: false,
  },
});
```

`hub/package.json` scripts: add `"test:e2e": "vitest run --config vitest.e2e.config.ts"`.

`.gitignore`: add the line `hub/.e2e/`.

- [ ] **Step 2: Run the E2E locally**

Run:
```bash
JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-paper:build :test-fixtures:build
cd hub && CRAFTWIRE_E2E_JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" npm run test:e2e > ../.superpowers/sdd/2026-10-05-craftwire-m3-dev-loop/e2e.log 2>&1; tail -30 ../.superpowers/sdd/2026-10-05-craftwire-m3-dev-loop/e2e.log
```
Expected: 4/4 PASS. The first run downloads Paper and GraalJS. A failure here means Tasks 3–5 have a real-server bug; debug it with superpowers:systematic-debugging, reading `hub/.e2e/server/logs/latest.log`.

- [ ] **Step 3: Add the CI job**

Append to `.github/workflows/ci.yml`:

```yaml
  dev-loop-e2e:
    runs-on: ubuntu-latest
    needs: [java, hub]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 25
      - uses: gradle/actions/setup-gradle@v4
      - uses: actions/setup-node@v4
        with:
          node-version: 22
          cache: npm
          cache-dependency-path: hub/package-lock.json
      - run: ./gradlew :agent-paper:build :test-fixtures:build
      - run: npm ci
        working-directory: hub
      - run: npm run test:e2e
        working-directory: hub
      - if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: dev-loop-e2e-logs
          path: hub/.e2e/server/logs/
```

- [ ] **Step 4: Confirm the unit suite ignores the E2E folder**

Run: `cd hub && npx vitest run && npm run typecheck`
Expected: PASS. Only `test/**` runs: vitest.config.ts includes `test/**/*.test.ts`.

- [ ] **Step 5: Commit**

```bash
git add hub/test-e2e hub/vitest.e2e.config.ts hub/package.json .gitignore .github/workflows/ci.yml
git commit -m "test(hub): dev-loop end-to-end run against a real Paper server"
```

---

### Task 8: Skill, docs and version 0.3.0

**Files:**
- Create: `claude-plugin/skills/paper-plugin-dev/SKILL.md`
- Modify:
  - `claude-plugin/skills/craftwire/SKILL.md`
  - `README.md`
  - `protocol/PROTOCOL.md`
  - `hub/test/plugin-manifest.test.ts`
  - Version 0.3.0: `gradle.properties`, `hub/package.json`, `hub/package-lock.json`, `hub/src/version.ts`, `claude-plugin/.claude-plugin/plugin.json`, `claude-plugin/.mcp.json`, `.claude-plugin/marketplace.json` (wherever `0.2.0` appears)

**Interfaces:**
- Consumes: the tool names and parameters from Tasks 4–6.
- Produces: nothing new for code.

- [ ] **Step 1: Write the failing manifest test**

In `hub/test/plugin-manifest.test.ts`, change the skills list to `["craftwire", "minecraft-promo-shots", "paper-plugin-dev"]`.

Run: `cd hub && npx vitest run test/plugin-manifest.test.ts`
Expected: FAIL, ENOENT for `paper-plugin-dev/SKILL.md`.

- [ ] **Step 2: Write the skill**

`claude-plugin/skills/paper-plugin-dev/SKILL.md`:

```markdown
---
name: paper-plugin-dev
description: Use when developing or debugging a Paper/Bukkit plugin with the craftwire MCP tools — start a local test server, build and redeploy the plugin after each change, read its compiler errors and logs, and check the result in game.
---

# Paper plugin dev loop with Craftwire

## Setup
1. `server_process {action:"status"}` — servers this hub runs are under `servers`; servers started elsewhere are under `external`.
2. No server yet: `server_process {action:"start", serverDir:"<folder with the Paper jar>"}`. JVM flags come from its start.bat/start.sh. The first start can take minutes.
   - `EULA_NOT_ACCEPTED`: stop and ask the user to read the EULA and set `eula=true` themselves. Never edit eula.txt.
   - `JAVA_TOO_OLD` / `JAVA_NOT_FOUND`: Paper 26.x needs Java 25+; pass `java`.
   - `PORT_IN_USE` / `WORLD_LOCKED`: another server is running; check `status`.
3. The start result has `agent` when the Craftwire plugin connected. If `warning` says it is missing: `plugin_deploy {jar:"<craftwire-paper jar>"}`.

## The loop
1. Edit the plugin code.
2. `plugin_deploy {projectDir:"<project root>"}` — builds (tests skipped), installs the jar, restarts the server, and returns `loaded` (enabled, version) and `problems` (WARN/ERROR lines naming the plugin).
   - `BUILD_FAILED`: fix each `details.errors[]` entry (`file:line`) and deploy again; if `errors` is empty, read `details.outputTail`.
   - `AMBIGUOUS_JAR`: multi-module project; pass `jarGlob`, e.g. `"my-plugin/build/libs/*-all.jar"`.
   - `NOT_MANAGED`: the user started this server outside Craftwire. Ask before passing `takeOver:true`: it stops their server.
3. Check: `logs {level:"WARN"}`, `server_command` for the plugin's commands, `server_eval` to inspect state (`plugin('Name')`), and the client tools (`screenshot`, `gui_read`) for anything a player sees.
4. Repeat. Keep `restart:true` (default): Paper cannot reload plugins safely.

## Notes
- `buildCommand` runs any build in projectDir (e.g. `gradlew.bat shadowJar`); `javaHome` sets the JDK for the build.
- Replaced jars are kept in `plugins/.craftwire-backup/`.
- `restart:false` on a running server stages the jar in `plugins/update/`; it loads on the next start.
- A server started by `server_process` stops when the hub exits (Claude Code closes). `server_process {action:"status", tail:100}` shows its console.
- `npx craftwire doctor --server <dir>` checks Node, Java, the hub, the EULA and the plugin.
```

- [ ] **Step 3: Update the craftwire skill, README and PROTOCOL**

`claude-plugin/skills/craftwire/SKILL.md`:
- In the front-matter description, extend the tool list `(screenshot, …, logs, wait_for)` to `(screenshot, …, logs, wait_for, server_process, plugin_deploy)`.
- Add this section before `## Player commands through \`chat\``:

```markdown
## Dev loop (local server)
- `server_process {action:"start"|"stop"|"restart"|"status", serverDir}` runs a local Paper server under the hub; `start` returns when `Done (` was printed and the Craftwire plugin connected.
- `plugin_deploy {projectDir}` (or `{jar}`) builds, installs and restarts, then reports `loaded` and `problems`. See the `paper-plugin-dev` skill for the full loop.
- Never accept the EULA for the user. Ask before `takeOver:true` — it stops a server the user started.
```

`README.md`:
- Intro sentence: replace "A plugin dev loop and bots follow in the next milestones." with "It also runs a local server and builds and redeploys your plugin in one step (dev loop). Bots follow in the next milestone."
- After the `## Paper server` section, add:

```markdown
## Dev loop

Point Claude at a server folder and a plugin project:

- `server_process` starts, stops and restarts a local Paper server. JVM flags come from its start script. It never accepts the EULA for you.
- `plugin_deploy` builds the project (Gradle or Maven, tests skipped) or takes a ready jar, swaps it into `plugins/` (old jar kept in `plugins/.craftwire-backup/`), restarts the server and reports whether the plugin enabled and what it logged. Compiler errors come back as `file:line`.

Ask Claude: *"build my plugin, deploy it to ~/servers/test and tell me what broke"*.

Something not connecting? Run `npx craftwire doctor` (add `--server <folder>` to check a server folder too).
```

- Tools list: add `- Dev loop (M3): \`server_process\` · \`plugin_deploy\` · CLI \`craftwire doctor\``.
- Development section: add `- Dev-loop E2E (real Paper server): \`./gradlew :agent-paper:build :test-fixtures:build\`, then \`cd hub && npm run test:e2e\``.
- Windows npx hint: `craftwire@0.2.0` → `craftwire@0.3.0`.

`protocol/PROTOCOL.md`:
- Item 1: after the params list, add the sentence "Server agents (0.3.0+) also send `serverDir` (the server's working directory) and `pid` (its JVM process id); the hub uses them to recognise servers it started."
- After item 5, add:

```markdown
6. Tools (not agents) may instead open a connection with request `status` `{token}`: the hub answers `{hubVersion, protocolVersion, instances[], rejected[]}` (`rejected` = the last 20 refused hellos: `{time, code, agentKind, agentVersion, instanceName, protocolVersion}`) and closes with 1000. A wrong token gets `UNAUTHORIZED` and close 4001. `craftwire doctor` uses this.
```

- [ ] **Step 4: Bump to 0.3.0**

Run:
```bash
cd C:/Users/pc/Desktop/craftwire && grep -rln "0\.2\.0" gradle.properties hub/src/version.ts claude-plugin .claude-plugin README.md
```

Replace `0.2.0` with `0.3.0` in each listed file using the Edit tool, after reading the file. The `craftwire_version` key in gradle.properties is one of them. Then run:

```bash
cd hub && npm version 0.3.0 --no-git-tag-version
```

Verify that every changed file is still valid UTF-8 (Windows Python defaults to cp1254; use `encoding='utf-8'` if Python is used at all):

```bash
cd C:/Users/pc/Desktop/craftwire && git diff --name-only | xargs -I{} sh -c 'iconv -f utf-8 -t utf-8 "{}" > /dev/null || echo "BAD {}"'
```
Expected: no `BAD` lines.

- [ ] **Step 5: Run every suite**

Run:
```bash
cd hub && npx vitest run && npm run typecheck
cd .. && JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-core:test :agent-fabric:test :agent-fabric:build :agent-paper:test :agent-paper:build :test-fixtures:build :agent-paper:integrationTest > .superpowers/sdd/2026-10-05-craftwire-m3-dev-loop/java.log 2>&1; tail -5 .superpowers/sdd/2026-10-05-craftwire-m3-dev-loop/java.log
cd hub && CRAFTWIRE_E2E_JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" npm run test:e2e
```
Expected:
- the hub suite passes, including the manifest test with 3 skills and the lockstep 0.3.0 version;
- `BUILD SUCCESSFUL`;
- E2E 4/4.

- [ ] **Step 6: Commit**

```bash
git add -A claude-plugin README.md protocol/PROTOCOL.md gradle.properties hub/package.json hub/package-lock.json hub/src/version.ts .claude-plugin hub/test/plugin-manifest.test.ts
git commit -m "docs: dev loop tools, paper-plugin-dev skill, version 0.3.0"
```

---

### Task 9: Manual acceptance on the user's server (ask first)

The steps below change the user's real server: they stop it, swap the Craftwire jar and start it again. **Ask the user before Step 2** and wait for a yes.

**Files:**
- Create: `docs/acceptance/m3-dev-loop.md`

- [ ] **Step 1: Doctor (read-only, no consent needed)**

Run:
```bash
cd C:/Users/pc/Desktop/craftwire/hub && npm run build && node dist/cli.js doctor --server C:/Users/pc/Desktop/server
```
Expected:
- hub.json ok;
- the e2e driver's hub (0.2.0) is running, so a version warning appears;
- the client mod at 0.1.0 triggers a warning;
- Java 26 ok;
- EULA accepted;
- Craftwire plugin 0.2.0 differs from 0.3.0 (warn).

Record the output.

- [ ] **Step 2: Ask the user**

Message (Turkish): explain that `plugin_deploy {jar: craftwire-paper-0.3.0.jar, serverDir, takeOver:true}` will:
- stop their running server via the plugin's `stop` (worlds saved);
- back up the 0.2.0 jar to `plugins/.craftwire-backup/`;
- start the server under the hub with the flags from their start.bat.

Also say that the server will stop when that hub exits. Wait for consent.

- [ ] **Step 3: Run the dev loop through the real hub 0.3.0**

Restart the scratch e2e driver on the new `hub/dist/cli.js`: stop task `beem1846f`, then start `node driver.mjs` again (the driver spawns `hub/dist/cli.js`). Then, through `curl -X POST http://127.0.0.1:47900/call`:
1. `server_process {action:"status"}` → the user's server is under `external` (no `pid`: the 0.2.0 plugin).
2. `plugin_deploy {jar:"C:/Users/pc/Desktop/craftwire/agent-paper/build/libs/craftwire-paper-0.3.0.jar", serverDir:"C:/Users/pc/Desktop/server", takeOver:true, timeoutMs:600000}` → `install.replaced` lists the 0.2.0 jar, `server.state` is running, and `loaded` is `{name:"Craftwire", version:"0.3.0", enabled:true}`.
3. Stop the old background shell `b8mx78bh9` (its java exited in step 2; `tail -f` lingers) with TaskStop.
4. `list_instances` → the server instance has `serverDir` and `pid`. `server_process {action:"status"}` lists it under `servers`, state running.
5. `server_process {action:"restart"}` → running again.
6. `logs {level:"WARN"}` → the usual startup warnings, no Craftwire errors.

- [ ] **Step 4: Record and commit**

Write `docs/acceptance/m3-dev-loop.md`, structured like `m2-server-tools.md`: date, versions, a table of each call and its outcome, notes. Then:

```bash
git add docs/acceptance/m3-dev-loop.md
git commit -m "docs: M3 manual dev-loop run on the user's server"
```

---

## Self-review notes

- **Spec coverage:**
  - `server_process` (start/stop/restart/status, serverDir, jvmArgs, ready = `Done (` + agent): Tasks 3–4.
  - `plugin_deploy` (projectDir, buildCommand, Gradle/Maven detection, jarGlob, serverDir, restart default true, `file:line` errors): Tasks 2 and 5.
  - `craftwire doctor` (Node, hub.json, agents, version compatibility, fixes): Tasks 1 and 6.
  - `SERVER_NOT_RUNNING` / `BUILD_FAILED` with parsed errors: Tasks 3 and 5.
  - `paper-plugin-dev` skill: Task 8.
  - The spec also lists a `fabric-mod-dev` skill under §8 skills. It needs a client launch/redeploy tool that no milestone defines yet, so it is left for a later plan. This is noted, not dropped silently.
- **Additions beyond the spec, each serving a spec requirement:**
  - `java` and `jar` parameters and start-script parsing, so the user's own flags are used;
  - `takeOver`, so a user-started server is never stopped silently;
  - `javaHome`, because this machine's JAVA_HOME is 21;
  - staging in `plugins/update/`, because Windows locks jars;
  - the refused-agent list, so doctor can show protocol mismatches.
