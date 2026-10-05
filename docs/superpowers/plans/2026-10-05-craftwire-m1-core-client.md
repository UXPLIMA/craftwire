# Craftwire M1 (Core + Client) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship milestone M1 of Craftwire: a Node/TypeScript MCP hub plus a Fabric client agent. With M1, Claude Code can screenshot Minecraft, drive the camera, read and click GUIs, send chat and commands, read the HUD and simulate input, with no human at the keyboard.

**Architecture:** The hub (`hub/`, npm package `craftwire`) is the only MCP server. Claude Code talks to it over stdio, and it opens a WebSocket server on `127.0.0.1`. The Fabric mod (`agent-fabric/`) embeds a shared Java library (`agent-core/`) that connects *out* to the hub, performs a token handshake and executes JSON-RPC requests on the Minecraft client thread. Protocol fixtures in `protocol/fixtures/` are the shared contract: both the TypeScript and the Java test suites must accept them.

**Tech Stack:**
- Hub: Node ≥ 20, TypeScript 5.9, `@modelcontextprotocol/sdk` 1.32, `ws` 8, `zod` 4, Vitest 5
- Java side: Java 25 (`release 25`), Gradle 9.7.1 wrapper, Fabric Loom 1.18.2, Fabric Loader 0.19.5, Fabric API 0.161.0+26.2, Minecraft 26.2 (unobfuscated, Mojang names), Gson (provided by Minecraft), JUnit 5.13, Java-WebSocket 1.6 (tests only)

**Spec:** `docs/superpowers/specs/2026-10-05-craftwire-design.md`. This plan covers spec §9 milestone **M1**, plus the M1 slices of §3 Protocol, §5 Security, §6 Errors, §7 Testing and §8 Distribution. M2–M4 get their own plans.

## Global Constraints

- Minecraft target: **26.2** (Fabric). Java bytecode `release = 25`.
- Hub runtime: Node `>=20`. Runtime dependencies are limited to `@modelcontextprotocol/sdk`, `ws` and `zod` (spec §2).
- Java runtime dependencies: none beyond JDK `java.net.http` and Gson (spec §2). Java-WebSocket is test-only.
- Network: the hub binds `127.0.0.1` only. Agents open no ports (spec §5).
- Hub port: try `47821` first, fall back to a random free port, then write the chosen port to `hub.json` (spec §3).
- Token: 256-bit random, stored in `~/.craftwire/hub.json` as `{ "port", "token" }` with user-only permissions. `CRAFTWIRE_HOME` overrides `~/.craftwire`. Compare in constant time (spec §5).
- WebSocket upgrades carrying an `Origin` header are rejected (spec §5).
- Protocol: JSON-RPC 2.0 over WebSocket. `protocolVersion` integer = **1** (spec §3).
- Error shape returned to the model: `{ "code", "message", "hint" }` (spec §6).
- Operation cache: results keyed by `operationId` are kept for **5 minutes** (spec §3).
- Event ring buffer: **5,000** entries per instance (spec §3).
- Screenshot default `maxSize` (long edge returned to the model) is **1600** px. Full-resolution PNG is written only when `savePath` is given (spec §3).
- Kill switch: **F8** pauses all AI control. On-screen indicator text reads "⚡ Craftwire connected" and is hidden during captures (spec §5).
- Reconnect backoff runs from 1 s, doubling, up to 30 s (spec §6).
- Names: npm `craftwire`, mod id `craftwire-agent`, mod name "Craftwire Agent", Java package root `com.uxplima.craftwire`, license MIT.
- Audit log: every tool call is appended as JSONL to `~/.craftwire/logs/audit-YYYY-MM-DD.jsonl` (spec §5).

## Deviation from spec (deliberate, flag in review)

Spec §7 says "Schemas generate TS and Java types". This plan uses **shared JSON fixtures** as the contract instead of code generation. `protocol/fixtures/*.json` is validated by zod in the hub tests and parsed by Gson in the agent-core tests. The drift protection is the same, with no codegen toolchain. This is revisited if the protocol grows past ~20 message types.

Spec §7 also lists an automated end-to-end layer (server + client + hub + fixture plugin). That layer needs the Paper agent, so it lands in M2. In M1, the hub is tested against a fake agent over a real WebSocket (Tasks 3–6), the agent is tested against a fake hub (Task 8), and the real hub ↔ real client path is verified manually in Task 15.

## Review Focus

These failure modes are implied by the spec but no feature test naturally exercises them. Each one is pinned by a test in the owning task.

1. **The user alt-tabs to Claude Code.** Minecraft's default `pauseOnLostFocus` opens the pause menu, which ruins screenshots and GUI actions. Expected: while the hub is connected, the agent turns `pauseOnLostFocus` off and restores the user's value on disconnect. Owned by Task 9.
2. **Two hubs at once** (two Claude Code sessions): the second hub binds a random port and overwrites `hub.json`. Expected: the agent re-reads `hub.json` on every reconnect attempt and follows the newest hub. Owned by Task 8.
3. **Garbage on the socket:** non-JSON, a first message that is not `hello`, a wrong token, or a browser `Origin`. Expected: the hub closes that socket with a reason and never crashes. Owned by Task 3.
4. **Screenshot fails midway**, or is taken while a GUI is open with `hud: false`. Expected: the HUD-hidden flag, the camera override and the FOV are always restored (`finally`), and the open GUI stays open. Owned by Task 12.
5. **Stale GUI state:** `gui_action` targets a slot index from an earlier `gui_read`, but the screen changed or closed. Expected: a `SLOT_OUT_OF_RANGE` / `NO_SCREEN_OPEN` error with a hint, never an exception on the client thread. Owned by Task 11.

## File Structure

```
craftwire/
├─ .gitignore  .editorconfig  LICENSE  README.md
├─ protocol/
│  ├─ PROTOCOL.md                 message reference (human-readable)
│  └─ fixtures/                   contract fixtures (valid-*.json / invalid-*.json)
├─ hub/
│  ├─ package.json  tsconfig.json  vitest.config.ts
│  ├─ src/
│  │  ├─ version.ts               HUB_VERSION, PROTOCOL_VERSION
│  │  ├─ config.ts                craftwireHome, token, hub.json
│  │  ├─ errors.ts                CraftwireError, toToolError
│  │  ├─ protocol.ts              zod schemas for agent→hub messages
│  │  ├─ ringbuffer.ts            RingBuffer<T>
│  │  ├─ agents.ts                AgentServer (WebSocket, handshake, routing)
│  │  ├─ operations.ts            OperationTracker
│  │  ├─ audit.ts                 AuditLog (JSONL)
│  │  ├─ tools/registry.ts        defineTool helper, ok()/image results
│  │  ├─ tools/hub-tools.ts       list_instances, wait_for, get_request_status
│  │  ├─ tools/client-tools.ts    screenshot, camera, gui_read, gui_action, input, chat, hud_read, player_state, client_settings
│  │  ├─ server.ts                createCraftwireServer()
│  │  └─ cli.ts                   bin entry (stdio)
│  └─ test/ …                     *.test.ts, helpers/fakeAgent.ts
├─ settings.gradle  build.gradle  gradle.properties  gradlew(.bat)  gradle/wrapper/*
├─ agent-core/
│  ├─ build.gradle
│  └─ src/main/java/com/uxplima/craftwire/core/
│     Json, HubConfig, AgentError, Handler, OperationCache, Dispatcher, Backoff, Hello, RpcCodec, HubClient
│     (tests in src/test/java/…)
├─ agent-fabric/
│  ├─ build.gradle
│  ├─ src/main/java/com/uxplima/craftwire/fabric/
│  │  CraftwireClient (entrypoint), CraftwireAgent, ClientScheduler, KillSwitch, Indicator, ChatBridge, ScreenWatcher, ItemJson
│  │  camera/ CameraOverride, CameraMath
│  │  handlers/ PlayerStateHandler, ChatSendHandler, HudReadHandler, GuiReadHandler, GuiActionHandler,
│  │            ScreenshotHandler, CameraHandler, InputHandler, ClientSettingsHandler, Handlers
│  │  mixin/ HudAccessor, BossHealthOverlayAccessor, MouseHandlerAccessor, AbstractContainerScreenAccessor, CameraMixin
│  ├─ src/main/resources/ fabric.mod.json, craftwire-agent.mixins.json, assets/craftwire/lang/en_us.json
│  ├─ src/test/java/…              plain JUnit (CameraMath)
│  └─ src/gametest/java/…          Fabric client gametests
├─ claude-plugin/                 .claude-plugin/plugin.json, .mcp.json, skills/
├─ .claude-plugin/marketplace.json
└─ .github/workflows/ci.yml
```

---

### Task 1: Repository scaffold, protocol fixtures, hub package skeleton

**Files:**
- Create: `.gitignore`, `.editorconfig`, `LICENSE`, `README.md`
- Create: `protocol/PROTOCOL.md`, `protocol/fixtures/*.json` (listed below)
- Create: `hub/package.json`, `hub/tsconfig.json`, `hub/vitest.config.ts`
- Create: `hub/src/version.ts`, `hub/src/protocol.ts`
- Test: `hub/test/protocol.test.ts`

**Interfaces:**
- Produces: `PROTOCOL_VERSION = 1`, `HUB_VERSION = "0.1.0"` (`hub/src/version.ts`). Zod schemas `HelloRequest`, `RpcResponse`, `EventNotification` and the union `AgentMessage` (`hub/src/protocol.ts`), with type `AgentEvent = { type: string; time: number; data: Record<string, unknown> }`.

- [ ] **Step 1: Root files**

`.gitignore`:
```
node_modules/
dist/
build/
.gradle/
run/
*.log
.idea/
out/
```

`.editorconfig`:
```
root = true
[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
indent_style = space
indent_size = 2
[*.java]
indent_size = 4
```

`LICENSE`: the standard MIT license text with `Copyright (c) 2026 UXPLIMA`.

`README.md`:
```markdown
# Craftwire

Let AI agents (Claude Code and any MCP client) see and drive Minecraft: screenshots, camera, GUIs, chat, input — and soon server control, scripting, a plugin dev loop and bots.

> Status: M1 (hub + Fabric client agent) in development. Minecraft 26.2, Fabric.

By [UXPLIMA](https://github.com/uxplima). MIT licensed.
```

- [ ] **Step 2: Protocol reference and fixtures**

`protocol/PROTOCOL.md`:
```markdown
# Craftwire protocol v1

Transport: WebSocket `ws://127.0.0.1:<port>/` (port from `~/.craftwire/hub.json`). Framing: JSON-RPC 2.0, one message per text frame.

1. Agent → hub, first message: request `hello` with id `0`.
   params: `token`, `agentKind` ("client"|"server"), `agentVersion`, `protocolVersion` (1), `mcVersion`, `instanceName`.
2. Hub → agent: response id `0` with `result.instanceId`, or `error` with `data.code` (`UNAUTHORIZED`, `PROTOCOL_MISMATCH`) followed by close.
3. Hub → agent: requests `{id, method, params}`. Params may include `operationId`.
4. Agent → hub: responses `{id, result}` or `{id, error: {code: -32000, message, data: {code, hint}}}`.
5. Agent → hub: notifications `{method: "event", params: {type, time, data}}`.

Event types (M1): `chat` `{text, kind: "chat"|"game", sender?}`, `hud` `{element: "actionbar", text}`, `screen` `{open, title, type}`.

Client methods (M1): `player.state`, `chat.send`, `hud.read`, `gui.read`, `gui.action`, `screenshot`, `camera`, `input`, `client.settings`.
```

`protocol/fixtures/valid-hello.json`:
```json
{"jsonrpc":"2.0","id":0,"method":"hello","params":{"token":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","agentKind":"client","agentVersion":"0.1.0","protocolVersion":1,"mcVersion":"26.2","instanceName":"Sirac"}}
```
`protocol/fixtures/valid-response-result.json`:
```json
{"jsonrpc":"2.0","id":7,"result":{"open":false}}
```
`protocol/fixtures/valid-response-error.json`:
```json
{"jsonrpc":"2.0","id":8,"error":{"code":-32000,"message":"No screen is open","data":{"code":"NO_SCREEN_OPEN","hint":"Open a menu first, e.g. chat {action:\"command\", text:\"/builders crew\"}."}}}
```
`protocol/fixtures/valid-event-chat.json`:
```json
{"jsonrpc":"2.0","method":"event","params":{"type":"chat","time":1759670000000,"data":{"text":"<Sirac> hello","kind":"chat","sender":"Sirac"}}}
```
`protocol/fixtures/invalid-hello-missing-token.json`:
```json
{"jsonrpc":"2.0","id":0,"method":"hello","params":{"agentKind":"client","agentVersion":"0.1.0","protocolVersion":1,"mcVersion":"26.2","instanceName":"x"}}
```
`protocol/fixtures/invalid-hello-bad-kind.json`:
```json
{"jsonrpc":"2.0","id":0,"method":"hello","params":{"token":"t","agentKind":"bot","agentVersion":"0.1.0","protocolVersion":1,"mcVersion":"26.2","instanceName":"x"}}
```
`protocol/fixtures/invalid-event-no-type.json`:
```json
{"jsonrpc":"2.0","method":"event","params":{"time":1,"data":{}}}
```

- [ ] **Step 3: Hub package files**

`hub/package.json`:
```json
{
  "name": "craftwire",
  "version": "0.1.0",
  "description": "MCP hub that lets AI agents see and drive Minecraft (Craftwire by UXPLIMA)",
  "license": "MIT",
  "type": "module",
  "bin": { "craftwire": "dist/cli.js" },
  "files": ["dist"],
  "engines": { "node": ">=20" },
  "scripts": {
    "build": "tsc -p tsconfig.json",
    "typecheck": "tsc -p tsconfig.json --noEmit",
    "test": "vitest run"
  },
  "dependencies": {
    "@modelcontextprotocol/sdk": "^1.32.1",
    "ws": "^8.22.0",
    "zod": "^4.6.5"
  },
  "devDependencies": {
    "@types/node": "^24.0.0",
    "@types/ws": "^8.18.0",
    "typescript": "~5.9.0",
    "vitest": "^5.0.3"
  }
}
```

`hub/tsconfig.json`:
```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "NodeNext",
    "moduleResolution": "NodeNext",
    "outDir": "dist",
    "rootDir": "src",
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "declaration": false,
    "sourceMap": true
  },
  "include": ["src"]
}
```

`hub/vitest.config.ts`:
```ts
import { defineConfig } from "vitest/config";

export default defineConfig({
  test: { include: ["test/**/*.test.ts"], testTimeout: 20000 },
});
```

Run: `cd hub && npm install`
Expected: installs without errors and creates `package-lock.json`.

- [ ] **Step 4: Write the failing protocol test**

`hub/test/protocol.test.ts`:
```ts
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { AgentMessage } from "../src/protocol.js";

const dir = join(__dirname, "..", "..", "protocol", "fixtures");
const files = readdirSync(dir).filter((f) => f.endsWith(".json"));

describe("protocol fixtures", () => {
  it("has fixtures", () => expect(files.length).toBeGreaterThanOrEqual(7));

  for (const f of files) {
    it(`${f} is ${f.startsWith("valid-") ? "accepted" : "rejected"}`, () => {
      const msg = JSON.parse(readFileSync(join(dir, f), "utf8"));
      const res = AgentMessage.safeParse(msg);
      expect(res.success).toBe(f.startsWith("valid-"));
    });
  }
});
```

- [ ] **Step 5: Run it to verify it fails**

Run: `cd hub && npx vitest run test/protocol.test.ts`
Expected: FAIL, with "Cannot find module '../src/protocol.js'".

- [ ] **Step 6: Implement `version.ts` and `protocol.ts`**

`hub/src/version.ts`:
```ts
export const HUB_VERSION = "0.1.0";
export const PROTOCOL_VERSION = 1;
```

`hub/src/protocol.ts`:
```ts
import { z } from "zod";

const RpcId = z.union([z.number().int(), z.string()]);

export const HelloParams = z.object({
  token: z.string().min(1),
  agentKind: z.enum(["client", "server"]),
  agentVersion: z.string(),
  protocolVersion: z.number().int(),
  mcVersion: z.string(),
  instanceName: z.string(),
});
export type HelloParams = z.infer<typeof HelloParams>;

export const HelloRequest = z.object({
  jsonrpc: z.literal("2.0"),
  id: RpcId,
  method: z.literal("hello"),
  params: HelloParams,
});

export const RpcErrorObject = z.object({
  code: z.number().int(),
  message: z.string(),
  data: z.object({ code: z.string(), hint: z.string().optional() }).partial().optional(),
});

export const RpcResponse = z.union([
  z.object({ jsonrpc: z.literal("2.0"), id: RpcId, result: z.unknown() }).refine((m) => "result" in m),
  z.object({ jsonrpc: z.literal("2.0"), id: RpcId, error: RpcErrorObject }),
]);

export const EventParams = z.object({
  type: z.string().min(1),
  time: z.number(),
  data: z.record(z.string(), z.unknown()),
});
export type AgentEvent = z.infer<typeof EventParams>;

export const EventNotification = z.object({
  jsonrpc: z.literal("2.0"),
  method: z.literal("event"),
  params: EventParams,
});

export const AgentMessage = z.union([HelloRequest, RpcResponse, EventNotification]);
export type AgentMessage = z.infer<typeof AgentMessage>;
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `cd hub && npx vitest run test/protocol.test.ts && npm run typecheck`
Expected: PASS (8 tests). Typecheck exits with 0.

- [ ] **Step 8: Commit**

```bash
git add .gitignore .editorconfig LICENSE README.md protocol hub/package.json hub/package-lock.json hub/tsconfig.json hub/vitest.config.ts hub/src hub/test
git commit -m "feat(hub): scaffold repo, protocol fixtures and zod schemas"
```

---

### Task 2: Hub config (home dir, token, hub.json) and errors

**Files:**
- Create: `hub/src/config.ts`, `hub/src/errors.ts`
- Test: `hub/test/config.test.ts`, `hub/test/errors.test.ts`

**Interfaces:**
- Produces: `craftwireHome(): string`, `loadOrCreateToken(home?: string): string` (64 hex chars), `writeHubConfig(cfg: {port: number; token: string}, home?: string): string` (returns the file path), `tokensEqual(a: string, b: string): boolean`.
- Produces: `class CraftwireError extends Error { code: string; hint?: string; toJSON() }` and `toToolError(err: unknown): CallToolResult`. The result has `isError: true` and one text item containing `JSON.stringify({code, message, hint})`. Unknown errors map to code `INTERNAL`.

- [ ] **Step 1: Write the failing tests**

`hub/test/config.test.ts`:
```ts
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { craftwireHome, loadOrCreateToken, tokensEqual, writeHubConfig } from "../src/config.js";

const tmp = () => mkdtempSync(join(tmpdir(), "cw-"));

describe("config", () => {
  afterEach(() => { delete process.env.CRAFTWIRE_HOME; });

  it("honours CRAFTWIRE_HOME", () => {
    process.env.CRAFTWIRE_HOME = "/x/y";
    expect(craftwireHome()).toBe("/x/y");
  });

  it("creates a 64-hex token when none exists", () => {
    expect(loadOrCreateToken(tmp())).toMatch(/^[0-9a-f]{64}$/);
  });

  it("reuses the token stored in hub.json", () => {
    const home = tmp();
    const token = "b".repeat(64);
    writeHubConfig({ port: 1234, token }, home);
    expect(loadOrCreateToken(home)).toBe(token);
  });

  it("ignores a corrupt hub.json", () => {
    const home = tmp();
    writeFileSync(join(home, "hub.json"), "{not json");
    expect(loadOrCreateToken(home)).toMatch(/^[0-9a-f]{64}$/);
  });

  it("writes port and token", () => {
    const home = tmp();
    const file = writeHubConfig({ port: 47821, token: "c".repeat(64) }, home);
    expect(JSON.parse(readFileSync(file, "utf8"))).toEqual({ port: 47821, token: "c".repeat(64) });
  });

  it("compares tokens safely", () => {
    expect(tokensEqual("abc", "abc")).toBe(true);
    expect(tokensEqual("abc", "abd")).toBe(false);
    expect(tokensEqual("abc", "abcd")).toBe(false);
  });
});
```

`hub/test/errors.test.ts`:
```ts
import { describe, expect, it } from "vitest";
import { CraftwireError, toToolError } from "../src/errors.js";

const body = (r: ReturnType<typeof toToolError>) => JSON.parse((r.content[0] as { text: string }).text);

describe("errors", () => {
  it("serialises CraftwireError with hint", () => {
    const r = toToolError(new CraftwireError("NO_INSTANCE", "No client connected", "Start Minecraft with Craftwire Agent."));
    expect(r.isError).toBe(true);
    expect(body(r)).toEqual({ code: "NO_INSTANCE", message: "No client connected", hint: "Start Minecraft with Craftwire Agent." });
  });

  it("maps unknown errors to INTERNAL", () => {
    expect(body(toToolError(new Error("boom")))).toMatchObject({ code: "INTERNAL", message: "boom" });
    expect(body(toToolError("weird"))).toMatchObject({ code: "INTERNAL", message: "weird" });
  });
});
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd hub && npx vitest run test/config.test.ts test/errors.test.ts`
Expected: FAIL, with module-not-found errors.

- [ ] **Step 3: Implement**

`hub/src/config.ts`:
```ts
import { randomBytes, timingSafeEqual } from "node:crypto";
import { chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

export interface HubConfig {
  port: number;
  token: string;
}

export function craftwireHome(): string {
  const env = process.env.CRAFTWIRE_HOME;
  return env && env.trim() !== "" ? env : join(homedir(), ".craftwire");
}

export function loadOrCreateToken(home: string = craftwireHome()): string {
  const file = join(home, "hub.json");
  if (existsSync(file)) {
    try {
      const parsed = JSON.parse(readFileSync(file, "utf8")) as Partial<HubConfig>;
      if (typeof parsed.token === "string" && /^[0-9a-f]{64}$/.test(parsed.token)) return parsed.token;
    } catch {
      // corrupt file: fall through and mint a new token
    }
  }
  return randomBytes(32).toString("hex");
}

export function writeHubConfig(config: HubConfig, home: string = craftwireHome()): string {
  mkdirSync(home, { recursive: true });
  const file = join(home, "hub.json");
  writeFileSync(file, JSON.stringify({ port: config.port, token: config.token }, null, 2), { mode: 0o600 });
  try {
    chmodSync(file, 0o600);
  } catch {
    // Windows relies on the user-profile ACL
  }
  return file;
}

export function tokensEqual(a: string, b: string): boolean {
  const ba = Buffer.from(a);
  const bb = Buffer.from(b);
  return ba.length === bb.length && timingSafeEqual(ba, bb);
}
```

`hub/src/errors.ts`:
```ts
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";

export class CraftwireError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly hint?: string,
  ) {
    super(message);
    this.name = "CraftwireError";
  }

  toJSON(): { code: string; message: string; hint?: string } {
    return this.hint === undefined
      ? { code: this.code, message: this.message }
      : { code: this.code, message: this.message, hint: this.hint };
  }
}

export function toToolError(err: unknown): CallToolResult {
  const payload =
    err instanceof CraftwireError
      ? err.toJSON()
      : { code: "INTERNAL", message: err instanceof Error ? err.message : String(err) };
  return { isError: true, content: [{ type: "text", text: JSON.stringify(payload) }] };
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd hub && npx vitest run test/config.test.ts test/errors.test.ts`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add hub/src/config.ts hub/src/errors.ts hub/test/config.test.ts hub/test/errors.test.ts
git commit -m "feat(hub): token/hub.json config and structured errors"
```

---

### Task 3: AgentServer — WebSocket, handshake, request routing, events

**Files:**
- Create: `hub/src/ringbuffer.ts`, `hub/src/agents.ts`
- Create: `hub/test/helpers/fakeAgent.ts`
- Test: `hub/test/ringbuffer.test.ts`, `hub/test/agents.test.ts`

**Interfaces:**
- Consumes: `HelloRequest`, `RpcResponse`, `EventNotification`, `AgentEvent` (Task 1); `CraftwireError`, `tokensEqual` (Task 2); `PROTOCOL_VERSION` (Task 1).
- Produces:
  ```ts
  class RingBuffer<T> { constructor(capacity: number); push(v: T): void; toArray(): T[]; get size(): number }
  type AgentKind = "client" | "server";
  interface InstanceInfo { id: string; kind: AgentKind; name: string; agentVersion: string; mcVersion: string; connectedAt: number }
  class AgentServer extends EventEmitter {
    constructor(opts: { token: string; port: number; host?: string; requestTimeoutMs?: number; handshakeTimeoutMs?: number; bufferSize?: number });
    listen(): Promise<number>;                       // resolves with the bound port
    instances(): InstanceInfo[];
    resolve(kind: AgentKind, selector?: string): InstanceInfo;   // throws NO_INSTANCE / AMBIGUOUS_INSTANCE
    request(instanceId: string, method: string, params: Record<string, unknown>, timeoutMs?: number): Promise<unknown>;
    events(instanceId: string): AgentEvent[];
    close(): Promise<void>;
    // emits: "connected"(InstanceInfo), "disconnected"(InstanceInfo), "event"(instanceId: string, ev: AgentEvent)
  }
  ```
  Test helper `connectFakeAgent(port, opts) → Promise<FakeAgent>`, where `FakeAgent` has `instanceId`, `onRequest(method, fn)`, `emit(type, data)`, `close()`.
- Instance ids: `client-1`, `client-2`, `server-1`… (per-kind counter). `resolve(kind, selector)` matches the id exactly or the `name` case-insensitively.
- WebSocket close codes: `4001` unauthorized, `4002` protocol mismatch, `4003` bad handshake.

- [ ] **Step 1: Write the failing ring buffer test**

`hub/test/ringbuffer.test.ts`:
```ts
import { describe, expect, it } from "vitest";
import { RingBuffer } from "../src/ringbuffer.js";

describe("RingBuffer", () => {
  it("keeps the newest N items in order", () => {
    const rb = new RingBuffer<number>(3);
    [1, 2, 3, 4, 5].forEach((n) => rb.push(n));
    expect(rb.toArray()).toEqual([3, 4, 5]);
    expect(rb.size).toBe(3);
  });

  it("works below capacity", () => {
    const rb = new RingBuffer<string>(5);
    rb.push("a");
    expect(rb.toArray()).toEqual(["a"]);
  });
});
```

- [ ] **Step 2: Implement the ring buffer**

`hub/src/ringbuffer.ts`:
```ts
export class RingBuffer<T> {
  private readonly items: T[] = [];

  constructor(private readonly capacity: number) {}

  push(value: T): void {
    this.items.push(value);
    if (this.items.length > this.capacity) this.items.shift();
  }

  toArray(): T[] {
    return [...this.items];
  }

  get size(): number {
    return this.items.length;
  }
}
```

Run: `cd hub && npx vitest run test/ringbuffer.test.ts`
Expected: PASS.

- [ ] **Step 3: Write the fake agent helper**

`hub/test/helpers/fakeAgent.ts`:
```ts
import WebSocket from "ws";

type Handler = (params: Record<string, unknown>) => unknown | Promise<unknown>;

export interface FakeAgent {
  instanceId: string;
  socket: WebSocket;
  onRequest(method: string, fn: Handler): void;
  emit(type: string, data: Record<string, unknown>): void;
  close(): Promise<void>;
}

export async function connectFakeAgent(
  port: number,
  opts: { token: string; kind?: "client" | "server"; name?: string; protocolVersion?: number },
): Promise<FakeAgent> {
  const socket = new WebSocket(`ws://127.0.0.1:${port}/`);
  const handlers = new Map<string, Handler>();
  await new Promise<void>((res, rej) => { socket.once("open", () => res()); socket.once("error", rej); });

  const instanceId = await new Promise<string>((resolve, reject) => {
    socket.once("message", (raw) => {
      const msg = JSON.parse(String(raw));
      if (msg.result?.instanceId) resolve(msg.result.instanceId);
      else reject(new Error(JSON.stringify(msg.error)));
    });
    socket.once("close", (code, reason) => reject(new Error(`closed ${code} ${reason}`)));
    socket.send(JSON.stringify({
      jsonrpc: "2.0", id: 0, method: "hello",
      params: {
        token: opts.token, agentKind: opts.kind ?? "client", agentVersion: "0.1.0",
        protocolVersion: opts.protocolVersion ?? 1, mcVersion: "26.2", instanceName: opts.name ?? "Tester",
      },
    }));
  });

  socket.on("message", async (raw) => {
    const msg = JSON.parse(String(raw));
    if (msg.method === undefined || msg.id === undefined) return;
    const fn = handlers.get(msg.method);
    try {
      if (!fn) throw Object.assign(new Error(`no handler ${msg.method}`), { code: "UNKNOWN_METHOD" });
      const result = await fn(msg.params ?? {});
      socket.send(JSON.stringify({ jsonrpc: "2.0", id: msg.id, result }));
    } catch (e) {
      const err = e as Error & { code?: string; hint?: string };
      socket.send(JSON.stringify({
        jsonrpc: "2.0", id: msg.id,
        error: { code: -32000, message: err.message, data: { code: err.code ?? "INTERNAL", hint: err.hint } },
      }));
    }
  });

  return {
    instanceId,
    socket,
    onRequest: (method, fn) => handlers.set(method, fn),
    emit: (type, data) => socket.send(JSON.stringify({ jsonrpc: "2.0", method: "event", params: { type, time: Date.now(), data } })),
    close: () => new Promise((res) => { socket.once("close", () => res()); socket.close(); }),
  };
}
```

- [ ] **Step 4: Write the failing AgentServer tests**

`hub/test/agents.test.ts`:
```ts
import { createServer } from "node:net";
import WebSocket from "ws";
import { afterEach, describe, expect, it } from "vitest";
import { AgentServer } from "../src/agents.js";
import { CraftwireError } from "../src/errors.js";
import { connectFakeAgent } from "./helpers/fakeAgent.js";

const TOKEN = "a".repeat(64);
let server: AgentServer | undefined;

async function start(opts: Partial<ConstructorParameters<typeof AgentServer>[0]> = {}) {
  server = new AgentServer({ token: TOKEN, port: 0, requestTimeoutMs: 500, handshakeTimeoutMs: 300, ...opts });
  const port = await server.listen();
  return { server, port };
}

function closeCode(port: number, first?: string, headers?: Record<string, string>): Promise<number> {
  return new Promise((resolve) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/`, { headers });
    ws.on("open", () => { if (first !== undefined) ws.send(first); });
    ws.on("close", (code) => resolve(code));
    ws.on("error", () => resolve(-1));
  });
}

afterEach(async () => { await server?.close(); server = undefined; });

describe("AgentServer handshake", () => {
  it("accepts a valid hello and lists the instance", async () => {
    const { server, port } = await start();
    const agent = await connectFakeAgent(port, { token: TOKEN, name: "Sirac" });
    expect(agent.instanceId).toBe("client-1");
    expect(server.instances()).toMatchObject([{ id: "client-1", kind: "client", name: "Sirac", mcVersion: "26.2" }]);
  });

  it("rejects a wrong token with 4001", async () => {
    const { port } = await start();
    await expect(connectFakeAgent(port, { token: "b".repeat(64) })).rejects.toThrow(/UNAUTHORIZED/);
  });

  it("rejects a protocol mismatch with PROTOCOL_MISMATCH", async () => {
    const { port } = await start();
    await expect(connectFakeAgent(port, { token: TOKEN, protocolVersion: 99 })).rejects.toThrow(/PROTOCOL_MISMATCH/);
  });

  it("closes on non-JSON first message (4003)", async () => {
    const { port } = await start();
    expect(await closeCode(port, "not json")).toBe(4003);
  });

  it("closes when the first message is not hello (4003)", async () => {
    const { port } = await start();
    expect(await closeCode(port, JSON.stringify({ jsonrpc: "2.0", method: "event", params: { type: "x", time: 1, data: {} } }))).toBe(4003);
  });

  it("closes silent sockets after the handshake timeout", async () => {
    const { port } = await start();
    expect(await closeCode(port)).toBe(4003);
  });

  it("refuses browser connections that send an Origin header", async () => {
    const { port } = await start();
    expect(await closeCode(port, undefined, { Origin: "https://evil.example" })).toBe(-1);
  });

  it("keeps running after a bad client and still accepts good ones", async () => {
    const { port } = await start();
    await closeCode(port, "garbage");
    const agent = await connectFakeAgent(port, { token: TOKEN });
    expect(agent.instanceId).toMatch(/^client-/);
  });
});

describe("AgentServer routing", () => {
  it("round-trips a request", async () => {
    const { server, port } = await start();
    const agent = await connectFakeAgent(port, { token: TOKEN });
    agent.onRequest("gui.read", (p) => ({ echo: p }));
    await expect(server.request(agent.instanceId, "gui.read", { a: 1 })).resolves.toEqual({ echo: { a: 1 } });
  });

  it("maps agent errors to CraftwireError", async () => {
    const { server, port } = await start();
    const agent = await connectFakeAgent(port, { token: TOKEN });
    agent.onRequest("gui.read", () => { throw Object.assign(new Error("No screen"), { code: "NO_SCREEN_OPEN", hint: "open one" }); });
    const err = await server.request(agent.instanceId, "gui.read", {}).catch((e) => e);
    expect(err).toBeInstanceOf(CraftwireError);
    expect(err).toMatchObject({ code: "NO_SCREEN_OPEN", hint: "open one" });
  });

  it("times out with TIMEOUT", async () => {
    const { server, port } = await start();
    const agent = await connectFakeAgent(port, { token: TOKEN });
    agent.onRequest("slow", () => new Promise(() => {}));
    await expect(server.request(agent.instanceId, "slow", {}, 100)).rejects.toMatchObject({ code: "TIMEOUT" });
  });

  it("fails pending requests with AGENT_DISCONNECTED when the agent drops", async () => {
    const { server, port } = await start();
    const agent = await connectFakeAgent(port, { token: TOKEN });
    agent.onRequest("slow", () => new Promise(() => {}));
    const pending = server.request(agent.instanceId, "slow", {}, 5000);
    await agent.close();
    await expect(pending).rejects.toMatchObject({ code: "AGENT_DISCONNECTED" });
    expect(server.instances()).toEqual([]);
  });

  it("buffers events and emits them", async () => {
    const { server, port } = await start();
    const agent = await connectFakeAgent(port, { token: TOKEN });
    const seen = new Promise((res) => server.once("event", (_id, ev) => res(ev)));
    agent.emit("chat", { text: "hi", kind: "chat" });
    await expect(seen).resolves.toMatchObject({ type: "chat", data: { text: "hi" } });
    expect(server.events(agent.instanceId)).toHaveLength(1);
  });
});

describe("AgentServer resolve", () => {
  it("throws NO_INSTANCE when nothing is connected", async () => {
    const { server } = await start();
    expect(() => server.resolve("client")).toThrow(expect.objectContaining({ code: "NO_INSTANCE" }));
  });

  it("auto-selects a single instance and requires a selector for several", async () => {
    const { server, port } = await start();
    await connectFakeAgent(port, { token: TOKEN, name: "Alice" });
    expect(server.resolve("client").name).toBe("Alice");
    await connectFakeAgent(port, { token: TOKEN, name: "Bob" });
    expect(() => server.resolve("client")).toThrow(expect.objectContaining({ code: "AMBIGUOUS_INSTANCE" }));
    expect(server.resolve("client", "bob").name).toBe("Bob");
    expect(server.resolve("client", "client-1").name).toBe("Alice");
  });
});

describe("AgentServer listen", () => {
  it("falls back to a random port when the preferred one is taken", async () => {
    const blocker = createServer();
    const taken = await new Promise<number>((res) => blocker.listen(0, "127.0.0.1", () => res((blocker.address() as { port: number }).port)));
    const { port } = await start({ port: taken });
    expect(port).not.toBe(taken);
    blocker.close();
  });
});
```

- [ ] **Step 5: Run them to verify they fail**

Run: `cd hub && npx vitest run test/agents.test.ts`
Expected: FAIL, with "Cannot find module '../src/agents.js'".

- [ ] **Step 6: Implement AgentServer**

`hub/src/agents.ts`:
```ts
import { EventEmitter } from "node:events";
import type { AddressInfo } from "node:net";
import { WebSocketServer, type WebSocket } from "ws";
import { tokensEqual } from "./config.js";
import { CraftwireError } from "./errors.js";
import { type AgentEvent, EventNotification, HelloRequest, RpcResponse } from "./protocol.js";
import { RingBuffer } from "./ringbuffer.js";
import { PROTOCOL_VERSION } from "./version.js";

export type AgentKind = "client" | "server";

export interface InstanceInfo {
  id: string;
  kind: AgentKind;
  name: string;
  agentVersion: string;
  mcVersion: string;
  connectedAt: number;
}

interface Pending {
  resolve: (v: unknown) => void;
  reject: (e: Error) => void;
  timer: NodeJS.Timeout;
}

interface Instance {
  info: InstanceInfo;
  socket: WebSocket;
  pending: Map<number, Pending>;
  events: RingBuffer<AgentEvent>;
  nextId: number;
}

export interface AgentServerOptions {
  token: string;
  port: number;
  host?: string;
  requestTimeoutMs?: number;
  handshakeTimeoutMs?: number;
  bufferSize?: number;
}

const CLOSE_UNAUTHORIZED = 4001;
const CLOSE_PROTOCOL = 4002;
const CLOSE_BAD_HANDSHAKE = 4003;

export class AgentServer extends EventEmitter {
  private wss?: WebSocketServer;
  private readonly live = new Map<string, Instance>();
  private readonly counters: Record<AgentKind, number> = { client: 0, server: 0 };

  constructor(private readonly opts: AgentServerOptions) {
    super();
  }

  async listen(): Promise<number> {
    try {
      return await this.listenOn(this.opts.port);
    } catch (e) {
      if ((e as NodeJS.ErrnoException).code === "EADDRINUSE") return this.listenOn(0);
      throw e;
    }
  }

  private listenOn(port: number): Promise<number> {
    return new Promise((resolve, reject) => {
      const wss = new WebSocketServer({
        host: this.opts.host ?? "127.0.0.1",
        port,
        // Browsers always send Origin; agents never do. Refusing it blocks drive-by localhost attacks.
        verifyClient: (info) => !info.origin,
      });
      wss.once("error", reject);
      wss.once("listening", () => {
        wss.off("error", reject);
        wss.on("error", () => {});
        wss.on("connection", (socket) => this.onConnection(socket));
        this.wss = wss;
        resolve((wss.address() as AddressInfo).port);
      });
    });
  }

  private onConnection(socket: WebSocket): void {
    const timer = setTimeout(() => socket.close(CLOSE_BAD_HANDSHAKE, "handshake timeout"), this.opts.handshakeTimeoutMs ?? 5000);
    socket.on("error", () => {});
    socket.once("message", (raw) => {
      clearTimeout(timer);
      let parsed: unknown;
      try {
        parsed = JSON.parse(String(raw));
      } catch {
        socket.close(CLOSE_BAD_HANDSHAKE, "invalid JSON");
        return;
      }
      const hello = HelloRequest.safeParse(parsed);
      if (!hello.success) {
        socket.close(CLOSE_BAD_HANDSHAKE, "first message must be hello");
        return;
      }
      const { id, params } = hello.data;
      const fail = (code: string, message: string, hint: string, closeCode: number) => {
        socket.send(JSON.stringify({ jsonrpc: "2.0", id, error: { code: -32001, message, data: { code, hint } } }));
        socket.close(closeCode, code);
      };
      if (!tokensEqual(params.token, this.opts.token)) {
        fail("UNAUTHORIZED", "Invalid token", "The agent read a stale hub.json; it will retry automatically.", CLOSE_UNAUTHORIZED);
        return;
      }
      if (params.protocolVersion !== PROTOCOL_VERSION) {
        const older = params.protocolVersion < PROTOCOL_VERSION ? "agent" : "hub";
        fail(
          "PROTOCOL_MISMATCH",
          `Agent speaks protocol ${params.protocolVersion}, hub speaks ${PROTOCOL_VERSION}`,
          older === "agent" ? "Update the Craftwire agent (mod/plugin)." : "Update the craftwire hub (npm).",
          CLOSE_PROTOCOL,
        );
        return;
      }
      this.counters[params.agentKind] += 1;
      const info: InstanceInfo = {
        id: `${params.agentKind}-${this.counters[params.agentKind]}`,
        kind: params.agentKind,
        name: params.instanceName,
        agentVersion: params.agentVersion,
        mcVersion: params.mcVersion,
        connectedAt: Date.now(),
      };
      const inst: Instance = { info, socket, pending: new Map(), events: new RingBuffer(this.opts.bufferSize ?? 5000), nextId: 1 };
      this.live.set(info.id, inst);
      socket.on("message", (data) => this.onMessage(inst, String(data)));
      socket.on("close", () => this.onClose(inst));
      socket.send(JSON.stringify({ jsonrpc: "2.0", id, result: { instanceId: info.id } }));
      this.emit("connected", info);
    });
  }

  private onMessage(inst: Instance, text: string): void {
    let msg: unknown;
    try {
      msg = JSON.parse(text);
    } catch {
      return;
    }
    const ev = EventNotification.safeParse(msg);
    if (ev.success) {
      inst.events.push(ev.data.params);
      this.emit("event", inst.info.id, ev.data.params);
      return;
    }
    const res = RpcResponse.safeParse(msg);
    if (!res.success || typeof res.data.id !== "number") return;
    const p = inst.pending.get(res.data.id);
    if (!p) return;
    inst.pending.delete(res.data.id);
    clearTimeout(p.timer);
    if ("error" in res.data) {
      const { message, data } = res.data.error;
      p.reject(new CraftwireError(data?.code ?? "INTERNAL", message, data?.hint));
    } else {
      p.resolve(res.data.result);
    }
  }

  private onClose(inst: Instance): void {
    this.live.delete(inst.info.id);
    for (const p of inst.pending.values()) {
      clearTimeout(p.timer);
      p.reject(new CraftwireError("AGENT_DISCONNECTED", `${inst.info.id} disconnected`, "Retry with the same operationId once the agent reconnects (list_instances)."));
    }
    inst.pending.clear();
    this.emit("disconnected", inst.info);
  }

  instances(): InstanceInfo[] {
    return [...this.live.values()].map((i) => i.info);
  }

  resolve(kind: AgentKind, selector?: string): InstanceInfo {
    const ofKind = this.instances().filter((i) => i.kind === kind);
    const label = kind === "client" ? "Minecraft client (Craftwire Agent mod)" : "Paper server (Craftwire plugin)";
    if (selector) {
      const s = selector.toLowerCase();
      const hit = ofKind.find((i) => i.id === selector) ?? ofKind.find((i) => i.name.toLowerCase() === s);
      if (!hit) throw new CraftwireError("NO_INSTANCE", `No ${kind} instance matches "${selector}"`, "Call list_instances to see connected instances.");
      return hit;
    }
    if (ofKind.length === 0) throw new CraftwireError("NO_INSTANCE", `No ${label} is connected`, `Start Minecraft with the Craftwire ${kind === "client" ? "Agent mod" : "plugin"} installed; it connects automatically.`);
    if (ofKind.length > 1) throw new CraftwireError("AMBIGUOUS_INSTANCE", `${ofKind.length} ${kind} instances are connected`, `Pass instance: one of ${ofKind.map((i) => `${i.id} (${i.name})`).join(", ")}.`);
    return ofKind[0]!;
  }

  request(instanceId: string, method: string, params: Record<string, unknown>, timeoutMs?: number): Promise<unknown> {
    const inst = this.live.get(instanceId);
    if (!inst) return Promise.reject(new CraftwireError("AGENT_DISCONNECTED", `${instanceId} is not connected`, "Call list_instances."));
    const id = inst.nextId++;
    const ms = timeoutMs ?? this.opts.requestTimeoutMs ?? 30000;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        inst.pending.delete(id);
        reject(new CraftwireError("TIMEOUT", `${method} did not answer within ${ms} ms`, "The game may be frozen or loading; check with list_instances and retry."));
      }, ms);
      inst.pending.set(id, { resolve, reject, timer });
      inst.socket.send(JSON.stringify({ jsonrpc: "2.0", id, method, params }));
    });
  }

  events(instanceId: string): AgentEvent[] {
    return this.live.get(instanceId)?.events.toArray() ?? [];
  }

  async close(): Promise<void> {
    for (const inst of this.live.values()) inst.socket.terminate();
    await new Promise<void>((res) => (this.wss ? this.wss.close(() => res()) : res()));
  }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `cd hub && npx vitest run test/agents.test.ts test/ringbuffer.test.ts && npm run typecheck`
Expected: PASS (all tests). Typecheck is clean. The Origin test resolves `-1`, because the upgrade is refused with HTTP 401.

- [ ] **Step 8: Commit**

```bash
git add hub/src/ringbuffer.ts hub/src/agents.ts hub/test/helpers hub/test/ringbuffer.test.ts hub/test/agents.test.ts
git commit -m "feat(hub): agent WebSocket server with token handshake, routing and event buffer"
```

---

### Task 4: Operations, audit log, tool registry and hub tools

**Files:**
- Create: `hub/src/operations.ts`, `hub/src/audit.ts`, `hub/src/tools/registry.ts`, `hub/src/tools/hub-tools.ts`, `hub/src/server.ts`
- Create: `hub/test/helpers/hub.ts`
- Test: `hub/test/operations.test.ts`, `hub/test/hub-tools.test.ts`

**Interfaces:**
- Consumes: `AgentServer`, `InstanceInfo` (Task 3); `CraftwireError`, `toToolError` (Task 2); `HUB_VERSION` (Task 1); `AgentEvent` (Task 1).
- Produces:
  ```ts
  class OperationTracker { constructor(ttlMs?: number, now?: () => number);
    run<T>(operationId: string | undefined, fn: () => Promise<T>): Promise<T>;
    status(operationId: string): { status: "unknown" | "running" | "done" | "failed"; result?: unknown; error?: unknown; updatedAt?: number } }
  class AuditLog { constructor(dir: string, now?: () => Date); write(entry: { tool: string; args: unknown; ok: boolean; ms: number; error?: unknown }): void }
  interface ToolContext { agents: AgentServer; ops: OperationTracker; audit: AuditLog }
  function defineTool<S extends z.ZodRawShape>(server: McpServer, ctx: ToolContext, name: string, description: string, shape: S,
    run: (args: z.infer<z.ZodObject<S>>, ctx: ToolContext) => Promise<CallToolResult>): void
  function ok(data: unknown): CallToolResult
  const targetArgs: { instance: ZodOptional<ZodString>; operationId: ZodOptional<ZodString> }
  function registerHubTools(server: McpServer, ctx: ToolContext): void
  function createCraftwireServer(ctx: ToolContext): McpServer
  ```
- Test helper `startHub()` returns `{ agents, port, token, home, client, call(name, args?) → Promise<CallToolResult>, close() }`. Also `json(result)` parses the first text item.
- `OperationTracker` semantics: `done` returns the cached result. `running` returns the same in-flight promise. `failed` **re-runs**, because a retry after `AGENT_DISCONNECTED` must execute again. Entries expire after `ttlMs`.

- [ ] **Step 1: Write the failing OperationTracker test**

`hub/test/operations.test.ts`:
```ts
import { describe, expect, it } from "vitest";
import { CraftwireError } from "../src/errors.js";
import { OperationTracker } from "../src/operations.js";

describe("OperationTracker", () => {
  it("runs without an id every time", async () => {
    const ops = new OperationTracker();
    let n = 0;
    await ops.run(undefined, async () => ++n);
    await ops.run(undefined, async () => ++n);
    expect(n).toBe(2);
  });

  it("returns the cached result for a repeated id", async () => {
    const ops = new OperationTracker();
    let n = 0;
    expect(await ops.run("op1", async () => ++n)).toBe(1);
    expect(await ops.run("op1", async () => ++n)).toBe(1);
    expect(ops.status("op1")).toMatchObject({ status: "done", result: 1 });
  });

  it("shares an in-flight promise", async () => {
    const ops = new OperationTracker();
    let n = 0;
    const slow = () => new Promise<number>((r) => setTimeout(() => r(++n), 20));
    const [a, b] = await Promise.all([ops.run("op", slow), ops.run("op", slow)]);
    expect([a, b, n]).toEqual([1, 1, 1]);
  });

  it("re-runs after a failure and records the error", async () => {
    const ops = new OperationTracker();
    await expect(ops.run("op", async () => { throw new CraftwireError("AGENT_DISCONNECTED", "gone"); })).rejects.toThrow("gone");
    expect(ops.status("op")).toMatchObject({ status: "failed", error: { code: "AGENT_DISCONNECTED" } });
    expect(await ops.run("op", async () => 42)).toBe(42);
  });

  it("forgets entries after the TTL", async () => {
    let t = 0;
    const ops = new OperationTracker(1000, () => t);
    await ops.run("op", async () => 1);
    t = 1001;
    expect(ops.status("op")).toEqual({ status: "unknown" });
  });
});
```

- [ ] **Step 2: Run it to verify it fails, then implement**

Run: `cd hub && npx vitest run test/operations.test.ts`
Expected: FAIL (module not found).

`hub/src/operations.ts`:
```ts
import { CraftwireError } from "./errors.js";

type Status = "running" | "done" | "failed";

interface Entry {
  status: Status;
  result?: unknown;
  error?: unknown;
  updatedAt: number;
  promise?: Promise<unknown>;
}

export class OperationTracker {
  private readonly ops = new Map<string, Entry>();

  constructor(
    private readonly ttlMs = 300_000,
    private readonly now: () => number = Date.now,
  ) {}

  async run<T>(operationId: string | undefined, fn: () => Promise<T>): Promise<T> {
    if (!operationId) return fn();
    this.prune();
    const existing = this.ops.get(operationId);
    if (existing?.status === "done") return existing.result as T;
    if (existing?.status === "running" && existing.promise) return existing.promise as Promise<T>;

    const promise = fn();
    this.ops.set(operationId, { status: "running", updatedAt: this.now(), promise });
    try {
      const result = await promise;
      this.ops.set(operationId, { status: "done", result, updatedAt: this.now() });
      return result;
    } catch (e) {
      const error = e instanceof CraftwireError ? e.toJSON() : { code: "INTERNAL", message: e instanceof Error ? e.message : String(e) };
      this.ops.set(operationId, { status: "failed", error, updatedAt: this.now() });
      throw e;
    }
  }

  status(operationId: string): { status: "unknown" | Status; result?: unknown; error?: unknown; updatedAt?: number } {
    this.prune();
    const e = this.ops.get(operationId);
    if (!e) return { status: "unknown" };
    const { promise: _promise, ...rest } = e;
    return rest;
  }

  private prune(): void {
    const now = this.now();
    for (const [id, e] of this.ops) if (e.status !== "running" && now - e.updatedAt > this.ttlMs) this.ops.delete(id);
  }
}
```

Run: `cd hub && npx vitest run test/operations.test.ts`
Expected: PASS (5 tests).

- [ ] **Step 3: Implement the audit log and tool registry**

`hub/src/audit.ts`:
```ts
import { appendFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";

const MAX_ARGS_CHARS = 4000;

export class AuditLog {
  constructor(
    private readonly dir: string,
    private readonly now: () => Date = () => new Date(),
  ) {}

  write(entry: { tool: string; args: unknown; ok: boolean; ms: number; error?: unknown }): void {
    try {
      mkdirSync(this.dir, { recursive: true });
      const time = this.now();
      const file = join(this.dir, `audit-${time.toISOString().slice(0, 10)}.jsonl`);
      appendFileSync(file, `${JSON.stringify({ time: time.toISOString(), ...entry, args: clip(entry.args) })}\n`);
    } catch {
      // auditing must never break a tool call
    }
  }
}

function clip(value: unknown): unknown {
  const text = JSON.stringify(value) ?? "null";
  return text.length <= MAX_ARGS_CHARS ? value : { truncated: true, preview: text.slice(0, MAX_ARGS_CHARS) };
}
```

`hub/src/tools/registry.ts`:
```ts
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";
import type { AgentServer } from "../agents.js";
import type { AuditLog } from "../audit.js";
import { CraftwireError, toToolError } from "../errors.js";
import type { OperationTracker } from "../operations.js";

export interface ToolContext {
  agents: AgentServer;
  ops: OperationTracker;
  audit: AuditLog;
}

export const targetArgs = {
  instance: z.string().optional().describe("Instance id or name from list_instances. Optional when exactly one matching instance is connected."),
  operationId: z.string().optional().describe("Idempotency key. Retrying with the same id returns the first result instead of executing again."),
};

export function ok(data: unknown): CallToolResult {
  return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
}

export function defineTool<S extends z.ZodRawShape>(
  server: McpServer,
  ctx: ToolContext,
  name: string,
  description: string,
  shape: S,
  run: (args: z.infer<z.ZodObject<S>>, ctx: ToolContext) => Promise<CallToolResult>,
): void {
  const handler = async (args: unknown): Promise<CallToolResult> => {
    const started = Date.now();
    try {
      const result = await run(args as z.infer<z.ZodObject<S>>, ctx);
      ctx.audit.write({ tool: name, args, ok: !result.isError, ms: Date.now() - started });
      return result;
    } catch (e) {
      ctx.audit.write({ tool: name, args, ok: false, ms: Date.now() - started, error: e instanceof CraftwireError ? e.toJSON() : String(e) });
      return toToolError(e);
    }
  };
  // The SDK's generic overloads do not infer through our wrapper; the shape is still validated by the SDK.
  server.registerTool(name, { description, inputSchema: shape }, handler as never);
}
```

- [ ] **Step 4: Write the failing hub-tools tests and helper**

`hub/test/helpers/hub.ts`:
```ts
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { AgentServer } from "../../src/agents.js";
import { AuditLog } from "../../src/audit.js";
import { OperationTracker } from "../../src/operations.js";
import { createCraftwireServer } from "../../src/server.js";

export const TOKEN = "a".repeat(64);

export async function startHub() {
  const home = mkdtempSync(join(tmpdir(), "cw-hub-"));
  const agents = new AgentServer({ token: TOKEN, port: 0, requestTimeoutMs: 2000 });
  const port = await agents.listen();
  const server = createCraftwireServer({ agents, ops: new OperationTracker(), audit: new AuditLog(join(home, "logs")) });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  await server.connect(serverTransport);
  const client = new Client({ name: "test", version: "0.0.0" });
  await client.connect(clientTransport);
  const call = (name: string, args: Record<string, unknown> = {}) =>
    client.callTool({ name, arguments: args }) as Promise<CallToolResult>;
  return {
    agents, port, token: TOKEN, home, client, call,
    close: async () => { await client.close(); await server.close(); await agents.close(); },
  };
}

export const json = (r: CallToolResult) => JSON.parse((r.content[0] as { text: string }).text);
```

`hub/test/hub-tools.test.ts`:
```ts
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { connectFakeAgent } from "./helpers/fakeAgent.js";
import { json, startHub, TOKEN } from "./helpers/hub.js";

let hub: Awaited<ReturnType<typeof startHub>> | undefined;
afterEach(async () => { await hub?.close(); hub = undefined; });

describe("hub tools", () => {
  it("list_instances is empty, then shows a connected client", async () => {
    hub = await startHub();
    expect(json(await hub.call("list_instances"))).toEqual({ instances: [], hint: expect.any(String) });
    await connectFakeAgent(hub.port, { token: TOKEN, name: "Sirac" });
    expect(json(await hub.call("list_instances")).instances).toMatchObject([{ id: "client-1", name: "Sirac" }]);
  });

  it("get_request_status reports unknown ids", async () => {
    hub = await startHub();
    expect(json(await hub.call("get_request_status", { operationId: "nope" }))).toEqual({ status: "unknown" });
  });

  it("wait_for resolves on a matching event that arrives later", async () => {
    hub = await startHub();
    const agent = await connectFakeAgent(hub.port, { token: TOKEN });
    const pending = hub.call("wait_for", { condition: "chat_match", pattern: "build complete", timeoutMs: 3000 });
    setTimeout(() => { agent.emit("chat", { text: "irrelevant", kind: "game" }); agent.emit("chat", { text: "Your build complete!", kind: "game" }); }, 50);
    const res = json(await pending);
    expect(res).toMatchObject({ matched: true, instance: "client-1", event: { type: "chat", data: { text: "Your build complete!" } } });
  });

  it("wait_for screen_open ignores close events", async () => {
    hub = await startHub();
    const agent = await connectFakeAgent(hub.port, { token: TOKEN });
    const pending = hub.call("wait_for", { condition: "screen_open", timeoutMs: 3000 });
    setTimeout(() => { agent.emit("screen", { open: false, title: "", type: "" }); agent.emit("screen", { open: true, title: "Crew", type: "ContainerScreen" }); }, 50);
    expect(json(await pending).event.data.title).toBe("Crew");
  });

  it("wait_for times out with TIMEOUT", async () => {
    hub = await startHub();
    await connectFakeAgent(hub.port, { token: TOKEN });
    const res = await hub.call("wait_for", { condition: "chat_match", pattern: "never", timeoutMs: 100 });
    expect(res.isError).toBe(true);
    expect(json(res).code).toBe("TIMEOUT");
  });

  it("wait_for rejects an invalid regex", async () => {
    hub = await startHub();
    const res = await hub.call("wait_for", { condition: "chat_match", pattern: "(", timeoutMs: 100 });
    expect(json(res).code).toBe("INVALID_PARAMS");
  });

  it("writes an audit line per call", async () => {
    hub = await startHub();
    await hub.call("list_instances");
    const dir = join(hub.home, "logs");
    const [file] = readdirSync(dir);
    const line = JSON.parse(readFileSync(join(dir, file!), "utf8").trim().split("\n")[0]!);
    expect(line).toMatchObject({ tool: "list_instances", ok: true });
  });
});
```

Run: `cd hub && npx vitest run test/hub-tools.test.ts`
Expected: FAIL (cannot find `../../src/server.js`).

- [ ] **Step 5: Implement hub tools and the server factory**

`hub/src/tools/hub-tools.ts`:
```ts
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import type { AgentEvent } from "../protocol.js";
import { CraftwireError } from "../errors.js";
import { defineTool, ok, type ToolContext } from "./registry.js";

const EVENT_TYPE = {
  chat_match: "chat",
  hud_match: "hud",
  screen_open: "screen",
  screen_closed: "screen",
  log_match: "log",
  player_join: "player",
} as const;
type Condition = keyof typeof EVENT_TYPE;

function matches(condition: Condition, pattern: RegExp | undefined, ev: AgentEvent): boolean {
  if (ev.type !== EVENT_TYPE[condition]) return false;
  const d = ev.data;
  if (condition === "screen_open" && d.open !== true) return false;
  if (condition === "screen_closed" && d.open !== false) return false;
  if (condition === "player_join" && d.action !== "join") return false;
  if (!pattern) return true;
  return pattern.test(String(d.text ?? d.title ?? d.message ?? d.name ?? ""));
}

export function registerHubTools(server: McpServer, ctx: ToolContext): void {
  defineTool(server, ctx, "list_instances",
    "List connected Minecraft clients and servers (id, name, versions). Call this first; other tools take `instance` from here.",
    {},
    async (_args, c) => {
      const instances = c.agents.instances();
      return ok(instances.length
        ? { instances }
        : { instances, hint: "Nothing is connected. Start Minecraft with the Craftwire Agent mod (and/or a Paper server with the Craftwire plugin); they connect to this hub automatically." });
    });

  defineTool(server, ctx, "get_request_status",
    "Status of a request sent with an operationId: unknown, running, done (with result) or failed (with error). Results are kept for 5 minutes.",
    { operationId: z.string() },
    async ({ operationId }, c) => ok(c.ops.status(operationId)));

  defineTool(server, ctx, "wait_for",
    "Block until an event happens or the timeout expires. Conditions: chat_match, hud_match (actionbar), screen_open, screen_closed, log_match, player_join. `pattern` is a case-insensitive regex tested against the event text/title. Only events arriving after the call count.",
    {
      condition: z.enum(["chat_match", "hud_match", "screen_open", "screen_closed", "log_match", "player_join"]),
      pattern: z.string().optional(),
      instance: z.string().optional().describe("Only accept events from this instance (id or name)."),
      timeoutMs: z.number().int().min(10).max(300_000).default(30_000),
    },
    async ({ condition, pattern, instance, timeoutMs }, c) => {
      let regex: RegExp | undefined;
      try {
        regex = pattern === undefined ? undefined : new RegExp(pattern, "i");
      } catch (e) {
        throw new CraftwireError("INVALID_PARAMS", `Invalid regex: ${(e as Error).message}`, "Escape special characters such as ( [ . * with a backslash.");
      }
      const only = instance
        ? c.agents.instances().find((i) => i.id === instance || i.name.toLowerCase() === instance.toLowerCase())?.id
        : undefined;
      if (instance && !only) throw new CraftwireError("NO_INSTANCE", `No instance matches "${instance}"`, "Call list_instances.");

      const hit = await new Promise<{ instanceId: string; ev: AgentEvent } | undefined>((resolve) => {
        const onEvent = (instanceId: string, ev: AgentEvent) => {
          if (only && instanceId !== only) return;
          if (!matches(condition, regex, ev)) return;
          cleanup();
          resolve({ instanceId, ev });
        };
        const timer = setTimeout(() => { cleanup(); resolve(undefined); }, timeoutMs);
        const cleanup = () => { clearTimeout(timer); c.agents.off("event", onEvent); };
        c.agents.on("event", onEvent);
      });
      if (!hit) throw new CraftwireError("TIMEOUT", `No ${condition} event within ${timeoutMs} ms`, "Check chat/hud_read/gui_read to see what actually happened, then retry or widen the pattern.");
      return ok({ matched: true, instance: hit.instanceId, event: hit.ev });
    });
}
```

`hub/src/server.ts`:
```ts
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { registerHubTools } from "./tools/hub-tools.js";
import type { ToolContext } from "./tools/registry.js";
import { HUB_VERSION } from "./version.js";

const INSTRUCTIONS = [
  "Craftwire lets you see and drive Minecraft.",
  "Start with list_instances. Client tools (screenshot, camera, gui_*, input, chat, hud_read, player_state, client_settings) act on a game client.",
  "After an action that opens a menu (e.g. chat {action:'command'}), call wait_for {condition:'screen_open'} before gui_read.",
  "Errors carry a `hint` with the next step. Pass operationId on actions you might retry.",
].join(" ");

export function createCraftwireServer(ctx: ToolContext): McpServer {
  const server = new McpServer({ name: "craftwire", version: HUB_VERSION }, { instructions: INSTRUCTIONS });
  registerHubTools(server, ctx);
  return server;
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd hub && npx vitest run && npm run typecheck`
Expected: PASS (every test so far). Typecheck is clean.

- [ ] **Step 7: Commit**

```bash
git add hub/src/operations.ts hub/src/audit.ts hub/src/tools hub/src/server.ts hub/test/helpers/hub.ts hub/test/operations.test.ts hub/test/hub-tools.test.ts
git commit -m "feat(hub): operation tracking, audit log, list_instances/wait_for/get_request_status"
```

---

### Task 5: Client tools in the hub

**Files:**
- Create: `hub/src/tools/client-tools.ts`
- Modify: `hub/src/server.ts` (register client tools)
- Test: `hub/test/client-tools.test.ts`

**Interfaces:**
- Consumes: `defineTool`, `ok`, `targetArgs`, `ToolContext` (Task 4); `AgentServer.resolve/request/events` (Task 3).
- Produces: `registerClientTools(server, ctx)`, which registers these tools:

| Tool | Agent method | Notes |
|---|---|---|
| `player_state` | `player.state` | |
| `hud_read` | `hud.read` | |
| `gui_read` | `gui.read` | |
| `gui_action` | `gui.action` | |
| `input` | `input` | |
| `camera` | `camera` | |
| `client_settings` | `client.settings` | |
| `screenshot` | `screenshot` | 20 s timeout; returns MCP `image` content plus a text meta item; `savePath` is made absolute against the hub's cwd before sending |
| `chat` | `chat.send` / none | `action: send \| command` forwards `{text, command}`; `action: read` filters the hub's event buffer (`chat` and `hud` events) |

- Every forwarded request passes `operationId` through to the agent params when it is given, and is wrapped in `ctx.ops.run(operationId, …)`.

- [ ] **Step 1: Write the failing tests**

`hub/test/client-tools.test.ts`:
```ts
import { resolve } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { connectFakeAgent } from "./helpers/fakeAgent.js";
import { json, startHub, TOKEN } from "./helpers/hub.js";

let hub: Awaited<ReturnType<typeof startHub>> | undefined;
afterEach(async () => { await hub?.close(); hub = undefined; });

async function withAgent() {
  hub = await startHub();
  const agent = await connectFakeAgent(hub.port, { token: TOKEN, name: "Sirac" });
  return { hub, agent };
}

describe("client tools", () => {
  const forwards: Array<[string, string, Record<string, unknown>]> = [
    ["player_state", "player.state", {}],
    ["hud_read", "hud.read", {}],
    ["gui_read", "gui.read", {}],
    ["gui_action", "gui.action", { action: "click", slot: 3 }],
    ["input", "input", { keys: ["jump"], mode: "press" }],
    ["camera", "camera", { action: "set", x: 1, y: 2, z: 3, yaw: 90, pitch: 10 }],
    ["client_settings", "client.settings", { guiScale: 3 }],
  ];

  for (const [tool, method, args] of forwards) {
    it(`${tool} forwards to ${method}`, async () => {
      const { hub, agent } = await withAgent();
      agent.onRequest(method, (p) => ({ got: p }));
      const res = await hub.call(tool, args);
      expect(res.isError).toBeFalsy();
      expect(json(res)).toEqual({ got: expect.objectContaining(args) });
    });
  }

  it("returns NO_INSTANCE when no client is connected", async () => {
    hub = await startHub();
    const res = await hub.call("player_state");
    expect(res.isError).toBe(true);
    expect(json(res)).toMatchObject({ code: "NO_INSTANCE", hint: expect.stringContaining("Craftwire Agent") });
  });

  it("surfaces agent errors with their hint", async () => {
    const { hub, agent } = await withAgent();
    agent.onRequest("gui.read", () => { throw Object.assign(new Error("No screen is open"), { code: "NO_SCREEN_OPEN", hint: "open a menu" }); });
    expect(json(await hub.call("gui_read"))).toEqual({ code: "NO_SCREEN_OPEN", message: "No screen is open", hint: "open a menu" });
  });

  it("deduplicates retries that share an operationId", async () => {
    const { hub, agent } = await withAgent();
    let calls = 0;
    agent.onRequest("gui.action", (p) => ({ calls: ++calls, operationId: p.operationId }));
    await hub.call("gui_action", { action: "click", slot: 1, operationId: "op-1" });
    const second = json(await hub.call("gui_action", { action: "click", slot: 1, operationId: "op-1" }));
    expect(second).toEqual({ calls: 1, operationId: "op-1" });
  });

  it("screenshot returns an image item and absolutises savePath", async () => {
    const { hub, agent } = await withAgent();
    let seen: Record<string, unknown> = {};
    agent.onRequest("screenshot", (p) => {
      seen = p;
      return { mime: "image/png", data: "iVBORw0KGgo=", width: 2, height: 1, fullWidth: 4, fullHeight: 2, savedPath: p.savePath };
    });
    const res = await hub.call("screenshot", { hud: false, savePath: "shots/a.png" });
    expect(res.content[0]).toEqual({ type: "image", data: "iVBORw0KGgo=", mimeType: "image/png" });
    expect(JSON.parse((res.content[1] as { text: string }).text)).toMatchObject({ width: 2, fullWidth: 4, savedPath: resolve("shots/a.png") });
    expect(seen).toMatchObject({ hud: false, maxSize: 1600, savePath: resolve("shots/a.png") });
  });

  it("chat send and command forward to chat.send", async () => {
    const { hub, agent } = await withAgent();
    const got: unknown[] = [];
    agent.onRequest("chat.send", (p) => { got.push(p); return { sent: true }; });
    await hub.call("chat", { action: "send", text: "hello" });
    await hub.call("chat", { action: "command", text: "/builders crew" });
    expect(got).toEqual([{ text: "hello", command: false }, { text: "/builders crew", command: true }]);
  });

  it("chat read filters buffered chat and actionbar events", async () => {
    const { hub, agent } = await withAgent();
    agent.emit("chat", { text: "Welcome!", kind: "game" });
    agent.emit("hud", { element: "actionbar", text: "Preview: house" });
    agent.emit("screen", { open: true, title: "x", type: "y" });
    await new Promise((r) => setTimeout(r, 50));
    const all = json(await hub.call("chat", { action: "read" }));
    expect(all.messages.map((m: { text: string }) => m.text)).toEqual(["Welcome!", "Preview: house"]);
    const filtered = json(await hub.call("chat", { action: "read", contains: "preview" }));
    expect(filtered.messages).toHaveLength(1);
  });

  it("chat send without text is INVALID_PARAMS", async () => {
    const { hub } = await withAgent();
    expect(json(await hub.call("chat", { action: "send" })).code).toBe("INVALID_PARAMS");
  });
});
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd hub && npx vitest run test/client-tools.test.ts`
Expected: FAIL, with "Tool player_state not found" or similar.

- [ ] **Step 3: Implement the client tools**

`hub/src/tools/client-tools.ts`:
```ts
import { resolve as resolvePath } from "node:path";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";
import { CraftwireError } from "../errors.js";
import { defineTool, ok, targetArgs, type ToolContext } from "./registry.js";

const vec3 = { x: z.number(), y: z.number(), z: z.number() };

async function forward(c: ToolContext, method: string, args: Record<string, unknown>, timeoutMs?: number): Promise<unknown> {
  const { instance, operationId, ...params } = args as { instance?: string; operationId?: string } & Record<string, unknown>;
  const inst = c.agents.resolve("client", instance);
  const payload = operationId ? { ...params, operationId } : params;
  return c.ops.run(operationId, () => c.agents.request(inst.id, method, payload, timeoutMs));
}

const fwd = (method: string, timeoutMs?: number) => async (args: Record<string, unknown>, c: ToolContext) =>
  ok(await forward(c, method, args, timeoutMs));

export function registerClientTools(server: McpServer, ctx: ToolContext): void {
  defineTool(server, ctx, "player_state",
    "Local player snapshot: position, rotation, health, game mode, dimension, selected hotbar slot, inventory, and the block/entity under the crosshair.",
    { ...targetArgs }, fwd("player.state"));

  defineTool(server, ctx, "hud_read",
    "Read HUD state: bossbars (name, progress, color), actionbar, title/subtitle, scoreboard sidebar and tab list.",
    { ...targetArgs }, fwd("hud.read"));

  defineTool(server, ctx, "gui_read",
    "Describe the open screen: title, type, slots (slot, item id, count, name, lore, enchanted, container), hovered slot tooltip, and widgets (buttons/text fields with index and text). Returns {open:false} when no screen is open.",
    { ...targetArgs }, fwd("gui.read"));

  defineTool(server, ctx, "gui_action",
    "Interact with the open screen. hover/click/right_click/shift_click take `slot` (from gui_read). drag moves `slot` → `toSlot`. click_widget takes `widget` (index or visible text). type sends `text` to the focused field (or `widget`). close closes the screen. Returns the fresh gui_read state.",
    {
      ...targetArgs,
      action: z.enum(["hover", "click", "right_click", "shift_click", "drag", "click_widget", "type", "close"]),
      slot: z.number().int().min(0).optional(),
      toSlot: z.number().int().min(0).optional(),
      widget: z.string().optional(),
      text: z.string().max(256).optional(),
    }, fwd("gui.action"));

  defineTool(server, ctx, "input",
    "Simulate gameplay input (no screen open). keys: forward, back, left, right, jump, sneak, sprint, attack, use, pick, drop, inventory, swap_hands, chat, command, player_list, perspective, hide_gui, screenshot. mode press (default, optional durationMs) | hold | release. look adds yaw/pitch degrees. hotbar selects slot 1-9.",
    {
      ...targetArgs,
      keys: z.array(z.string()).optional(),
      mode: z.enum(["press", "hold", "release"]).default("press"),
      durationMs: z.number().int().min(0).max(30_000).optional(),
      look: z.object({ yaw: z.number().optional(), pitch: z.number().optional() }).optional(),
      hotbar: z.number().int().min(1).max(9).optional(),
    }, fwd("input"));

  defineTool(server, ctx, "camera",
    "Control the render camera without moving the player (client-side only). set: x,y,z,yaw,pitch. look_at: aim at `target`. frame_area: fit `area` (min/max corners) in view. frame_entity: fit `entity` (UUID or name). freecam_on/freecam_off. reset returns to the player's eyes. Keep the camera within render distance of the player.",
    {
      ...targetArgs,
      action: z.enum(["set", "look_at", "frame_area", "frame_entity", "freecam_on", "freecam_off", "reset"]),
      x: z.number().optional(), y: z.number().optional(), z: z.number().optional(),
      yaw: z.number().optional(), pitch: z.number().min(-90).max(90).optional(),
      target: z.object(vec3).optional(),
      area: z.object({ min: z.object(vec3), max: z.object(vec3) }).optional(),
      entity: z.string().optional(),
      distanceScale: z.number().min(0.2).max(5).default(1),
    }, fwd("camera"));

  defineTool(server, ctx, "client_settings",
    "Adjust rendering settings and return the current values: guiScale (0=auto..8), fov (30-110), renderDistance (2-32), hideHud, windowSize {width,height}. Call with no arguments to just read them.",
    {
      ...targetArgs,
      guiScale: z.number().int().min(0).max(8).optional(),
      fov: z.number().int().min(30).max(110).optional(),
      renderDistance: z.number().int().min(2).max(32).optional(),
      hideHud: z.boolean().optional(),
      windowSize: z.object({ width: z.number().int().min(320), height: z.number().int().min(240) }).optional(),
    }, fwd("client.settings"));

  defineTool(server, ctx, "screenshot",
    "Capture the game frame and return it as an image. hud:false hides the HUD (F1). maxSize is the long edge of the returned image. savePath (relative to the current directory) also writes the full-resolution PNG. camera {x,y,z,yaw,pitch,fov} applies only for this capture.",
    {
      ...targetArgs,
      hud: z.boolean().default(true),
      maxSize: z.number().int().min(256).max(4096).default(1600),
      savePath: z.string().optional(),
      format: z.enum(["auto", "png", "jpeg"]).default("auto"),
      camera: z.object({ ...vec3, yaw: z.number(), pitch: z.number(), fov: z.number().int().min(30).max(110).optional() }).optional(),
    },
    async (args, c): Promise<CallToolResult> => {
      const withAbsPath = args.savePath ? { ...args, savePath: resolvePath(args.savePath) } : args;
      const r = (await forward(c, "screenshot", withAbsPath, 20_000)) as {
        mime: string; data: string; width: number; height: number; fullWidth: number; fullHeight: number; savedPath?: string;
      };
      const meta = { width: r.width, height: r.height, fullWidth: r.fullWidth, fullHeight: r.fullHeight, savedPath: r.savedPath };
      return { content: [{ type: "image", data: r.data, mimeType: r.mime }, { type: "text", text: JSON.stringify(meta) }] };
    });

  defineTool(server, ctx, "chat",
    "send: say `text` in chat. command: run `text` as a command (leading / optional). read: recent chat and actionbar messages from the hub buffer, optionally filtered by `contains` (case-insensitive) and `since` (epoch ms).",
    {
      ...targetArgs,
      action: z.enum(["send", "command", "read"]),
      text: z.string().max(256).optional(),
      contains: z.string().optional(),
      since: z.number().optional(),
      limit: z.number().int().min(1).max(500).default(50),
    },
    async (args, c) => {
      if (args.action === "read") {
        const inst = c.agents.resolve("client", args.instance);
        const needle = args.contains?.toLowerCase();
        const messages = c.agents.events(inst.id)
          .filter((e) => e.type === "chat" || e.type === "hud")
          .filter((e) => args.since === undefined || e.time >= args.since)
          .map((e) => ({ time: e.time, type: e.type, ...e.data }) as { time: number; type: string; text?: string })
          .filter((m) => !needle || String(m.text ?? "").toLowerCase().includes(needle))
          .slice(-args.limit);
        return ok({ messages });
      }
      if (!args.text) throw new CraftwireError("INVALID_PARAMS", "`text` is required for send/command", "Pass text, e.g. {action:'command', text:'/time set noon'}.");
      return ok(await forward(c, "chat.send", { instance: args.instance, operationId: args.operationId, text: args.text, command: args.action === "command" }));
    });
}
```

Modify `hub/src/server.ts`. Add the import and register the client tools after the hub tools:
```ts
import { registerClientTools } from "./tools/client-tools.js";
// …inside createCraftwireServer, after registerHubTools(server, ctx):
  registerClientTools(server, ctx);
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd hub && npx vitest run && npm run typecheck`
Expected: PASS (all suites). Typecheck is clean.

- [ ] **Step 5: Commit**

```bash
git add hub/src/tools/client-tools.ts hub/src/server.ts hub/test/client-tools.test.ts
git commit -m "feat(hub): client tools (screenshot, camera, gui, input, chat, hud, player, settings)"
```

---

### Task 6: CLI entry point and stdio end-to-end test

**Files:**
- Create: `hub/src/cli.ts`
- Test: `hub/test/cli.test.ts`

**Interfaces:**
- Consumes: everything above.
- Produces: the executable `dist/cli.js` (npm bin `craftwire`). Environment: `CRAFTWIRE_HOME` (state directory) and `CRAFTWIRE_PORT` (preferred port, default `47821`; `0` means random). It writes `hub.json` after binding and logs to **stderr only**, because stdout carries MCP.

- [ ] **Step 1: Write the failing end-to-end test**

`hub/test/cli.test.ts`:
```ts
import { execSync } from "node:child_process";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { connectFakeAgent } from "./helpers/fakeAgent.js";

const hubDir = join(__dirname, "..");
const home = mkdtempSync(join(tmpdir(), "cw-cli-"));
let client: Client;

beforeAll(async () => {
  execSync("npm run build", { cwd: hubDir, stdio: "inherit" });
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [join(hubDir, "dist", "cli.js")],
    env: { ...(process.env as Record<string, string>), CRAFTWIRE_HOME: home, CRAFTWIRE_PORT: "0" },
    stderr: "pipe",
  });
  client = new Client({ name: "e2e", version: "0.0.0" });
  await client.connect(transport);
}, 120_000);

afterAll(async () => { await client?.close(); });

describe("craftwire CLI over stdio", () => {
  it("exposes all M1 tools", async () => {
    const names = (await client.listTools()).tools.map((t) => t.name).sort();
    expect(names).toEqual([
      "camera", "chat", "client_settings", "get_request_status", "gui_action", "gui_read",
      "hud_read", "input", "list_instances", "player_state", "screenshot", "wait_for",
    ]);
  });

  it("writes hub.json and accepts an agent using it", async () => {
    const cfg = JSON.parse(readFileSync(join(home, "hub.json"), "utf8"));
    expect(cfg.port).toBeGreaterThan(0);
    expect(cfg.token).toMatch(/^[0-9a-f]{64}$/);
    await connectFakeAgent(cfg.port, { token: cfg.token, name: "E2E" });
    const res = (await client.callTool({ name: "list_instances", arguments: {} })) as CallToolResult;
    expect(JSON.parse((res.content[0] as { text: string }).text).instances[0].name).toBe("E2E");
  });
});
```

Run: `cd hub && npx vitest run test/cli.test.ts`
Expected: FAIL (the build fails, or `dist/cli.js` is missing).

- [ ] **Step 2: Implement the CLI**

`hub/src/cli.ts`:
```ts
#!/usr/bin/env node
import { join } from "node:path";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { AgentServer } from "./agents.js";
import { AuditLog } from "./audit.js";
import { craftwireHome, loadOrCreateToken, writeHubConfig } from "./config.js";
import { OperationTracker } from "./operations.js";
import { createCraftwireServer } from "./server.js";
import { HUB_VERSION } from "./version.js";

const log = (msg: string) => process.stderr.write(`[craftwire] ${msg}\n`);

async function main(): Promise<void> {
  const home = craftwireHome();
  const token = loadOrCreateToken(home);
  const agents = new AgentServer({ token, port: Number(process.env.CRAFTWIRE_PORT ?? 47821) });
  const port = await agents.listen();
  writeHubConfig({ port, token }, home);
  agents.on("connected", (i) => log(`${i.id} connected (${i.name}, Minecraft ${i.mcVersion}, agent ${i.agentVersion})`));
  agents.on("disconnected", (i) => log(`${i.id} disconnected`));

  const server = createCraftwireServer({ agents, ops: new OperationTracker(), audit: new AuditLog(join(home, "logs")) });
  const transport = new StdioServerTransport();
  transport.onclose = () => {
    void agents.close().finally(() => process.exit(0));
  };
  await server.connect(transport);
  log(`hub ${HUB_VERSION} listening on 127.0.0.1:${port} (state: ${home})`);
}

main().catch((e: unknown) => {
  log(`fatal: ${e instanceof Error ? e.stack ?? e.message : String(e)}`);
  process.exit(1);
});
```

- [ ] **Step 3: Run the tests to verify they pass**

Run: `cd hub && npm run build && npx vitest run`
Expected: PASS (all hub suites). On Windows also run `node dist/cli.js < NUL`: it should log "listening" to stderr and exit 0 when stdin closes. In bash, use `node dist/cli.js < /dev/null`.

- [ ] **Step 4: Commit**

```bash
git add hub/src/cli.ts hub/test/cli.test.ts
git commit -m "feat(hub): stdio CLI entry point with end-to-end MCP test"
```

---

### Task 7: Gradle build and agent-core foundations

**Files:**
- Create: `settings.gradle`, `build.gradle`, `gradle.properties`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`
- Create: `agent-core/build.gradle`
- Create in `agent-core/src/main/java/com/uxplima/craftwire/core/`: `Json.java`, `HubConfig.java`, `AgentError.java`, `Handler.java`, `OperationCache.java`, `Dispatcher.java`, `Backoff.java`
- Test in `agent-core/src/test/java/com/uxplima/craftwire/core/`: `HubConfigTest.java`, `OperationCacheTest.java`, `DispatcherTest.java`, `BackoffTest.java`

**Interfaces:**
- Produces (package `com.uxplima.craftwire.core`):
  ```java
  final class Json { static final Gson GSON; }
  record HubConfig(int port, String token) { static Path defaultHome(); static Optional<HubConfig> load(Path home); }
  class AgentError extends RuntimeException { AgentError(String code, String message, String hint); String code(); String hint(); }
  @FunctionalInterface interface Handler { CompletableFuture<JsonElement> handle(JsonObject params); }
  final class OperationCache { OperationCache(long ttlMillis, LongSupplier clock);
      CompletableFuture<JsonElement> run(String operationId, Supplier<CompletableFuture<JsonElement>> action); }
  final class Dispatcher { Dispatcher(OperationCache cache); void register(String method, Handler h);
      void setPaused(boolean paused); boolean isPaused(); CompletableFuture<JsonElement> dispatch(String method, JsonObject params); }
  final class Backoff { Backoff(long initialMillis, long maxMillis); long nextDelayMillis(); void reset(); }
  ```
- Dispatcher error codes: `PAUSED_BY_USER` (F8), `UNKNOWN_METHOD`. A handler that throws synchronously becomes a failed future and never propagates.
- `OperationCache` caches **successful** results only. A failed operation is removed so that a retry runs again, matching the hub's `OperationTracker`.

- [ ] **Step 1: Add the Gradle wrapper and root build**

Copy the wrapper from the Fabric example mod (pinned to Gradle 9.7.1):
```bash
curl -fsSLo gradlew https://raw.githubusercontent.com/FabricMC/fabric-example-mod/HEAD/gradlew
curl -fsSLo gradlew.bat https://raw.githubusercontent.com/FabricMC/fabric-example-mod/HEAD/gradlew.bat
mkdir -p gradle/wrapper
curl -fsSLo gradle/wrapper/gradle-wrapper.jar https://raw.githubusercontent.com/FabricMC/fabric-example-mod/HEAD/gradle/wrapper/gradle-wrapper.jar
curl -fsSLo gradle/wrapper/gradle-wrapper.properties https://raw.githubusercontent.com/FabricMC/fabric-example-mod/HEAD/gradle/wrapper/gradle-wrapper.properties
chmod +x gradlew
grep distributionUrl gradle/wrapper/gradle-wrapper.properties   # expect gradle-9.7.1-bin.zip
```

`settings.gradle`:
```groovy
pluginManagement {
    repositories {
        maven { name = 'Fabric'; url = 'https://maven.fabricmc.net/' }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = 'craftwire'
include 'agent-core'
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx2G
org.gradle.parallel=true
craftwire_version=0.1.0
minecraft_version=26.2
loader_version=0.19.5
loom_version=1.18.2
fabric_api_version=0.161.0+26.2
```

`build.gradle`:
```groovy
allprojects {
    group = 'com.uxplima.craftwire'
    version = project.property('craftwire_version')
}
```

`agent-core/build.gradle`:
```groovy
plugins {
    id 'java-library'
}

repositories {
    mavenCentral()
}

dependencies {
    // Gson ships with Minecraft and Paper; never bundle it.
    compileOnly 'com.google.code.gson:gson:2.13.2'

    testImplementation 'com.google.code.gson:gson:2.13.2'
    testImplementation platform('org.junit:junit-bom:5.13.4')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
    testImplementation 'org.java-websocket:Java-WebSocket:1.6.0'
}

tasks.withType(JavaCompile).configureEach {
    options.release = 25
    options.encoding = 'UTF-8'
}

test {
    useJUnitPlatform()
}
```

Run: `./gradlew :agent-core:build`
Expected: BUILD SUCCESSFUL (there are no sources yet).

- [ ] **Step 2: Write the failing tests**

`HubConfigTest.java`:
```java
package com.uxplima.craftwire.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HubConfigTest {
    @Test
    void loadsPortAndToken(@TempDir Path home) throws Exception {
        Files.writeString(home.resolve("hub.json"), "{\"port\": 47821, \"token\": \"" + "a".repeat(64) + "\"}");
        HubConfig cfg = HubConfig.load(home).orElseThrow();
        assertEquals(47821, cfg.port());
        assertEquals("a".repeat(64), cfg.token());
    }

    @Test
    void emptyWhenMissing(@TempDir Path home) {
        assertTrue(HubConfig.load(home).isEmpty());
    }

    @Test
    void emptyWhenCorrupt(@TempDir Path home) throws Exception {
        Files.writeString(home.resolve("hub.json"), "{nope");
        assertTrue(HubConfig.load(home).isEmpty());
    }

    @Test
    void emptyWhenFieldsMissing(@TempDir Path home) throws Exception {
        Files.writeString(home.resolve("hub.json"), "{\"port\": 1}");
        assertTrue(HubConfig.load(home).isEmpty());
    }
}
```

`OperationCacheTest.java`:
```java
package com.uxplima.craftwire.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class OperationCacheTest {
    private final AtomicLong now = new AtomicLong();
    private final OperationCache cache = new OperationCache(1000, now::get);

    @Test
    void returnsCachedSuccess() {
        AtomicInteger runs = new AtomicInteger();
        var a = cache.run("op", () -> CompletableFuture.completedFuture(new JsonPrimitive(runs.incrementAndGet())));
        var b = cache.run("op", () -> CompletableFuture.completedFuture(new JsonPrimitive(runs.incrementAndGet())));
        assertEquals(1, a.join().getAsInt());
        assertEquals(1, b.join().getAsInt());
        assertEquals(1, runs.get());
    }

    @Test
    void doesNotCacheFailures() {
        AtomicInteger runs = new AtomicInteger();
        var failed = cache.run("op", () -> { runs.incrementAndGet(); return CompletableFuture.<JsonElement>failedFuture(new AgentError("X", "x", null)); });
        assertTrue(failed.isCompletedExceptionally());
        var ok = cache.run("op", () -> { runs.incrementAndGet(); return CompletableFuture.completedFuture(new JsonPrimitive(7)); });
        assertEquals(7, ok.join().getAsInt());
        assertEquals(2, runs.get());
    }

    @Test
    void expiresAfterTtl() {
        AtomicInteger runs = new AtomicInteger();
        cache.run("op", () -> CompletableFuture.completedFuture(new JsonPrimitive(runs.incrementAndGet())));
        now.set(1001);
        cache.run("op", () -> CompletableFuture.completedFuture(new JsonPrimitive(runs.incrementAndGet())));
        assertEquals(2, runs.get());
    }
}
```

`DispatcherTest.java`:
```java
package com.uxplima.craftwire.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DispatcherTest {
    private final Dispatcher dispatcher = new Dispatcher(new OperationCache(300_000, System::currentTimeMillis));

    private static AgentError errorOf(CompletableFuture<?> f) {
        CompletionException e = assertThrows(CompletionException.class, f::join);
        return assertInstanceOf(AgentError.class, e.getCause());
    }

    @Test
    void routesToHandler() {
        dispatcher.register("echo", p -> CompletableFuture.completedFuture(p));
        JsonObject params = new JsonObject();
        params.addProperty("a", 1);
        assertEquals(params, dispatcher.dispatch("echo", params).join());
    }

    @Test
    void unknownMethod() {
        assertEquals("UNKNOWN_METHOD", errorOf(dispatcher.dispatch("nope", new JsonObject())).code());
    }

    @Test
    void pausedRejectsEverything() {
        dispatcher.register("echo", p -> CompletableFuture.completedFuture(p));
        dispatcher.setPaused(true);
        AgentError err = errorOf(dispatcher.dispatch("echo", new JsonObject()));
        assertEquals("PAUSED_BY_USER", err.code());
        assertTrue(err.hint().contains("F8"));
        dispatcher.setPaused(false);
        assertNotNull(dispatcher.dispatch("echo", new JsonObject()).join());
    }

    @Test
    void synchronousThrowBecomesFailedFuture() {
        dispatcher.register("boom", p -> { throw new AgentError("NO_SCREEN_OPEN", "none", "open one"); });
        assertEquals("NO_SCREEN_OPEN", errorOf(dispatcher.dispatch("boom", new JsonObject())).code());
    }

    @Test
    void operationIdDeduplicates() {
        AtomicInteger runs = new AtomicInteger();
        dispatcher.register("count", p -> CompletableFuture.completedFuture(new JsonPrimitive(runs.incrementAndGet())));
        JsonObject params = new JsonObject();
        params.addProperty("operationId", "op-1");
        dispatcher.dispatch("count", params).join();
        assertEquals(1, dispatcher.dispatch("count", params).join().getAsInt());
        assertEquals(1, runs.get());
    }
}
```

`BackoffTest.java`:
```java
package com.uxplima.craftwire.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BackoffTest {
    @Test
    void doublesUpToMaxAndResets() {
        Backoff b = new Backoff(1000, 30_000);
        assertEquals(1000, b.nextDelayMillis());
        assertEquals(2000, b.nextDelayMillis());
        assertEquals(4000, b.nextDelayMillis());
        for (int i = 0; i < 10; i++) b.nextDelayMillis();
        assertEquals(30_000, b.nextDelayMillis());
        b.reset();
        assertEquals(1000, b.nextDelayMillis());
    }
}
```

Run: `./gradlew :agent-core:test`
Expected: FAIL (compilation errors: the classes do not exist).

- [ ] **Step 3: Implement**

`Json.java`:
```java
package com.uxplima.craftwire.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public final class Json {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {}
}
```

`HubConfig.java`:
```java
package com.uxplima.craftwire.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Connection details the hub writes to {@code <home>/hub.json}. Re-read on every connect attempt. */
public record HubConfig(int port, String token) {
    public static Path defaultHome() {
        String env = System.getenv("CRAFTWIRE_HOME");
        if (env != null && !env.isBlank()) return Path.of(env);
        return Path.of(System.getProperty("user.home"), ".craftwire");
    }

    public static Optional<HubConfig> load(Path home) {
        Path file = home.resolve("hub.json");
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (!o.has("port") || !o.has("token")) return Optional.empty();
            return Optional.of(new HubConfig(o.get("port").getAsInt(), o.get("token").getAsString()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
```

`AgentError.java`:
```java
package com.uxplima.craftwire.core;

/** An expected failure reported to the model as {code, message, hint}. */
public class AgentError extends RuntimeException {
    private final String code;
    private final String hint;

    public AgentError(String code, String message, String hint) {
        super(message, null, false, false);
        this.code = code;
        this.hint = hint;
    }

    public String code() {
        return code;
    }

    public String hint() {
        return hint;
    }
}
```

`Handler.java`:
```java
package com.uxplima.craftwire.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface Handler {
    CompletableFuture<JsonElement> handle(JsonObject params);
}
```

`OperationCache.java`:
```java
package com.uxplima.craftwire.core;

import com.google.gson.JsonElement;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Remembers successful results by operationId so hub retries never execute twice. */
public final class OperationCache {
    private record Entry(CompletableFuture<JsonElement> future, long createdAt) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final LongSupplier clock;

    public OperationCache(long ttlMillis, LongSupplier clock) {
        this.ttlMillis = ttlMillis;
        this.clock = clock;
    }

    public CompletableFuture<JsonElement> run(String operationId, Supplier<CompletableFuture<JsonElement>> action) {
        long now = clock.getAsLong();
        entries.values().removeIf(e -> now - e.createdAt() > ttlMillis);
        Entry entry = entries.computeIfAbsent(operationId, id -> new Entry(action.get(), now));
        entry.future().whenComplete((r, err) -> {
            if (err != null) entries.remove(operationId, entry);
        });
        return entry.future();
    }
}
```

`Dispatcher.java`:
```java
package com.uxplima.craftwire.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class Dispatcher {
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    private final OperationCache cache;
    private volatile boolean paused;

    public Dispatcher(OperationCache cache) {
        this.cache = cache;
    }

    public void register(String method, Handler handler) {
        handlers.put(method, handler);
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean isPaused() {
        return paused;
    }

    public CompletableFuture<JsonElement> dispatch(String method, JsonObject params) {
        if (paused) {
            return CompletableFuture.failedFuture(new AgentError("PAUSED_BY_USER",
                    "The player paused Craftwire control in-game.",
                    "Ask the user to press F8 in Minecraft to resume."));
        }
        Handler handler = handlers.get(method);
        if (handler == null) {
            return CompletableFuture.failedFuture(new AgentError("UNKNOWN_METHOD",
                    "Unknown method: " + method, "Update the Craftwire agent so it matches the hub version."));
        }
        Supplier<CompletableFuture<JsonElement>> run = () -> {
            try {
                return handler.handle(params);
            } catch (Throwable t) {
                return CompletableFuture.failedFuture(t);
            }
        };
        JsonElement op = params.get("operationId");
        return op != null && op.isJsonPrimitive() ? cache.run(op.getAsString(), run) : run.get();
    }
}
```

`Backoff.java`:
```java
package com.uxplima.craftwire.core;

public final class Backoff {
    private final long initial;
    private final long max;
    private long next;

    public Backoff(long initialMillis, long maxMillis) {
        this.initial = initialMillis;
        this.max = maxMillis;
        this.next = initialMillis;
    }

    public synchronized long nextDelayMillis() {
        long current = next;
        next = Math.min(max, next * 2);
        return current;
    }

    public synchronized void reset() {
        next = initial;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :agent-core:test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle build.gradle gradle.properties gradlew gradlew.bat gradle agent-core
git commit -m "feat(core): gradle build, dispatcher, operation cache, hub config"
```

---

### Task 8: agent-core RPC codec and HubClient

**Files:**
- Create in `agent-core/src/main/java/com/uxplima/craftwire/core/`: `Hello.java`, `RpcCodec.java`, `HubClient.java`
- Test: `ProtocolFixturesTest.java`, `RpcCodecTest.java`, `HubClientTest.java`, `TestHub.java` (test helper)

**Interfaces:**
- Consumes: `Dispatcher`, `HubConfig`, `Backoff`, `AgentError`, `Json` (Task 7). Fixtures in `protocol/fixtures` (Task 1).
- Produces:
  ```java
  record Hello(String agentKind, String agentVersion, String mcVersion, String instanceName) {}
  final class RpcCodec { static final int PROTOCOL_VERSION = 1;
      static String hello(String token, Hello h); static String result(JsonElement id, JsonElement result);
      static String error(JsonElement id, Throwable t); static String event(String type, JsonObject data, long time); }
  final class HubClient implements AutoCloseable {
      interface Listener { void onConnected(String instanceId); void onDisconnected(); void onLog(String message); }
      HubClient(Supplier<Optional<HubConfig>> config, Supplier<Hello> hello, Dispatcher dispatcher, Listener listener);
      HubClient(Supplier<Optional<HubConfig>> config, Supplier<Hello> hello, Dispatcher dispatcher, Listener listener, Backoff backoff);
      void start(); boolean isConnected(); void notifyEvent(String type, JsonObject data); void close(); }
  ```
- Error encoding: `AgentError` → `{code:-32000, message, data:{code, hint}}`. `IllegalArgumentException` → `INVALID_PARAMS`. Anything else → `INTERNAL`, with the message set to `toString()` of the root cause.
- `HubClient` calls `config.get()` on **every** connect attempt (Review Focus #2) and never blocks the caller. All I/O runs on its own daemon thread named `craftwire-hub`.

- [ ] **Step 1: Write the failing codec and fixture tests**

`ProtocolFixturesTest.java`:
```java
package com.uxplima.craftwire.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ProtocolFixturesTest {
    static final Path FIXTURES = Path.of("..", "protocol", "fixtures");

    @Test
    void everyValidFixtureIsWellFormedJsonRpc() throws Exception {
        List<Path> valid;
        try (Stream<Path> s = Files.list(FIXTURES)) {
            valid = s.filter(p -> p.getFileName().toString().startsWith("valid-")).toList();
        }
        assertFalse(valid.isEmpty());
        for (Path p : valid) {
            JsonObject o = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            assertEquals("2.0", o.get("jsonrpc").getAsString(), p.toString());
            assertTrue(o.has("method") || o.has("id"), p.toString());
        }
    }

    @Test
    void helloMatchesFixtureExactly() throws Exception {
        JsonObject fixture = JsonParser.parseString(Files.readString(FIXTURES.resolve("valid-hello.json"))).getAsJsonObject();
        String encoded = RpcCodec.hello("a".repeat(64), new Hello("client", "0.1.0", "26.2", "Sirac"));
        assertEquals(fixture, JsonParser.parseString(encoded));
    }

    @Test
    void errorMatchesFixtureShape() throws Exception {
        JsonObject fixture = JsonParser.parseString(Files.readString(FIXTURES.resolve("valid-response-error.json"))).getAsJsonObject();
        JsonObject data = fixture.getAsJsonObject("error").getAsJsonObject("data");
        String encoded = RpcCodec.error(fixture.get("id"),
                new AgentError(data.get("code").getAsString(), fixture.getAsJsonObject("error").get("message").getAsString(), data.get("hint").getAsString()));
        assertEquals(fixture, JsonParser.parseString(encoded));
    }
}
```

`RpcCodecTest.java`:
```java
package com.uxplima.craftwire.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

class RpcCodecTest {
    private static JsonObject parse(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void illegalArgumentIsInvalidParams() {
        JsonObject o = parse(RpcCodec.error(new JsonPrimitive(3), new IllegalArgumentException("slot must be >= 0")));
        assertEquals("INVALID_PARAMS", o.getAsJsonObject("error").getAsJsonObject("data").get("code").getAsString());
        assertEquals("slot must be >= 0", o.getAsJsonObject("error").get("message").getAsString());
    }

    @Test
    void unknownThrowableIsInternal() {
        JsonObject o = parse(RpcCodec.error(new JsonPrimitive(3), new IllegalStateException("boom")));
        assertEquals("INTERNAL", o.getAsJsonObject("error").getAsJsonObject("data").get("code").getAsString());
    }

    @Test
    void nullResultIsJsonNull() {
        assertTrue(parse(RpcCodec.result(new JsonPrimitive(1), null)).get("result").isJsonNull());
    }

    @Test
    void eventHasTypeTimeData() {
        JsonObject data = new JsonObject();
        data.addProperty("text", "hi");
        JsonObject o = parse(RpcCodec.event("chat", data, 42L));
        assertEquals("event", o.get("method").getAsString());
        assertEquals(42L, o.getAsJsonObject("params").get("time").getAsLong());
        assertEquals("hi", o.getAsJsonObject("params").getAsJsonObject("data").get("text").getAsString());
    }
}
```

Run: `./gradlew :agent-core:test`
Expected: FAIL (`RpcCodec`, `Hello` missing).

- [ ] **Step 2: Implement `Hello` and `RpcCodec`**

`Hello.java`:
```java
package com.uxplima.craftwire.core;

public record Hello(String agentKind, String agentVersion, String mcVersion, String instanceName) {}
```

`RpcCodec.java`:
```java
package com.uxplima.craftwire.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

public final class RpcCodec {
    public static final int PROTOCOL_VERSION = 1;

    private RpcCodec() {}

    public static String hello(String token, Hello h) {
        JsonObject params = new JsonObject();
        params.addProperty("token", token);
        params.addProperty("agentKind", h.agentKind());
        params.addProperty("agentVersion", h.agentVersion());
        params.addProperty("protocolVersion", PROTOCOL_VERSION);
        params.addProperty("mcVersion", h.mcVersion());
        params.addProperty("instanceName", h.instanceName());
        JsonObject o = envelope();
        o.addProperty("id", 0);
        o.addProperty("method", "hello");
        o.add("params", params);
        return Json.GSON.toJson(o);
    }

    public static String result(JsonElement id, JsonElement result) {
        JsonObject o = envelope();
        o.add("id", id);
        o.add("result", result == null ? JsonNull.INSTANCE : result);
        return Json.GSON.toJson(o);
    }

    public static String error(JsonElement id, Throwable t) {
        String code;
        String hint = null;
        String message;
        if (t instanceof AgentError e) {
            code = e.code();
            hint = e.hint();
            message = e.getMessage();
        } else if (t instanceof IllegalArgumentException) {
            code = "INVALID_PARAMS";
            message = t.getMessage();
        } else {
            code = "INTERNAL";
            message = t.toString();
        }
        JsonObject data = new JsonObject();
        data.addProperty("code", code);
        if (hint != null) data.addProperty("hint", hint);
        JsonObject err = new JsonObject();
        err.addProperty("code", -32000);
        err.addProperty("message", message == null ? code : message);
        err.add("data", data);
        JsonObject o = envelope();
        o.add("id", id);
        o.add("error", err);
        return Json.GSON.toJson(o);
    }

    public static String event(String type, JsonObject data, long time) {
        JsonObject params = new JsonObject();
        params.addProperty("type", type);
        params.add("time", new JsonPrimitive(time));
        params.add("data", data);
        JsonObject o = envelope();
        o.addProperty("method", "event");
        o.add("params", params);
        return Json.GSON.toJson(o);
    }

    private static JsonObject envelope() {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        return o;
    }
}
```

Run: `./gradlew :agent-core:test --tests '*Codec*' --tests '*Fixtures*'`
Expected: PASS.

- [ ] **Step 3: Write the test hub and the failing HubClient tests**

`TestHub.java` (test source set):
```java
package com.uxplima.craftwire.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/** Minimal hub stand-in: accepts hello with the right token and records every message. */
final class TestHub extends WebSocketServer {
    final BlockingQueue<JsonObject> inbox = new LinkedBlockingQueue<>();
    final String token;
    private final CountDownLatch started = new CountDownLatch(1);
    volatile WebSocket conn;

    TestHub(int port, String token) {
        super(new InetSocketAddress("127.0.0.1", port));
        this.token = token;
        setReuseAddr(true);
    }

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    TestHub startAndWait() throws InterruptedException {
        start();
        started.await(5, TimeUnit.SECONDS);
        return this;
    }

    JsonObject next() throws InterruptedException {
        JsonObject o = inbox.poll(5, TimeUnit.SECONDS);
        if (o == null) throw new AssertionError("no message within 5s");
        return o;
    }

    void sendRequest(int id, String method, JsonObject params) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.addProperty("id", id);
        o.addProperty("method", method);
        o.add("params", params);
        conn.send(o.toString());
    }

    @Override public void onOpen(WebSocket c, ClientHandshake h) { conn = c; }
    @Override public void onClose(WebSocket c, int code, String reason, boolean remote) {}
    @Override public void onError(WebSocket c, Exception ex) {}
    @Override public void onStart() { started.countDown(); }

    @Override
    public void onMessage(WebSocket c, String message) {
        JsonObject o = JsonParser.parseString(message).getAsJsonObject();
        inbox.add(o);
        if (o.has("method") && "hello".equals(o.get("method").getAsString())) {
            boolean ok = token.equals(o.getAsJsonObject("params").get("token").getAsString());
            c.send(ok
                    ? "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"instanceId\":\"client-1\"}}"
                    : "{\"jsonrpc\":\"2.0\",\"id\":0,\"error\":{\"code\":-32001,\"message\":\"Invalid token\",\"data\":{\"code\":\"UNAUTHORIZED\"}}}");
            if (!ok) c.close(4001, "UNAUTHORIZED");
        }
    }
}
```

`HubClientTest.java`:
```java
package com.uxplima.craftwire.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HubClientTest {
    static final String TOKEN = "a".repeat(64);
    TestHub hub;
    HubClient client;
    final Dispatcher dispatcher = new Dispatcher(new OperationCache(300_000, System::currentTimeMillis));
    final CountDownLatch connected = new CountDownLatch(1);
    final CountDownLatch disconnected = new CountDownLatch(1);
    final HubClient.Listener listener = new HubClient.Listener() {
        @Override public void onConnected(String id) { connected.countDown(); }
        @Override public void onDisconnected() { disconnected.countDown(); }
        @Override public void onLog(String m) {}
    };

    HubClient clientFor(AtomicReference<HubConfig> cfg) {
        client = new HubClient(() -> Optional.ofNullable(cfg.get()), () -> new Hello("client", "0.1.0", "26.2", "Tester"),
                dispatcher, listener, new Backoff(50, 200));
        client.start();
        return client;
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) client.close();
        if (hub != null) hub.stop(500);
    }

    @Test
    void sendsHelloWithTokenAndProtocol() throws Exception {
        int port = TestHub.freePort();
        hub = new TestHub(port, TOKEN).startAndWait();
        clientFor(new AtomicReference<>(new HubConfig(port, TOKEN)));
        JsonObject hello = hub.next();
        assertEquals("hello", hello.get("method").getAsString());
        assertEquals(TOKEN, hello.getAsJsonObject("params").get("token").getAsString());
        assertEquals(1, hello.getAsJsonObject("params").get("protocolVersion").getAsInt());
        assertTrue(connected.await(5, TimeUnit.SECONDS));
        assertTrue(client.isConnected());
    }

    @Test
    void answersRequestsThroughDispatcher() throws Exception {
        dispatcher.register("echo", p -> CompletableFuture.completedFuture(p));
        dispatcher.register("fail", p -> { throw new AgentError("NO_SCREEN_OPEN", "none", "open one"); });
        int port = TestHub.freePort();
        hub = new TestHub(port, TOKEN).startAndWait();
        clientFor(new AtomicReference<>(new HubConfig(port, TOKEN)));
        hub.next();
        assertTrue(connected.await(5, TimeUnit.SECONDS));

        JsonObject params = new JsonObject();
        params.addProperty("a", 1);
        hub.sendRequest(5, "echo", params);
        JsonObject res = hub.next();
        assertEquals(5, res.get("id").getAsInt());
        assertEquals(1, res.getAsJsonObject("result").get("a").getAsInt());

        hub.sendRequest(6, "fail", new JsonObject());
        JsonObject err = hub.next();
        assertEquals("NO_SCREEN_OPEN", err.getAsJsonObject("error").getAsJsonObject("data").get("code").getAsString());
        assertEquals("open one", err.getAsJsonObject("error").getAsJsonObject("data").get("hint").getAsString());
    }

    @Test
    void notifiesEventsOnlyWhenConnected() throws Exception {
        int port = TestHub.freePort();
        hub = new TestHub(port, TOKEN).startAndWait();
        clientFor(new AtomicReference<>(new HubConfig(port, TOKEN)));
        hub.next();
        assertTrue(connected.await(5, TimeUnit.SECONDS));
        JsonObject data = new JsonObject();
        data.addProperty("text", "hi");
        client.notifyEvent("chat", data);
        JsonObject ev = hub.next();
        assertEquals("event", ev.get("method").getAsString());
        assertEquals("chat", ev.getAsJsonObject("params").get("type").getAsString());
    }

    @Test
    void followsHubJsonToANewHub() throws Exception {
        int port1 = TestHub.freePort();
        hub = new TestHub(port1, TOKEN).startAndWait();
        AtomicReference<HubConfig> cfg = new AtomicReference<>(new HubConfig(port1, TOKEN));
        clientFor(cfg);
        hub.next();
        assertTrue(connected.await(5, TimeUnit.SECONDS));

        int port2 = TestHub.freePort();
        String token2 = "b".repeat(64);
        TestHub second = new TestHub(port2, token2).startAndWait();
        cfg.set(new HubConfig(port2, token2));     // a second Claude session rewrote hub.json
        hub.stop(500);
        hub = second;
        assertTrue(disconnected.await(5, TimeUnit.SECONDS));
        JsonObject hello = hub.next();
        assertEquals(token2, hello.getAsJsonObject("params").get("token").getAsString());
    }

    @Test
    void waitsQuietlyWithoutHubJson() throws Exception {
        clientFor(new AtomicReference<>(null));
        Thread.sleep(300);
        assertFalse(client.isConnected());
    }
}
```

Run: `./gradlew :agent-core:test --tests '*HubClient*'`
Expected: FAIL (`HubClient` missing).

- [ ] **Step 4: Implement HubClient**

`HubClient.java`:
```java
package com.uxplima.craftwire.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Outbound WebSocket connection to the Craftwire hub, with handshake, dispatch and reconnect. */
public final class HubClient implements AutoCloseable {
    public interface Listener {
        void onConnected(String instanceId);

        void onDisconnected();

        void onLog(String message);
    }

    private final Supplier<Optional<HubConfig>> config;
    private final Supplier<Hello> hello;
    private final Dispatcher dispatcher;
    private final Listener listener;
    private final Backoff backoff;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "craftwire-hub");
        t.setDaemon(true);
        return t;
    });

    private volatile WebSocket socket;
    private volatile String instanceId;
    private volatile boolean closed;
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);

    public HubClient(Supplier<Optional<HubConfig>> config, Supplier<Hello> hello, Dispatcher dispatcher, Listener listener) {
        this(config, hello, dispatcher, listener, new Backoff(1000, 30_000));
    }

    public HubClient(Supplier<Optional<HubConfig>> config, Supplier<Hello> hello, Dispatcher dispatcher, Listener listener, Backoff backoff) {
        this.config = config;
        this.hello = hello;
        this.dispatcher = dispatcher;
        this.listener = listener;
        this.backoff = backoff;
    }

    public void start() {
        exec.execute(this::connect);
    }

    public boolean isConnected() {
        return instanceId != null;
    }

    public void notifyEvent(String type, JsonObject data) {
        WebSocket ws = socket;
        if (ws != null && instanceId != null) send(ws, RpcCodec.event(type, data, System.currentTimeMillis()));
    }

    private void connect() {
        if (closed) return;
        Optional<HubConfig> cfg = config.get();
        if (cfg.isEmpty()) {
            scheduleReconnect();
            return;
        }
        HubConfig c = cfg.get();
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://127.0.0.1:" + c.port() + "/"), new SocketListener(c.token()))
                .whenComplete((ws, err) -> {
                    if (err != null) scheduleReconnect();
                });
    }

    private void scheduleReconnect() {
        if (closed || exec.isShutdown()) return;
        exec.schedule(this::connect, backoff.nextDelayMillis(), TimeUnit.MILLISECONDS);
    }

    private void handle(WebSocket ws, String text) {
        JsonObject msg;
        try {
            msg = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        if (msg.has("method") && msg.has("id")) {
            JsonElement id = msg.get("id");
            String method = msg.get("method").getAsString();
            JsonObject params = msg.has("params") && msg.get("params").isJsonObject() ? msg.getAsJsonObject("params") : new JsonObject();
            dispatcher.dispatch(method, params).whenComplete((result, err) ->
                    send(ws, err == null ? RpcCodec.result(id, result) : RpcCodec.error(id, unwrap(err))));
            return;
        }
        JsonElement id = msg.get("id");
        if (id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isNumber() && id.getAsInt() == 0) {
            if (msg.has("result")) {
                instanceId = msg.getAsJsonObject("result").get("instanceId").getAsString();
                backoff.reset();
                listener.onConnected(instanceId);
            } else {
                listener.onLog("Hub refused the connection: " + msg.get("error"));
            }
        }
    }

    private synchronized void send(WebSocket ws, String text) {
        sendChain = sendChain.handle((v, e) -> null).thenCompose(v -> ws.sendText(text, true));
    }

    static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) t = t.getCause();
        return t;
    }

    @Override
    public void close() {
        closed = true;
        WebSocket ws = socket;
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        exec.shutdownNow();
    }

    private final class SocketListener implements WebSocket.Listener {
        private final String token;
        private final StringBuilder buffer = new StringBuilder();
        private boolean ended;

        SocketListener(String token) {
            this.token = token;
        }

        @Override
        public void onOpen(WebSocket ws) {
            socket = ws;
            send(ws, RpcCodec.hello(token, hello.get()));
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                handle(ws, text);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            ended(statusCode + " " + reason);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            ended(String.valueOf(error));
        }

        private void ended(String why) {
            if (ended) return;
            ended = true;
            socket = null;
            boolean was = instanceId != null;
            instanceId = null;
            if (was) listener.onDisconnected();
            listener.onLog("Hub connection closed: " + why);
            scheduleReconnect();
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :agent-core:test`
Expected: BUILD SUCCESSFUL, all agent-core tests pass, including `followsHubJsonToANewHub`.

- [ ] **Step 6: Commit**

```bash
git add agent-core
git commit -m "feat(core): JSON-RPC codec and reconnecting HubClient"
```

---

### Task 9: Fabric agent scaffold — lifecycle, kill switch, indicator, events, focus guard

**Files:**
- Modify: `settings.gradle` (add `include 'agent-fabric'`)
- Create: `agent-fabric/build.gradle`
- Create: `agent-fabric/src/main/resources/fabric.mod.json`, `agent-fabric/src/main/resources/craftwire-agent.mixins.json`, `agent-fabric/src/main/resources/assets/craftwire/lang/en_us.json`
- Create in `agent-fabric/src/main/java/com/uxplima/craftwire/fabric/`: `CraftwireClient.java`, `CraftwireAgent.java`, `ClientScheduler.java`, `Params.java`, `KillSwitch.java`, `Indicator.java`, `ChatBridge.java`, `ScreenWatcher.java`, `handlers/Handlers.java`
- Create (gametest): `agent-fabric/src/gametest/resources/fabric.mod.json`, `agent-fabric/src/gametest/java/com/uxplima/craftwire/fabric/gametest/AgentGameTests.java`, `Calls.java`, `LifecycleChecks.java`

**Interfaces:**
- Consumes: `Dispatcher`, `OperationCache`, `HubClient`, `HubConfig`, `Hello`, `AgentError` (Tasks 7–8).
- Produces:
  ```java
  // com.uxplima.craftwire.fabric
  final class CraftwireClient implements ClientModInitializer { static CraftwireAgent agent(); }
  final class CraftwireAgent {
      Dispatcher dispatcher(); ClientScheduler scheduler();
      void start();                         // registers handlers/events, starts HubClient
      boolean isConnected(); boolean isCaptureInProgress(); void setCaptureInProgress(boolean v);
      void emit(String type, JsonObject data);
      void onHubConnected(String instanceId); void onHubDisconnected();   // run on any thread
  }
  final class ClientScheduler {
      <T> CompletableFuture<T> call(Supplier<T> onClientThread);   // runs on the client thread
      CompletableFuture<Void> delay(int ticks);                      // completes on the client thread after N end-ticks
      void onEndTick();
  }
  final class Params { static Optional<Integer> optInt(JsonObject p, String k); static Optional<Double> optDouble(JsonObject p, String k);
      static Optional<String> optString(JsonObject p, String k); static Optional<Boolean> optBool(JsonObject p, String k);
      static AgentError notInWorld(); static AgentError invalid(String message); }   // invalid → code INVALID_PARAMS
  final class Handlers { static void registerAll(CraftwireAgent agent); }   // grows in Tasks 10–13
  ```
- Gametest helper (package `com.uxplima.craftwire.fabric.gametest`):
  ```java
  final class Calls { static JsonElement call(ClientGameTestContext ctx, String method, String paramsJson);
      static AgentError error(ClientGameTestContext ctx, String method, String paramsJson);
      static void check(boolean condition, String message); }
  ```
  `AgentGameTests` creates one singleplayer world and runs every `*Checks.run(ctx, sp)` in order. Later tasks add one line each.
- Gametests drive the client tick-by-tick, so a test must **never** call `future.join()` before `ctx.waitFor(mc -> f.isDone())`. Otherwise it deadlocks.

- [ ] **Step 1: Build files and metadata**

`settings.gradle`: change the include line to `include 'agent-core', 'agent-fabric'`.

`agent-fabric/build.gradle`:
```groovy
plugins {
    id 'net.fabricmc.fabric-loom' version "${loom_version}"
}

base {
    archivesName = 'craftwire-agent-fabric'
}

repositories {
    mavenCentral()
}

loom {
    mods {
        "craftwire-agent" {
            sourceSet sourceSets.main
        }
    }
}

fabricApi {
    configureTests {
        createSourceSet = true
        modId = "craftwire-agent-gametest"
        enableClientGameTests = true
        eula = true
    }
}

dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    implementation "net.fabricmc:fabric-loader:${project.loader_version}"
    implementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}"

    implementation project(':agent-core')
    include project(':agent-core')

    testImplementation platform('org.junit:junit-bom:5.13.4')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

processResources {
    def version = project.version
    inputs.property "version", version
    filesMatching("fabric.mod.json") {
        expand "version": version
    }
}

tasks.withType(JavaCompile).configureEach {
    it.options.release = 25
    it.options.encoding = 'UTF-8'
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

test {
    useJUnitPlatform()
}
```

`agent-fabric/src/main/resources/fabric.mod.json`:
```json
{
  "schemaVersion": 1,
  "id": "craftwire-agent",
  "version": "${version}",
  "name": "Craftwire Agent",
  "description": "Lets AI agents (Claude Code and other MCP clients) see and drive Minecraft through the Craftwire hub. Press F8 to pause AI control.",
  "authors": ["UXPLIMA"],
  "contact": { "sources": "https://github.com/uxplima/craftwire" },
  "license": "MIT",
  "environment": "client",
  "entrypoints": {
    "client": ["com.uxplima.craftwire.fabric.CraftwireClient"]
  },
  "mixins": ["craftwire-agent.mixins.json"],
  "depends": {
    "fabricloader": ">=0.19.5",
    "minecraft": "~26.2",
    "java": ">=25",
    "fabric-api": "*"
  }
}
```

`agent-fabric/src/main/resources/craftwire-agent.mixins.json` (later tasks append to `client`):
```json
{
  "required": true,
  "package": "com.uxplima.craftwire.fabric.mixin",
  "compatibilityLevel": "JAVA_25",
  "client": [],
  "injectors": { "defaultRequire": 1 },
  "overwrites": { "requireAnnotations": true }
}
```

`agent-fabric/src/main/resources/assets/craftwire/lang/en_us.json`:
```json
{
  "key.craftwire.pause": "Pause / resume AI control",
  "key.category.craftwire.main": "Craftwire"
}
```

`agent-fabric/src/gametest/resources/fabric.mod.json`:
```json
{
  "schemaVersion": 1,
  "id": "craftwire-agent-gametest",
  "version": "0.0.0",
  "environment": "client",
  "entrypoints": {
    "fabric-client-gametest": ["com.uxplima.craftwire.fabric.gametest.AgentGameTests"]
  },
  "depends": { "craftwire-agent": "*" }
}
```

Run: `./gradlew :agent-fabric:compileJava`
Expected: BUILD SUCCESSFUL (Loom downloads Minecraft on first run).

- [ ] **Step 2: Write the failing gametest**

`Calls.java`:
```java
package com.uxplima.craftwire.fabric.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.fabric.CraftwireClient;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

final class Calls {
    private Calls() {}

    static CompletableFuture<JsonElement> start(String method, String paramsJson) {
        return CraftwireClient.agent().dispatcher().dispatch(method, JsonParser.parseString(paramsJson).getAsJsonObject());
    }

    static JsonElement call(ClientGameTestContext ctx, String method, String paramsJson) {
        CompletableFuture<JsonElement> f = start(method, paramsJson);
        ctx.waitFor(mc -> f.isDone(), 400);
        try {
            return f.join();
        } catch (CompletionException e) {
            throw new AssertionError(method + " failed: " + e.getCause(), e.getCause());
        }
    }

    static AgentError error(ClientGameTestContext ctx, String method, String paramsJson) {
        CompletableFuture<JsonElement> f = start(method, paramsJson);
        ctx.waitFor(mc -> f.isDone(), 400);
        try {
            f.join();
        } catch (CompletionException e) {
            Throwable c = e.getCause();
            if (c instanceof AgentError a) return a;
            throw new AssertionError(method + " threw non-AgentError " + c, c);
        }
        throw new AssertionError(method + " unexpectedly succeeded");
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
```

`LifecycleChecks.java`:
```java
package com.uxplima.craftwire.fabric.gametest;

import static com.uxplima.craftwire.fabric.gametest.Calls.check;

import com.uxplima.craftwire.fabric.CraftwireAgent;
import com.uxplima.craftwire.fabric.CraftwireClient;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.lwjgl.glfw.GLFW;

final class LifecycleChecks {
    private LifecycleChecks() {}

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        CraftwireAgent agent = CraftwireClient.agent();

        // Review Focus #1: a connected hub must stop the game from pausing when Claude Code takes focus.
        ctx.runOnClient(mc -> mc.options.pauseOnLostFocus = true);
        agent.onHubConnected("client-1");
        ctx.waitTicks(2);
        check(!ctx.computeOnClient(mc -> mc.options.pauseOnLostFocus), "pauseOnLostFocus should be off while connected");
        agent.onHubDisconnected();
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> mc.options.pauseOnLostFocus), "pauseOnLostFocus should be restored on disconnect");

        // F8 kill switch toggles the dispatcher.
        ctx.getInput().pressKey(GLFW.GLFW_KEY_F8);
        ctx.waitTicks(2);
        check(agent.dispatcher().isPaused(), "F8 should pause");
        check("PAUSED_BY_USER".equals(Calls.error(ctx, "player.state", "{}").code()), "paused calls must fail with PAUSED_BY_USER");
        ctx.getInput().pressKey(GLFW.GLFW_KEY_F8);
        ctx.waitTicks(2);
        check(!agent.dispatcher().isPaused(), "second F8 should resume");
    }
}
```

`AgentGameTests.java`:
```java
package com.uxplima.craftwire.fabric.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

public final class AgentGameTests implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientLevel().waitForChunksRender();
            LifecycleChecks.run(ctx, sp);
        }
    }
}
```

Run: `./gradlew :agent-fabric:runClientGameTest`
Expected: FAIL (compilation: `CraftwireClient` / `CraftwireAgent` do not exist).

- [ ] **Step 3: Implement the lifecycle classes**

`Params.java`:
```java
package com.uxplima.craftwire.fabric;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import java.util.Optional;

public final class Params {
    private Params() {}

    private static Optional<JsonElement> get(JsonObject p, String k) {
        JsonElement e = p.get(k);
        return e == null || e.isJsonNull() ? Optional.empty() : Optional.of(e);
    }

    public static Optional<Integer> optInt(JsonObject p, String k) {
        return get(p, k).map(JsonElement::getAsInt);
    }

    public static Optional<Double> optDouble(JsonObject p, String k) {
        return get(p, k).map(JsonElement::getAsDouble);
    }

    public static Optional<String> optString(JsonObject p, String k) {
        return get(p, k).map(JsonElement::getAsString);
    }

    public static Optional<Boolean> optBool(JsonObject p, String k) {
        return get(p, k).map(JsonElement::getAsBoolean);
    }

    public static AgentError notInWorld() {
        return new AgentError("NOT_IN_WORLD", "The player is not in a world.", "Join a world or server first (the title screen has no player).");
    }

    public static AgentError invalid(String message) {
        return new AgentError("INVALID_PARAMS", message, "Check the tool's parameter description and retry.");
    }
}
```

`ClientScheduler.java`:
```java
package com.uxplima.craftwire.fabric;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;

/** Bridges hub threads to the client thread. All state here is touched on the client thread only. */
public final class ClientScheduler {
    private record Pending(long due, Runnable task) {}

    private final List<Pending> pending = new ArrayList<>();
    private long tick;

    public <T> CompletableFuture<T> call(Supplier<T> onClientThread) {
        return Minecraft.getInstance().submit(onClientThread);
    }

    public CompletableFuture<Void> delay(int ticks) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> pending.add(new Pending(tick + Math.max(1, ticks), () -> f.complete(null))));
        return f;
    }

    public void onEndTick() {
        tick++;
        if (pending.isEmpty()) return;
        List<Runnable> due = new ArrayList<>();
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.due() <= tick) {
                due.add(p.task());
                it.remove();
            }
        }
        for (Runnable r : due) {
            try {
                r.run();
            } catch (RuntimeException e) {
                CraftwireAgent.LOGGER.error("Craftwire scheduled task failed", e);
            }
        }
    }
}
```

`KillSwitch.java`:
```java
package com.uxplima.craftwire.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

final class KillSwitch {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("craftwire", "main"));
    private final KeyMapping key = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.craftwire.pause", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, CATEGORY));
    private final CraftwireAgent agent;

    KillSwitch(CraftwireAgent agent) {
        this.agent = agent;
    }

    void onEndTick(Minecraft mc) {
        while (key.consumeClick()) {
            boolean paused = !agent.dispatcher().isPaused();
            agent.dispatcher().setPaused(paused);
            mc.gui.hud.setOverlayMessage(Component.literal(paused
                    ? "⏸ Craftwire paused — AI control off (F8 to resume)"
                    : "▶ Craftwire resumed — AI control on"), false);
        }
    }
}
```

`Indicator.java`:
```java
package com.uxplima.craftwire.fabric;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

final class Indicator {
    private Indicator() {}

    static void register(CraftwireAgent agent) {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("craftwire", "indicator"), (graphics, delta) -> {
            if (!agent.isConnected() || agent.isCaptureInProgress()) return;
            Minecraft mc = Minecraft.getInstance();
            boolean paused = agent.dispatcher().isPaused();
            graphics.text(mc.font, paused ? "⏸ Craftwire paused (F8)" : "⚡ Craftwire connected", 4, 4,
                    paused ? 0xFFFFAA00 : 0xFF55FF55, true);
        });
    }
}
```

`ChatBridge.java`:
```java
package com.uxplima.craftwire.fabric;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

final class ChatBridge {
    private ChatBridge() {}

    static void register(CraftwireAgent agent) {
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, bound, time) -> {
            JsonObject d = new JsonObject();
            d.addProperty("text", message.getString());
            d.addProperty("kind", "chat");
            if (sender != null) d.addProperty("sender", sender.name());
            agent.emit("chat", d);
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            JsonObject d = new JsonObject();
            d.addProperty("text", message.getString());
            if (overlay) {
                d.addProperty("element", "actionbar");
                agent.emit("hud", d);
            } else {
                d.addProperty("kind", "game");
                agent.emit("chat", d);
            }
        });
    }
}
```

`ScreenWatcher.java`:
```java
package com.uxplima.craftwire.fabric;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Emits `screen` events when the open screen changes (checked each client tick). */
final class ScreenWatcher {
    private final CraftwireAgent agent;
    private Screen last;

    ScreenWatcher(CraftwireAgent agent) {
        this.agent = agent;
    }

    void onEndTick(Minecraft mc) {
        Screen now = mc.gui.screen();
        if (now == last) return;
        if (now == null) agent.emit("screen", describe(last, false));
        else agent.emit("screen", describe(now, true));
        last = now;
    }

    private static JsonObject describe(Screen s, boolean open) {
        JsonObject d = new JsonObject();
        d.addProperty("open", open);
        d.addProperty("title", s == null ? "" : s.getTitle().getString());
        d.addProperty("type", s == null ? "" : s.getClass().getSimpleName());
        return d;
    }
}
```

`handlers/Handlers.java` (empty registry for now):
```java
package com.uxplima.craftwire.fabric.handlers;

import com.uxplima.craftwire.fabric.CraftwireAgent;

public final class Handlers {
    private Handlers() {}

    public static void registerAll(CraftwireAgent agent) {
        // Tasks 10–13 register their handlers here.
    }
}
```

`CraftwireAgent.java`:
```java
package com.uxplima.craftwire.fabric;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.Dispatcher;
import com.uxplima.craftwire.core.Hello;
import com.uxplima.craftwire.core.HubClient;
import com.uxplima.craftwire.core.HubConfig;
import com.uxplima.craftwire.core.OperationCache;
import com.uxplima.craftwire.fabric.handlers.Handlers;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CraftwireAgent {
    public static final Logger LOGGER = LoggerFactory.getLogger("craftwire");
    public static final String VERSION = FabricLoader.getInstance().getModContainer("craftwire-agent")
            .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");

    private final Dispatcher dispatcher = new Dispatcher(new OperationCache(300_000, System::currentTimeMillis));
    private final ClientScheduler scheduler = new ClientScheduler();
    private HubClient hub;
    private volatile boolean connected;
    private volatile boolean captureInProgress;
    private Boolean savedPauseOnLostFocus;

    public Dispatcher dispatcher() {
        return dispatcher;
    }

    public ClientScheduler scheduler() {
        return scheduler;
    }

    public void start() {
        Handlers.registerAll(this);
        KillSwitch killSwitch = new KillSwitch(this);
        ScreenWatcher screens = new ScreenWatcher(this);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            scheduler.onEndTick();
            killSwitch.onEndTick(mc);
            screens.onEndTick(mc);
        });
        ChatBridge.register(this);
        Indicator.register(this);

        hub = new HubClient(() -> HubConfig.load(HubConfig.defaultHome()), this::hello, dispatcher, new HubClient.Listener() {
            @Override public void onConnected(String instanceId) { onHubConnected(instanceId); }
            @Override public void onDisconnected() { onHubDisconnected(); }
            @Override public void onLog(String message) { LOGGER.debug("[craftwire] {}", message); }
        });
        hub.start();
    }

    private Hello hello() {
        Minecraft mc = Minecraft.getInstance();
        return new Hello("client", VERSION, SharedConstants.getCurrentVersion().name(), mc.getUser().getName());
    }

    public void onHubConnected(String instanceId) {
        connected = true;
        LOGGER.info("[craftwire] connected to hub as {}", instanceId);
        Minecraft.getInstance().execute(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (savedPauseOnLostFocus == null) savedPauseOnLostFocus = mc.options.pauseOnLostFocus;
            mc.options.pauseOnLostFocus = false;   // Claude Code takes focus; the game must keep rendering
        });
    }

    public void onHubDisconnected() {
        connected = false;
        Minecraft.getInstance().execute(() -> {
            if (savedPauseOnLostFocus != null) {
                Minecraft.getInstance().options.pauseOnLostFocus = savedPauseOnLostFocus;
                savedPauseOnLostFocus = null;
            }
        });
    }

    public boolean isConnected() {
        return connected;
    }

    public boolean isCaptureInProgress() {
        return captureInProgress;
    }

    public void setCaptureInProgress(boolean value) {
        captureInProgress = value;
    }

    public void emit(String type, JsonObject data) {
        if (hub != null) hub.notifyEvent(type, data);
    }
}
```

`CraftwireClient.java`:
```java
package com.uxplima.craftwire.fabric;

import net.fabricmc.api.ClientModInitializer;

public final class CraftwireClient implements ClientModInitializer {
    private static CraftwireAgent agent;

    @Override
    public void onInitializeClient() {
        agent = new CraftwireAgent();
        agent.start();
    }

    public static CraftwireAgent agent() {
        return agent;
    }
}
```

- [ ] **Step 4: Run the gametest to verify it passes**

Run: `./gradlew :agent-fabric:runClientGameTest`
Expected: a client window opens, creates a world, runs `LifecycleChecks` and exits. Gradle reports BUILD SUCCESSFUL. A failing `check` makes the task fail with the AssertionError message.

Note: no other handler exists yet, so `Calls.error(ctx, "player.state", "{}")` returns `PAUSED_BY_USER` because the pause check runs before the method lookup. That is exactly what this step asserts.

- [ ] **Step 5: Verify the agent-core jar is nested**

Run: `./gradlew :agent-fabric:build && unzip -l agent-fabric/build/libs/craftwire-agent-fabric-0.1.0.jar | grep agent-core`
Expected: one line for `META-INF/jars/agent-core-0.1.0.jar`.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle agent-fabric
git commit -m "feat(fabric): agent lifecycle, F8 kill switch, indicator, chat/screen events, focus guard"
```

---

### Task 10: `player.state`, `chat.send`, `hud.read`

**Files:**
- Create in `agent-fabric/src/main/java/com/uxplima/craftwire/fabric/`: `ItemJson.java`, `handlers/PlayerStateHandler.java`, `handlers/ChatSendHandler.java`, `handlers/HudReadHandler.java`, `mixin/HudAccessor.java`, `mixin/BossHealthOverlayAccessor.java`
- Modify: `handlers/Handlers.java`, `craftwire-agent.mixins.json` (`client` array)
- Create (gametest): `StateChecks.java`. Modify: `AgentGameTests.java`

**Interfaces:**
- Consumes: `CraftwireAgent`, `ClientScheduler.call`, `Params` (Task 9).
- Produces:
  - `ItemJson.of(ItemStack stack, boolean withLore): JsonObject` returns `{id, count, name, enchanted, lore?: string[]}`. Lore is the tooltip lines without the name line.
  - Accessors `HudAccessor` (`craftwire$getOverlayMessage()`, `craftwire$getOverlayMessageTime()`, `craftwire$getTitle()`, `craftwire$getSubtitle()`, `craftwire$getTitleTime()`, `craftwire$getBossOverlay()`, `craftwire$isHidden()`, `craftwire$setHidden(boolean)`) and `BossHealthOverlayAccessor.craftwire$getEvents(): Map<UUID, LerpingBossEvent>`. Task 12 uses `HudAccessor.craftwire$setHidden`.
  - Agent methods:
    - `player.state` → `{position:{x,y,z}, yaw, pitch, health, gameMode, dimension, selectedSlot (1-9), held, inventory:[{slot,…item}], target:{type:"block",pos,block} | {type:"entity",entity,name,uuid} | null}`
    - `chat.send {text, command}` → `{sent:true}`
    - `hud.read` → `{bossbars:[{name,progress,color}], actionbar, title, subtitle, sidebar:{title,entries:[{name,value}]}|null, tabList:[string], hidden}`

- [ ] **Step 1: Write the failing gametest checks**

`StateChecks.java`:
```java
package com.uxplima.craftwire.fabric.gametest;

import static com.uxplima.craftwire.fabric.gametest.Calls.check;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

final class StateChecks {
    private StateChecks() {}

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        sp.getServer().runCommand("give @a minecraft:diamond 5");
        sp.getServer().runCommand("bossbar add craftwire:test \"Build Progress\"");
        sp.getServer().runCommand("bossbar set craftwire:test players @a");
        sp.getServer().runCommand("bossbar set craftwire:test value 40");
        sp.getServer().runCommand("title @a title \"Hello Title\"");
        ctx.waitTicks(10);

        JsonObject state = Calls.call(ctx, "player.state", "{}").getAsJsonObject();
        check(state.has("position") && state.getAsJsonObject("position").has("y"), "player.state needs position");
        check(state.get("dimension").getAsString().equals("minecraft:overworld"), "dimension: " + state.get("dimension"));
        boolean hasDiamond = state.getAsJsonArray("inventory").asList().stream()
                .anyMatch(e -> e.getAsJsonObject().get("id").getAsString().equals("minecraft:diamond")
                        && e.getAsJsonObject().get("count").getAsInt() == 5);
        check(hasDiamond, "inventory should contain 5 diamonds: " + state.get("inventory"));

        JsonObject hud = Calls.call(ctx, "hud.read", "{}").getAsJsonObject();
        check(hud.getAsJsonArray("bossbars").size() == 1, "one bossbar expected: " + hud);
        check(hud.getAsJsonArray("bossbars").get(0).getAsJsonObject().get("name").getAsString().equals("Build Progress"), "bossbar name");
        check("Hello Title".equals(hud.get("title").getAsString()), "title: " + hud.get("title"));

        JsonObject sent = Calls.call(ctx, "chat.send", "{\"text\":\"/time set noon\",\"command\":true}").getAsJsonObject();
        check(sent.get("sent").getAsBoolean(), "chat.send should report sent");
        check("INVALID_PARAMS".equals(Calls.error(ctx, "chat.send", "{\"text\":\"\",\"command\":false}").code()), "empty text must be INVALID_PARAMS");
        sp.getServer().runCommand("bossbar remove craftwire:test");
    }
}
```

`AgentGameTests.java`: add `StateChecks.run(ctx, sp);` after `LifecycleChecks.run(ctx, sp);`.

Run: `./gradlew :agent-fabric:runClientGameTest`
Expected: FAIL with `UNKNOWN_METHOD` for `player.state`.

- [ ] **Step 2: Implement the accessors and ItemJson**

`mixin/HudAccessor.java`:
```java
package com.uxplima.craftwire.fabric.mixin;

import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Hud.class)
public interface HudAccessor {
    @Accessor("overlayMessageString") Component craftwire$getOverlayMessage();
    @Accessor("overlayMessageTime") int craftwire$getOverlayMessageTime();
    @Accessor("title") Component craftwire$getTitle();
    @Accessor("subtitle") Component craftwire$getSubtitle();
    @Accessor("titleTime") int craftwire$getTitleTime();
    @Accessor("bossOverlay") BossHealthOverlay craftwire$getBossOverlay();
    @Accessor("isHidden") boolean craftwire$isHidden();
    @Accessor("isHidden") void craftwire$setHidden(boolean hidden);
}
```

`mixin/BossHealthOverlayAccessor.java`:
```java
package com.uxplima.craftwire.fabric.mixin;

import java.util.Map;
import java.util.UUID;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BossHealthOverlay.class)
public interface BossHealthOverlayAccessor {
    @Accessor("events") Map<UUID, LerpingBossEvent> craftwire$getEvents();
}
```

`craftwire-agent.mixins.json`: set `"client": ["BossHealthOverlayAccessor", "HudAccessor"]`.

`ItemJson.java`:
```java
package com.uxplima.craftwire.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

public final class ItemJson {
    private ItemJson() {}

    public static JsonObject of(ItemStack stack, boolean withLore) {
        JsonObject o = new JsonObject();
        o.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        o.addProperty("count", stack.getCount());
        o.addProperty("name", stack.getHoverName().getString());
        o.addProperty("enchanted", stack.hasFoil());
        Minecraft mc = Minecraft.getInstance();
        if (withLore && mc.player != null && mc.level != null) {
            List<Component> lines = stack.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.NORMAL);
            JsonArray lore = new JsonArray();
            for (int i = 1; i < lines.size(); i++) lore.add(lines.get(i).getString());
            o.add("lore", lore);
        }
        return o;
    }
}
```

- [ ] **Step 3: Implement the handlers**

`handlers/PlayerStateHandler.java`:
```java
package com.uxplima.craftwire.fabric.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.fabric.ItemJson;
import com.uxplima.craftwire.fabric.Params;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class PlayerStateHandler {
    private PlayerStateHandler() {}

    static JsonElement read() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) throw Params.notInWorld();
        JsonObject o = new JsonObject();
        o.add("position", vec(p.position()));
        o.addProperty("yaw", p.getYRot());
        o.addProperty("pitch", p.getXRot());
        o.addProperty("health", p.getHealth());
        o.addProperty("gameMode", mc.gameMode == null ? "unknown" : mc.gameMode.getPlayerMode().getName());
        o.addProperty("dimension", mc.level.dimension().identifier().toString());
        o.addProperty("selectedSlot", p.getInventory().getSelectedSlot() + 1);
        o.add("held", ItemJson.of(p.getMainHandItem(), false));
        JsonArray inv = new JsonArray();
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            JsonObject item = ItemJson.of(s, false);
            item.addProperty("slot", i);
            inv.add(item);
        }
        o.add("inventory", inv);
        o.add("target", target(mc));
        return o;
    }

    private static JsonElement target(Minecraft mc) {
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK) {
            JsonObject t = new JsonObject();
            t.addProperty("type", "block");
            BlockPos pos = b.getBlockPos();
            t.add("pos", vec(Vec3.atLowerCornerOf(pos)));
            t.addProperty("block", BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).toString());
            return t;
        }
        if (hit instanceof EntityHitResult e && hit.getType() == HitResult.Type.ENTITY) {
            Entity en = e.getEntity();
            JsonObject t = new JsonObject();
            t.addProperty("type", "entity");
            t.addProperty("entity", BuiltInRegistries.ENTITY_TYPE.getKey(en.getType()).toString());
            t.addProperty("name", en.getName().getString());
            t.addProperty("uuid", en.getUUID().toString());
            return t;
        }
        return JsonNull.INSTANCE;
    }

    static JsonObject vec(Vec3 v) {
        JsonObject o = new JsonObject();
        o.addProperty("x", v.x);
        o.addProperty("y", v.y);
        o.addProperty("z", v.z);
        return o;
    }
}
```

`handlers/ChatSendHandler.java`:
```java
package com.uxplima.craftwire.fabric.handlers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.fabric.Params;
import net.minecraft.client.Minecraft;

final class ChatSendHandler {
    private ChatSendHandler() {}

    static JsonElement send(JsonObject params) {
        String text = Params.optString(params, "text").orElse("").strip();
        boolean command = Params.optBool(params, "command").orElse(false);
        if (text.isEmpty()) throw Params.invalid("text must not be empty");
        if (text.length() > 256) throw Params.invalid("text is longer than 256 characters");
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) throw Params.notInWorld();
        if (command) mc.getConnection().sendCommand(text.startsWith("/") ? text.substring(1) : text);
        else mc.getConnection().sendChat(text);
        JsonObject o = new JsonObject();
        o.addProperty("sent", true);
        return o;
    }
}
```

`handlers/HudReadHandler.java`:
```java
package com.uxplima.craftwire.fabric.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.fabric.Params;
import com.uxplima.craftwire.fabric.mixin.BossHealthOverlayAccessor;
import com.uxplima.craftwire.fabric.mixin.HudAccessor;
import java.util.Comparator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;

final class HudReadHandler {
    private HudReadHandler() {}

    static JsonElement read() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) throw Params.notInWorld();
        HudAccessor hud = (HudAccessor) mc.gui.hud;
        JsonObject o = new JsonObject();

        JsonArray bars = new JsonArray();
        for (LerpingBossEvent e : ((BossHealthOverlayAccessor) hud.craftwire$getBossOverlay()).craftwire$getEvents().values()) {
            JsonObject b = new JsonObject();
            b.addProperty("name", e.getName().getString());
            b.addProperty("progress", e.getProgress());
            b.addProperty("color", e.getColor().name().toLowerCase());
            bars.add(b);
        }
        o.add("bossbars", bars);
        o.add("actionbar", hud.craftwire$getOverlayMessageTime() > 0 ? text(hud.craftwire$getOverlayMessage()) : JsonNull.INSTANCE);
        boolean titleShown = hud.craftwire$getTitleTime() > 0;
        o.add("title", titleShown ? text(hud.craftwire$getTitle()) : JsonNull.INSTANCE);
        o.add("subtitle", titleShown ? text(hud.craftwire$getSubtitle()) : JsonNull.INSTANCE);
        o.add("sidebar", sidebar(mc.level.getScoreboard()));

        JsonArray tab = new JsonArray();
        if (mc.getConnection() != null) {
            for (PlayerInfo info : mc.getConnection().getListedOnlinePlayers()) {
                Component display = info.getTabListDisplayName();
                tab.add(display != null ? display.getString() : info.getProfile().name());
            }
        }
        o.add("tabList", tab);
        o.addProperty("hidden", hud.craftwire$isHidden());
        return o;
    }

    private static JsonElement text(Component c) {
        return c == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(c.getString());
    }

    private static JsonElement sidebar(Scoreboard board) {
        Objective obj = board.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (obj == null) return JsonNull.INSTANCE;
        JsonObject s = new JsonObject();
        s.addProperty("title", obj.getDisplayName().getString());
        JsonArray entries = new JsonArray();
        board.listPlayerScores(obj).stream()
                .sorted(Comparator.comparingInt(PlayerScoreEntry::value).reversed())
                .limit(15)
                .forEach(e -> {
                    JsonObject row = new JsonObject();
                    row.addProperty("name", e.display() != null ? e.display().getString() : e.owner());
                    row.addProperty("value", e.value());
                    entries.add(row);
                });
        s.add("entries", entries);
        return s;
    }
}
```

`handlers/Handlers.java`: replace the body of `registerAll`:
```java
    public static void registerAll(CraftwireAgent agent) {
        var s = agent.scheduler();
        agent.dispatcher().register("player.state", p -> s.call(PlayerStateHandler::read));
        agent.dispatcher().register("chat.send", p -> s.call(() -> ChatSendHandler.send(p)));
        agent.dispatcher().register("hud.read", p -> s.call(HudReadHandler::read));
    }
```

- [ ] **Step 4: Run the gametest to verify it passes**

Run: `./gradlew :agent-fabric:runClientGameTest`
Expected: BUILD SUCCESSFUL. `LifecycleChecks` and `StateChecks` pass.

- [ ] **Step 5: Commit**

```bash
git add agent-fabric
git commit -m "feat(fabric): player.state, chat.send and hud.read handlers"
```

---

### Task 11: `gui.read` and `gui.action`

**Files:**
- Create in `agent-fabric/src/main/java/com/uxplima/craftwire/fabric/`: `handlers/GuiReadHandler.java`, `handlers/GuiActionHandler.java`, `mixin/AbstractContainerScreenAccessor.java`, `mixin/MouseHandlerAccessor.java`
- Modify: `handlers/Handlers.java`, `craftwire-agent.mixins.json`
- Create (gametest): `GuiChecks.java`. Modify: `AgentGameTests.java`

**Interfaces:**
- Consumes: `ItemJson.of` (Task 10), `Params` incl. `Params.invalid` (Task 9), `ClientScheduler.call/delay` (Task 9).
- Produces:
  - `GuiReadHandler.read(): JsonObject` returns `{open:false}` or `{open:true, title, type, slotCount?, slots?:[{slot, id, count, name, enchanted, lore, container:"menu"|"player"}], hovered?:{slot, item?}|null, widgets:[{index, kind, text, x, y, width, height, value?}]}`.
  - `GuiActionHandler.act(JsonObject p, ClientScheduler s): CompletableFuture<JsonElement>` performs the action, waits 2 ticks and returns `GuiReadHandler.read()`.
  - `GuiActionHandler.moveMouseTo(Minecraft mc, double guiX, double guiY)` is used by later tasks.
  - Error codes: `NO_SCREEN_OPEN`, `NOT_A_CONTAINER`, `SLOT_OUT_OF_RANGE`, `WIDGET_NOT_FOUND`, `INVALID_PARAMS`.
- Slot numbers are indexes into `menu.slots`, the same ids that `slotClicked` and the server use.
- Widget `index` counts only `AbstractWidget` children, in `children()` order.

- [ ] **Step 1: Write the failing gametest checks**

`GuiChecks.java`:
```java
package com.uxplima.craftwire.fabric.gametest;

import static com.uxplima.craftwire.fabric.gametest.Calls.check;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

final class GuiChecks {
    private GuiChecks() {}

    private static int slotOf(JsonObject gui, String itemId) {
        for (JsonElement e : gui.getAsJsonArray("slots")) {
            JsonObject s = e.getAsJsonObject();
            if (s.get("id").getAsString().equals(itemId)) return s.get("slot").getAsInt();
        }
        return -1;
    }

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        sp.getServer().runCommand("clear @a");
        sp.getServer().runCommand("give @a minecraft:diamond 5");
        ctx.waitTicks(5);
        ctx.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
        ctx.waitTicks(2);

        JsonObject gui = Calls.call(ctx, "gui.read", "{}").getAsJsonObject();
        check(gui.get("open").getAsBoolean(), "inventory should be open");
        check(gui.get("type").getAsString().equals("InventoryScreen"), "type: " + gui.get("type"));
        int diamond = slotOf(gui, "minecraft:diamond");
        check(diamond >= 0, "diamond slot not found: " + gui);

        JsonObject hovered = Calls.call(ctx, "gui.action", "{\"action\":\"hover\",\"slot\":" + diamond + "}").getAsJsonObject();
        check(hovered.getAsJsonObject("hovered").get("slot").getAsInt() == diamond, "hovered slot: " + hovered.get("hovered"));
        check(hovered.getAsJsonObject("hovered").getAsJsonObject("item").get("id").getAsString().equals("minecraft:diamond"), "hovered item");

        int target = 9; // first main-inventory slot of InventoryMenu
        JsonObject moved = Calls.call(ctx, "gui.action", "{\"action\":\"drag\",\"slot\":" + diamond + ",\"toSlot\":" + target + "}").getAsJsonObject();
        check(slotOf(moved, "minecraft:diamond") == target, "diamond should move to slot 9: " + moved.get("slots"));

        check("SLOT_OUT_OF_RANGE".equals(Calls.error(ctx, "gui.action", "{\"action\":\"click\",\"slot\":999}").code()), "slot 999");

        Calls.call(ctx, "gui.action", "{\"action\":\"close\"}");
        ctx.waitTicks(2);
        check(!Calls.call(ctx, "gui.read", "{}").getAsJsonObject().get("open").getAsBoolean(), "closed");
        // Review Focus #5: acting on a stale slot after the screen closed is an error, not a crash.
        check("NO_SCREEN_OPEN".equals(Calls.error(ctx, "gui.action", "{\"action\":\"click\",\"slot\":" + target + "}").code()), "stale slot after close");

        ctx.setScreen(() -> new PauseScreen(true));
        ctx.waitTicks(2);
        JsonObject pause = Calls.call(ctx, "gui.read", "{}").getAsJsonObject();
        check(pause.getAsJsonArray("widgets").size() > 0, "pause screen widgets: " + pause);
        check("WIDGET_NOT_FOUND".equals(Calls.error(ctx, "gui.action", "{\"action\":\"click_widget\",\"widget\":\"No Such Button\"}").code()), "missing widget");
        JsonObject after = Calls.call(ctx, "gui.action", "{\"action\":\"click_widget\",\"widget\":\"Back to Game\"}").getAsJsonObject();
        check(!after.get("open").getAsBoolean(), "Back to Game should close the pause screen: " + after);
    }
}
```

`AgentGameTests.java`: add `GuiChecks.run(ctx, sp);` after `StateChecks.run(ctx, sp);`.

Run: `./gradlew :agent-fabric:runClientGameTest`
Expected: FAIL with `UNKNOWN_METHOD` for `gui.read`.

- [ ] **Step 2: Implement the accessors**

`mixin/AbstractContainerScreenAccessor.java`:
```java
package com.uxplima.craftwire.fabric.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
    @Accessor("leftPos") int craftwire$getLeftPos();
    @Accessor("topPos") int craftwire$getTopPos();
    @Accessor("hoveredSlot") Slot craftwire$getHoveredSlot();
    @Invoker("slotClicked") void craftwire$slotClicked(Slot slot, int slotId, int button, ContainerInput input);
}
```

`mixin/MouseHandlerAccessor.java`:
```java
package com.uxplima.craftwire.fabric.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
    @Accessor("xpos") void craftwire$setXpos(double x);
    @Accessor("ypos") void craftwire$setYpos(double y);
}
```

`craftwire-agent.mixins.json`: `"client": ["AbstractContainerScreenAccessor", "BossHealthOverlayAccessor", "HudAccessor", "MouseHandlerAccessor"]`.

- [ ] **Step 3: Implement the handlers**

`handlers/GuiReadHandler.java`:
```java
package com.uxplima.craftwire.fabric.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.fabric.ItemJson;
import com.uxplima.craftwire.fabric.mixin.AbstractContainerScreenAccessor;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class GuiReadHandler {
    private GuiReadHandler() {}

    public static JsonObject read() {
        Screen s = Minecraft.getInstance().gui.screen();
        JsonObject o = new JsonObject();
        o.addProperty("open", s != null);
        if (s == null) return o;
        o.addProperty("title", s.getTitle().getString());
        o.addProperty("type", s.getClass().getSimpleName());

        if (s instanceof AbstractContainerScreen<?> acs) {
            List<Slot> slots = acs.getMenu().slots;
            o.addProperty("slotCount", slots.size());
            JsonArray arr = new JsonArray();
            for (int i = 0; i < slots.size(); i++) {
                ItemStack stack = slots.get(i).getItem();
                if (stack.isEmpty()) continue;
                JsonObject item = ItemJson.of(stack, true);
                item.addProperty("slot", i);
                item.addProperty("container", slots.get(i).container instanceof Inventory ? "player" : "menu");
                arr.add(item);
            }
            o.add("slots", arr);
            Slot hovered = ((AbstractContainerScreenAccessor) acs).craftwire$getHoveredSlot();
            if (hovered == null) {
                o.add("hovered", JsonNull.INSTANCE);
            } else {
                JsonObject h = new JsonObject();
                h.addProperty("slot", slots.indexOf(hovered));
                if (hovered.hasItem()) h.add("item", ItemJson.of(hovered.getItem(), true));
                o.add("hovered", h);
            }
        }

        JsonArray widgets = new JsonArray();
        int index = 0;
        for (GuiEventListener child : s.children()) {
            if (!(child instanceof AbstractWidget w)) continue;
            JsonObject wj = new JsonObject();
            wj.addProperty("index", index++);
            wj.addProperty("kind", w.getClass().getSimpleName());
            wj.addProperty("text", w.getMessage().getString());
            wj.addProperty("x", w.getX());
            wj.addProperty("y", w.getY());
            wj.addProperty("width", w.getWidth());
            wj.addProperty("height", w.getHeight());
            if (w instanceof EditBox box) wj.addProperty("value", box.getValue());
            widgets.add(wj);
        }
        o.add("widgets", widgets);
        return o;
    }
}
```

`handlers/GuiActionHandler.java`:
```java
package com.uxplima.craftwire.fabric.handlers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.Window;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.fabric.ClientScheduler;
import com.uxplima.craftwire.fabric.Params;
import com.uxplima.craftwire.fabric.mixin.AbstractContainerScreenAccessor;
import com.uxplima.craftwire.fabric.mixin.MouseHandlerAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;

public final class GuiActionHandler {
    private GuiActionHandler() {}

    public static CompletableFuture<JsonElement> act(JsonObject p, ClientScheduler s) {
        return s.call(() -> {
                    perform(p);
                    return Boolean.TRUE;
                })
                .thenCompose(v -> s.delay(2))
                .thenCompose(v -> s.call(() -> (JsonElement) GuiReadHandler.read()));
    }

    private static void perform(JsonObject p) {
        String action = Params.optString(p, "action").orElseThrow(() -> Params.invalid("action is required"));
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.gui.screen();
        if (screen == null) {
            throw new AgentError("NO_SCREEN_OPEN", "No screen is open.",
                    "Open a menu first (e.g. chat {action:'command', text:'/builders crew'}), then wait_for {condition:'screen_open'}.");
        }
        switch (action) {
            case "close" -> screen.onClose();
            case "click_widget" -> clickWidget(mc, screen, findWidget(screen, p));
            case "type" -> type(mc, screen, p);
            case "hover", "click", "right_click", "shift_click", "drag" -> slotAction(mc, screen, action, p);
            default -> throw Params.invalid("unknown action: " + action);
        }
    }

    private static void slotAction(Minecraft mc, Screen screen, String action, JsonObject p) {
        if (!(screen instanceof AbstractContainerScreen<?> acs)) {
            throw new AgentError("NOT_A_CONTAINER", "The open screen (" + screen.getClass().getSimpleName() + ") has no item slots.",
                    "Use click_widget for buttons; gui_read lists widgets.");
        }
        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) acs;
        List<Slot> slots = acs.getMenu().slots;
        int id = slotId(slots, p, "slot");
        Slot slot = slots.get(id);
        moveMouseTo(mc, acc.craftwire$getLeftPos() + slot.x + 8, acc.craftwire$getTopPos() + slot.y + 8);
        switch (action) {
            case "click" -> acc.craftwire$slotClicked(slot, id, GLFW.GLFW_MOUSE_BUTTON_LEFT, ContainerInput.PICKUP);
            case "right_click" -> acc.craftwire$slotClicked(slot, id, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ContainerInput.PICKUP);
            case "shift_click" -> acc.craftwire$slotClicked(slot, id, GLFW.GLFW_MOUSE_BUTTON_LEFT, ContainerInput.QUICK_MOVE);
            case "drag" -> {
                int toId = slotId(slots, p, "toSlot");
                Slot to = slots.get(toId);
                acc.craftwire$slotClicked(slot, id, GLFW.GLFW_MOUSE_BUTTON_LEFT, ContainerInput.PICKUP);
                acc.craftwire$slotClicked(to, toId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ContainerInput.PICKUP);
                moveMouseTo(mc, acc.craftwire$getLeftPos() + to.x + 8, acc.craftwire$getTopPos() + to.y + 8);
            }
            default -> { /* hover: moving the mouse is the whole action */ }
        }
    }

    private static int slotId(List<Slot> slots, JsonObject p, String key) {
        int id = Params.optInt(p, key).orElseThrow(() -> Params.invalid(key + " is required for this action"));
        if (id < 0 || id >= slots.size()) {
            throw new AgentError("SLOT_OUT_OF_RANGE", "Slot " + id + " does not exist; this screen has " + slots.size() + " slots.",
                    "Call gui_read again: the screen may have changed since you last read it.");
        }
        return id;
    }

    private static List<AbstractWidget> widgets(Screen screen) {
        List<AbstractWidget> out = new ArrayList<>();
        for (GuiEventListener child : screen.children()) if (child instanceof AbstractWidget w) out.add(w);
        return out;
    }

    private static AbstractWidget findWidget(Screen screen, JsonObject p) {
        String key = Params.optString(p, "widget").orElseThrow(() -> Params.invalid("widget is required (index or text)"));
        List<AbstractWidget> all = widgets(screen);
        if (key.matches("\\d+")) {
            int i = Integer.parseInt(key);
            if (i < all.size()) return all.get(i);
        } else {
            String needle = key.toLowerCase(Locale.ROOT);
            for (AbstractWidget w : all) if (w.getMessage().getString().toLowerCase(Locale.ROOT).contains(needle)) return w;
        }
        List<String> names = all.stream().map(w -> w.getMessage().getString()).filter(t -> !t.isBlank()).toList();
        throw new AgentError("WIDGET_NOT_FOUND", "No widget matches \"" + key + "\".", "Available widgets: " + names);
    }

    private static void clickWidget(Minecraft mc, Screen screen, AbstractWidget w) {
        double cx = w.getX() + w.getWidth() / 2.0;
        double cy = w.getY() + w.getHeight() / 2.0;
        moveMouseTo(mc, cx, cy);
        MouseButtonEvent event = new MouseButtonEvent(cx, cy, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
        screen.mouseClicked(event, false);
        screen.mouseReleased(event);
    }

    private static void type(Minecraft mc, Screen screen, JsonObject p) {
        String text = Params.optString(p, "text").orElseThrow(() -> Params.invalid("text is required for type"));
        if (p.has("widget")) clickWidget(mc, screen, findWidget(screen, p));
        text.codePoints().forEach(cp -> screen.charTyped(new CharacterEvent(cp)));
    }

    /** Moves the logical (and, when the window has focus, the real) cursor to GUI-scaled coordinates. */
    public static void moveMouseTo(Minecraft mc, double guiX, double guiY) {
        Window window = mc.getWindow();
        double sx = guiX * window.getScreenWidth() / (double) window.getGuiScaledWidth();
        double sy = guiY * window.getScreenHeight() / (double) window.getGuiScaledHeight();
        GLFW.glfwSetCursorPos(window.handle(), sx, sy);   // GLFW ignores this when unfocused: the user's cursor is never hijacked
        MouseHandlerAccessor mouse = (MouseHandlerAccessor) mc.mouseHandler;
        mouse.craftwire$setXpos(sx);
        mouse.craftwire$setYpos(sy);
    }
}
```

`handlers/Handlers.java` — add to `registerAll` (`GuiReadHandler.read()` returns `JsonObject`, so widen it to `JsonElement` for the `Handler` type; every other handler already returns `JsonElement`):
```java
        agent.dispatcher().register("gui.read", p -> s.call(() -> (com.google.gson.JsonElement) GuiReadHandler.read()));
        agent.dispatcher().register("gui.action", p -> GuiActionHandler.act(p, s));
```

- [ ] **Step 4: Run the gametest to verify it passes**

Run: `./gradlew :agent-fabric:runClientGameTest`
Expected: BUILD SUCCESSFUL, with Lifecycle, State and Gui checks passing.

- [ ] **Step 5: Commit**

```bash
git add agent-fabric
git commit -m "feat(fabric): gui.read and gui.action (hover, click, drag, widgets, typing)"
```

---

### Task 12: Camera override, `camera` and `screenshot`

**Files:**
- Create in `agent-fabric/src/main/java/com/uxplima/craftwire/fabric/`: `camera/CameraOverride.java`, `camera/CameraMath.java`, `mixin/CameraMixin.java`, `handlers/CameraHandler.java`, `handlers/ScreenshotHandler.java`
- Modify: `handlers/Handlers.java`, `craftwire-agent.mixins.json`
- Test: `agent-fabric/src/test/java/com/uxplima/craftwire/fabric/camera/CameraMathTest.java`
- Create (gametest): `CaptureChecks.java`. Modify: `AgentGameTests.java`

**Interfaces:**
- Consumes: `HudAccessor.craftwire$setHidden/isHidden` (Task 10), `CraftwireAgent.setCaptureInProgress` and `scheduler()` (Task 9), `Params` (Tasks 9 and 11).
- Produces:
  ```java
  final class CameraOverride { static final CameraOverride INSTANCE; record Pose(double x, double y, double z, float yaw, float pitch) {}
      Pose get(); void set(Pose p); void clear(); }
  final class CameraMath { record Vec(double x, double y, double z) {} record Angles(float yaw, float pitch) {}
      static Angles lookAt(Vec from, Vec to); static Vec direction(float yaw, float pitch);
      static Vec frame(Vec min, Vec max, float yaw, float pitch, double fovDegrees, double scale); }
  ```
  - `camera` → `{active, x, y, z, yaw, pitch, warning?}`
  - `screenshot` → `{mime, data, width, height, fullWidth, fullHeight, savedPath?}`; errors `SAVE_FAILED`, `INVALID_PARAMS`.
  - Restoration contract: the returned future completes only **after** the HUD flag, camera override, FOV and capture flag have been restored.
- Yaw follows the Minecraft convention: 0 = +Z (south), 90 = −X (west). Pitch is positive looking down.
- The override is render-only: the server never sees it, and the player does not move.

- [ ] **Step 1: Write the failing CameraMath unit test**

`CameraMathTest.java`:
```java
package com.uxplima.craftwire.fabric.camera;

import static org.junit.jupiter.api.Assertions.*;

import com.uxplima.craftwire.fabric.camera.CameraMath.Angles;
import com.uxplima.craftwire.fabric.camera.CameraMath.Vec;
import org.junit.jupiter.api.Test;

class CameraMathTest {
    static final Vec O = new Vec(0, 0, 0);

    @Test
    void lookAtUsesMinecraftYaw() {
        assertEquals(0f, CameraMath.lookAt(O, new Vec(0, 0, 5)).yaw(), 1e-4);    // south
        assertEquals(90f, CameraMath.lookAt(O, new Vec(-5, 0, 0)).yaw(), 1e-4);  // west
        assertEquals(-90f, CameraMath.lookAt(O, new Vec(5, 0, 0)).yaw(), 1e-4);  // east
    }

    @Test
    void lookAtPitchIsPositiveDownwards() {
        assertEquals(45f, CameraMath.lookAt(O, new Vec(0, -5, 5)).pitch(), 1e-4);
        assertEquals(-90f, CameraMath.lookAt(O, new Vec(0, 5, 0)).pitch(), 1e-4);
    }

    @Test
    void directionIsInverseOfLookAt() {
        Vec d = CameraMath.direction(30f, 20f);
        Angles a = CameraMath.lookAt(O, d);
        assertEquals(30f, a.yaw(), 1e-3);
        assertEquals(20f, a.pitch(), 1e-3);
    }

    @Test
    void framedCameraLooksAtTheBoxCentreFromFarEnough() {
        Vec min = new Vec(-8, 60, -8), max = new Vec(8, 76, 8);
        Vec cam = CameraMath.frame(min, max, 45f, 30f, 70, 1.0);
        Angles a = CameraMath.lookAt(cam, new Vec(0, 68, 0));
        assertEquals(45f, a.yaw(), 1e-3);
        assertEquals(30f, a.pitch(), 1e-3);
        double dist = Math.sqrt(cam.x() * cam.x() + (cam.y() - 68) * (cam.y() - 68) + cam.z() * cam.z());
        double radius = Math.sqrt(16 * 16 * 3) / 2;
        assertTrue(dist >= radius / Math.tan(Math.toRadians(35)) - 1e-6, "too close: " + dist);
    }
}
```

Run: `./gradlew :agent-fabric:test`
Expected: FAIL (`CameraMath` missing).

- [ ] **Step 2: Implement CameraMath and CameraOverride**

`camera/CameraMath.java`:
```java
package com.uxplima.craftwire.fabric.camera;

/** Pure camera geometry (no Minecraft types) so it can be unit tested. */
public final class CameraMath {
    public record Vec(double x, double y, double z) {}

    public record Angles(float yaw, float pitch) {}

    private CameraMath() {}

    public static Angles lookAt(Vec from, Vec to) {
        double dx = to.x() - from.x(), dy = to.y() - from.y(), dz = to.z() - from.z();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return new Angles(yaw, pitch);
    }

    public static Vec direction(float yaw, float pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new Vec(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    public static Vec frame(Vec min, Vec max, float yaw, float pitch, double fovDegrees, double scale) {
        Vec c = new Vec((min.x() + max.x()) / 2, (min.y() + max.y()) / 2, (min.z() + max.z()) / 2);
        double dx = max.x() - min.x(), dy = max.y() - min.y(), dz = max.z() - min.z();
        double radius = Math.sqrt(dx * dx + dy * dy + dz * dz) / 2;
        double distance = radius / Math.tan(Math.toRadians(fovDegrees) / 2) * scale;
        Vec d = direction(yaw, pitch);
        return new Vec(c.x() - d.x() * distance, c.y() - d.y() * distance, c.z() - d.z() * distance);
    }
}
```

`camera/CameraOverride.java`:
```java
package com.uxplima.craftwire.fabric.camera;

/** Render-only camera pose applied after vanilla camera setup each frame. Null = vanilla camera. */
public final class CameraOverride {
    public static final CameraOverride INSTANCE = new CameraOverride();

    public record Pose(double x, double y, double z, float yaw, float pitch) {}

    private volatile Pose pose;

    private CameraOverride() {}

    public Pose get() {
        return pose;
    }

    public void set(Pose p) {
        pose = p;
    }

    public void clear() {
        pose = null;
    }
}
```

Run: `./gradlew :agent-fabric:test`
Expected: PASS (4 tests).

- [ ] **Step 3: Implement the camera mixin**

`mixin/CameraMixin.java`:
```java
package com.uxplima.craftwire.fabric.mixin;

import com.uxplima.craftwire.fabric.camera.CameraOverride;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow private boolean detached;

    @Shadow protected abstract void setPosition(double x, double y, double z);

    @Shadow protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "update", at = @At("TAIL"))
    private void craftwire$applyOverride(DeltaTracker deltaTracker, CallbackInfo ci) {
        CameraOverride.Pose p = CameraOverride.INSTANCE.get();
        if (p == null) return;
        setPosition(p.x(), p.y(), p.z());
        setRotation(p.yaw(), p.pitch());
        detached = true;   // render the local player's body, like third person
    }
}
```

`craftwire-agent.mixins.json`: `"client": ["AbstractContainerScreenAccessor", "BossHealthOverlayAccessor", "CameraMixin", "HudAccessor", "MouseHandlerAccessor"]`.

- [ ] **Step 4: Write the failing capture gametest**

`CaptureChecks.java`:
```java
package com.uxplima.craftwire.fabric.gametest;

import static com.uxplima.craftwire.fabric.gametest.Calls.check;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.fabric.CraftwireClient;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.phys.Vec3;

final class CaptureChecks {
    private CaptureChecks() {}

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) throws java.io.IOException {
        Path dir = Files.createTempDirectory("craftwire-shots");
        String save = dir.resolve("inv.png").toString().replace("\\", "\\\\");

        // Review Focus #4: capture with hud:false while a GUI is open; everything is restored afterwards.
        ctx.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
        ctx.waitTicks(2);
        JsonObject shot = Calls.call(ctx, "screenshot", "{\"hud\":false,\"maxSize\":512,\"savePath\":\"" + save + "\"}").getAsJsonObject();
        check(shot.get("mime").getAsString().startsWith("image/"), "mime");
        check(Math.max(shot.get("width").getAsInt(), shot.get("height").getAsInt()) <= 512, "downscaled: " + shot);
        check(shot.get("fullWidth").getAsInt() >= shot.get("width").getAsInt(), "full size");
        check(Files.size(Path.of(shot.get("savedPath").getAsString())) > 1000, "saved PNG should exist");
        check(!shot.get("data").getAsString().isEmpty(), "base64 data");
        check(Calls.call(ctx, "gui.read", "{}").getAsJsonObject().get("open").getAsBoolean(), "GUI must stay open");
        check(!Calls.call(ctx, "hud.read", "{}").getAsJsonObject().get("hidden").getAsBoolean(), "HUD must be restored");
        check(!CraftwireClient.agent().isCaptureInProgress(), "capture flag cleared");

        // Failure path: an unwritable savePath still restores state.
        Path blocker = Files.createFile(dir.resolve("blocker"));
        String bad = blocker.resolve("x.png").toString().replace("\\", "\\\\");
        Calls.error(ctx, "screenshot", "{\"hud\":false,\"savePath\":\"" + bad + "\"}");
        check(!Calls.call(ctx, "hud.read", "{}").getAsJsonObject().get("hidden").getAsBoolean(), "HUD restored after failure");
        check(!CraftwireClient.agent().isCaptureInProgress(), "capture flag cleared after failure");
        Calls.call(ctx, "gui.action", "{\"action\":\"close\"}");

        // Camera override moves only the render camera.
        Vec3 eye = ctx.computeOnClient(mc -> mc.player.getEyePosition());
        Calls.call(ctx, "camera", String.format(java.util.Locale.ROOT,
                "{\"action\":\"set\",\"x\":%f,\"y\":%f,\"z\":%f,\"yaw\":0,\"pitch\":45}", eye.x, eye.y + 10, eye.z));
        ctx.waitTicks(2);
        Vec3 cam = ctx.computeOnClient(mc -> mc.gameRenderer.mainCamera().position());
        check(Math.abs(cam.y - (eye.y + 10)) < 0.01, "camera y should be overridden: " + cam);
        check(ctx.computeOnClient(mc -> mc.player.getEyePosition()).distanceTo(eye) < 0.01, "player must not move");

        JsonObject looked = Calls.call(ctx, "camera", String.format(java.util.Locale.ROOT,
                "{\"action\":\"look_at\",\"target\":{\"x\":%f,\"y\":%f,\"z\":%f}}", eye.x, eye.y, eye.z)).getAsJsonObject();
        check(Math.abs(looked.get("pitch").getAsFloat() - 90f) < 0.5, "looking straight down: " + looked);

        Calls.call(ctx, "camera", "{\"action\":\"reset\"}");
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> mc.gameRenderer.mainCamera().position()).distanceTo(eye) < 0.5, "camera back at the eyes");

        // A per-capture camera does not leak into later frames.
        Calls.call(ctx, "screenshot", String.format(java.util.Locale.ROOT,
                "{\"maxSize\":256,\"camera\":{\"x\":%f,\"y\":%f,\"z\":%f,\"yaw\":90,\"pitch\":20,\"fov\":50}}", eye.x, eye.y + 20, eye.z));
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> mc.gameRenderer.mainCamera().position()).distanceTo(eye) < 0.5, "per-capture camera cleared");
        check(ctx.computeOnClient(mc -> mc.options.fov().get()) != 50, "per-capture fov restored");
    }
}
```

`AgentGameTests.java`: after `GuiChecks.run(ctx, sp);` add (`runTest` cannot throw checked exceptions):
```java
            try {
                CaptureChecks.run(ctx, sp);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
```

Run: `./gradlew :agent-fabric:runClientGameTest`
Expected: FAIL with `UNKNOWN_METHOD` for `screenshot`.

- [ ] **Step 5: Implement the camera handler**

`handlers/CameraHandler.java`:
```java
package com.uxplima.craftwire.fabric.handlers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.fabric.Params;
import com.uxplima.craftwire.fabric.camera.CameraMath;
import com.uxplima.craftwire.fabric.camera.CameraOverride;
import com.uxplima.craftwire.fabric.camera.CameraOverride.Pose;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class CameraHandler {
    private CameraHandler() {}

    static JsonElement handle(JsonObject p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) throw Params.notInWorld();
        String action = Params.optString(p, "action").orElseThrow(() -> Params.invalid("action is required"));
        Pose current = currentPose(mc);
        switch (action) {
            case "set" -> CameraOverride.INSTANCE.set(new Pose(
                    Params.optDouble(p, "x").orElse(current.x()),
                    Params.optDouble(p, "y").orElse(current.y()),
                    Params.optDouble(p, "z").orElse(current.z()),
                    Params.optDouble(p, "yaw").map(Double::floatValue).orElse(current.yaw()),
                    Params.optDouble(p, "pitch").map(Double::floatValue).orElse(current.pitch())));
            case "look_at" -> {
                CameraMath.Vec target = vec(p.getAsJsonObject("target"), "target");
                CameraMath.Angles a = CameraMath.lookAt(new CameraMath.Vec(current.x(), current.y(), current.z()), target);
                CameraOverride.INSTANCE.set(new Pose(current.x(), current.y(), current.z(), a.yaw(), a.pitch()));
            }
            case "frame_area" -> {
                JsonObject area = p.getAsJsonObject("area");
                if (area == null) throw Params.invalid("area {min,max} is required");
                frame(mc, p, current, vec(area.getAsJsonObject("min"), "area.min"), vec(area.getAsJsonObject("max"), "area.max"));
            }
            case "frame_entity" -> {
                Entity e = findEntity(mc, Params.optString(p, "entity").orElseThrow(() -> Params.invalid("entity is required")));
                AABB box = e.getBoundingBox().inflate(1.0);
                frame(mc, p, current, new CameraMath.Vec(box.minX, box.minY, box.minZ), new CameraMath.Vec(box.maxX, box.maxY, box.maxZ));
            }
            case "freecam_on" -> CameraOverride.INSTANCE.set(current);
            case "freecam_off", "reset" -> CameraOverride.INSTANCE.clear();
            default -> throw Params.invalid("unknown action: " + action);
        }
        return describe(mc);
    }

    private static void frame(Minecraft mc, JsonObject p, Pose current, CameraMath.Vec min, CameraMath.Vec max) {
        float yaw = Params.optDouble(p, "yaw").map(Double::floatValue).orElse(current.yaw());
        float pitch = Params.optDouble(p, "pitch").map(Double::floatValue).orElse(30f);
        double scale = Params.optDouble(p, "distanceScale").orElse(1.0);
        CameraMath.Vec pos = CameraMath.frame(min, max, yaw, pitch, mc.options.fov().get(), scale);
        CameraOverride.INSTANCE.set(new Pose(pos.x(), pos.y(), pos.z(), yaw, pitch));
    }

    private static Entity findEntity(Minecraft mc, String key) {
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.getUUID().toString().equalsIgnoreCase(key) || e.getName().getString().equalsIgnoreCase(key)) return e;
        }
        throw new AgentError("ENTITY_NOT_FOUND", "No loaded entity matches \"" + key + "\".",
                "Use a UUID or exact name; the entity must be within render distance.");
    }

    private static CameraMath.Vec vec(JsonObject o, String name) {
        if (o == null || !o.has("x") || !o.has("y") || !o.has("z")) throw Params.invalid(name + " needs x, y and z");
        return new CameraMath.Vec(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble());
    }

    static Pose currentPose(Minecraft mc) {
        Pose o = CameraOverride.INSTANCE.get();
        if (o != null) return o;
        Camera cam = mc.gameRenderer.mainCamera();
        Vec3 pos = cam.position();
        return new Pose(pos.x, pos.y, pos.z, cam.yRot(), cam.xRot());
    }

    private static JsonObject describe(Minecraft mc) {
        Pose p = currentPose(mc);
        JsonObject o = new JsonObject();
        o.addProperty("active", CameraOverride.INSTANCE.get() != null);
        o.addProperty("x", p.x());
        o.addProperty("y", p.y());
        o.addProperty("z", p.z());
        o.addProperty("yaw", p.yaw());
        o.addProperty("pitch", p.pitch());
        double limit = mc.options.renderDistance().get() * 16.0;
        double dist = mc.player.getEyePosition().distanceTo(new Vec3(p.x(), p.y(), p.z()));
        if (dist > limit) o.addProperty("warning", String.format("Camera is %.0f blocks from the player; chunks beyond render distance (%.0f) are not drawn.", dist, limit));
        return o;
    }
}
```

- [ ] **Step 6: Implement the screenshot handler**

`handlers/ScreenshotHandler.java`:
```java
package com.uxplima.craftwire.fabric.handlers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.fabric.ClientScheduler;
import com.uxplima.craftwire.fabric.CraftwireAgent;
import com.uxplima.craftwire.fabric.Params;
import com.uxplima.craftwire.fabric.camera.CameraOverride;
import com.uxplima.craftwire.fabric.mixin.HudAccessor;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

final class ScreenshotHandler {
    private ScreenshotHandler() {}

    private record Saved(boolean hudHidden, CameraOverride.Pose pose, int fov) {
        static Saved of(Minecraft mc) {
            return new Saved(((HudAccessor) mc.gui.hud).craftwire$isHidden(), CameraOverride.INSTANCE.get(), mc.options.fov().get());
        }

        void restore(Minecraft mc) {
            ((HudAccessor) mc.gui.hud).craftwire$setHidden(hudHidden);
            if (pose == null) CameraOverride.INSTANCE.clear();
            else CameraOverride.INSTANCE.set(pose);
            if (mc.options.fov().get() != fov) mc.options.fov().set(fov);
        }
    }

    static CompletableFuture<JsonElement> capture(JsonObject p, CraftwireAgent agent) {
        boolean hud = Params.optBool(p, "hud").orElse(true);
        int maxSize = Params.optInt(p, "maxSize").orElse(1600);
        String savePath = Params.optString(p, "savePath").orElse(null);
        String format = Params.optString(p, "format").orElse("auto");
        JsonObject camera = p.has("camera") && p.get("camera").isJsonObject() ? p.getAsJsonObject("camera") : null;
        ClientScheduler s = agent.scheduler();

        // Validate everything before touching game state, so a bad request never leaves the HUD hidden.
        CameraOverride.Pose pose = null;
        Integer fov = null;
        if (camera != null) {
            for (String k : new String[] {"x", "y", "z", "yaw", "pitch"}) {
                if (!camera.has(k)) throw Params.invalid("camera." + k + " is required");
            }
            pose = new CameraOverride.Pose(camera.get("x").getAsDouble(), camera.get("y").getAsDouble(), camera.get("z").getAsDouble(),
                    camera.get("yaw").getAsFloat(), camera.get("pitch").getAsFloat());
            fov = camera.has("fov") ? camera.get("fov").getAsInt() : null;
        }
        final CameraOverride.Pose capturePose = pose;
        final Integer captureFov = fov;

        CompletableFuture<Saved> prepared = s.call(() -> {
            Minecraft mc = Minecraft.getInstance();
            Saved saved = Saved.of(mc);
            agent.setCaptureInProgress(true);
            if (!hud) ((HudAccessor) mc.gui.hud).craftwire$setHidden(true);
            if (capturePose != null) CameraOverride.INSTANCE.set(capturePose);
            if (captureFov != null) mc.options.fov().set(captureFov);
            return saved;
        });

        return prepared.thenCompose(saved -> s.delay(3)
                        .thenCompose(v -> grab(s))
                        // Always restore (success or failure) and only then complete, so callers observe restored state.
                        .handle((file, err) -> s.call(() -> {
                            saved.restore(Minecraft.getInstance());
                            agent.setCaptureInProgress(false);
                            return Boolean.TRUE;
                        }).thenApply(done -> {
                            if (err != null) throw new java.util.concurrent.CompletionException(err);
                            return file;
                        }))
                        .thenCompose(f -> f))
                .thenApplyAsync(file -> encode(file, maxSize, savePath, format));
    }

    private static CompletableFuture<Path> grab(ClientScheduler s) {
        CompletableFuture<Path> out = new CompletableFuture<>();
        s.call(() -> {
            Minecraft mc = Minecraft.getInstance();
            Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
                try {
                    Path tmp = Files.createTempFile("craftwire-", ".png");
                    image.writeToFile(tmp);
                    out.complete(tmp);
                } catch (IOException | RuntimeException e) {
                    out.completeExceptionally(e);
                } finally {
                    image.close();
                }
            });
            return Boolean.TRUE;
        }).exceptionally(e -> {
            out.completeExceptionally(e);
            return Boolean.FALSE;
        });
        return out;
    }

    private static JsonElement encode(Path file, int maxSize, String savePath, String format) {
        try {
            BufferedImage full = ImageIO.read(file.toFile());
            String saved = null;
            try {
                if (savePath != null) {
                    Path target = Path.of(savePath).toAbsolutePath();
                    try {
                        if (target.getParent() != null) Files.createDirectories(target.getParent());
                        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        throw new AgentError("SAVE_FAILED", "Could not write " + target + ": " + e.getMessage(),
                                "Pass a writable savePath; missing directories are created automatically.");
                    }
                    saved = target.toString();
                }
            } finally {
                Files.deleteIfExists(file);
            }
            double scale = Math.min(1.0, maxSize / (double) Math.max(full.getWidth(), full.getHeight()));
            int w = Math.max(1, (int) Math.round(full.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(full.getHeight() * scale));
            BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = small.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(full, 0, 0, w, h, null);
            g.dispose();

            byte[] bytes = png(small);
            String mime = "image/png";
            if ("jpeg".equals(format) || ("auto".equals(format) && bytes.length > 1_500_000)) {
                bytes = jpeg(small, 0.9f);
                mime = "image/jpeg";
            }
            JsonObject o = new JsonObject();
            o.addProperty("mime", mime);
            o.addProperty("data", Base64.getEncoder().encodeToString(bytes));
            o.addProperty("width", w);
            o.addProperty("height", h);
            o.addProperty("fullWidth", full.getWidth());
            o.addProperty("fullHeight", full.getHeight());
            if (saved != null) o.addProperty("savedPath", saved);
            return o;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ImageIO.write(img, "png", buf);
        return buf.toByteArray();
    }

    private static byte[] jpeg(BufferedImage img, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(buf)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return buf.toByteArray();
    }
}
```

`handlers/Handlers.java`: add to `registerAll`:
```java
        agent.dispatcher().register("camera", p -> s.call(() -> CameraHandler.handle(p)));
        agent.dispatcher().register("screenshot", p -> ScreenshotHandler.capture(p, agent));
```

- [ ] **Step 7: Run all agent-fabric tests**

Run: `./gradlew :agent-fabric:test :agent-fabric:runClientGameTest`
Expected: BUILD SUCCESSFUL. The CameraMath unit tests and every gametest check pass.

- [ ] **Step 8: Commit**

```bash
git add agent-fabric
git commit -m "feat(fabric): render-only camera override, camera tool and screenshot capture"
```

---

### Task 13: `input` and `client.settings`

**Files:**
- Create in `agent-fabric/src/main/java/com/uxplima/craftwire/fabric/handlers/`: `InputHandler.java`, `ClientSettingsHandler.java`
- Modify: `handlers/Handlers.java`
- Create (gametest): `InputSettingsChecks.java`. Modify: `AgentGameTests.java`

**Interfaces:**
- Consumes: `Params`, `ClientScheduler.call/delay` (Task 9), `HudAccessor` (Task 10).
- Produces:
  - `input {keys?, mode, durationMs?, look?:{yaw?,pitch?}, hotbar?}` → `{keys:[…], mode, yaw, pitch, selectedSlot}`
  - `client.settings {guiScale?, fov?, renderDistance?, hideHud?, windowSize?}` → `{guiScale, fov, renderDistance, hideHud, windowSize:{width,height}}`
- Key names: `forward back left right jump sneak sprint attack use pick drop inventory swap_hands chat command player_list perspective hide_gui screenshot`. These map to `Options.key*` fields. An unknown name returns `INVALID_PARAMS` and lists the valid names.
- `press` clicks the mapping once. With `durationMs > 0` it also holds the key down and releases it after `ceil(durationMs / 50)` ticks.

- [ ] **Step 1: Write the failing gametest checks**

`InputSettingsChecks.java`:
```java
package com.uxplima.craftwire.fabric.gametest;

import static com.uxplima.craftwire.fabric.gametest.Calls.check;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

final class InputSettingsChecks {
    private InputSettingsChecks() {}

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        sp.getServer().runCommand("gamemode survival @a");
        ctx.waitTicks(2);

        JsonObject r = Calls.call(ctx, "input", "{\"hotbar\":3}").getAsJsonObject();
        check(r.get("selectedSlot").getAsInt() == 3, "hotbar 3: " + r);

        float yawBefore = ctx.computeOnClient(mc -> mc.player.getYRot());
        Calls.call(ctx, "input", "{\"look\":{\"yaw\":90,\"pitch\":10}}");
        check(Math.abs(ctx.computeOnClient(mc -> mc.player.getYRot()) - (yawBefore + 90)) < 0.01, "yaw +90");

        Calls.call(ctx, "input", "{\"keys\":[\"sneak\"],\"mode\":\"hold\"}");
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> mc.player.isShiftKeyDown()), "sneak held");
        Calls.call(ctx, "input", "{\"keys\":[\"sneak\"],\"mode\":\"release\"}");
        ctx.waitTicks(2);
        check(!ctx.computeOnClient(mc -> mc.player.isShiftKeyDown()), "sneak released");

        Calls.call(ctx, "input", "{\"keys\":[\"inventory\"]}");
        ctx.waitTicks(3);
        check(Calls.call(ctx, "gui.read", "{}").getAsJsonObject().get("open").getAsBoolean(), "inventory key opens a screen");
        Calls.call(ctx, "gui.action", "{\"action\":\"close\"}");

        var err = Calls.error(ctx, "input", "{\"keys\":[\"teleport\"]}");
        check("INVALID_PARAMS".equals(err.code()) && err.hint().contains("forward"), "unknown key lists valid names");

        JsonObject settings = Calls.call(ctx, "client.settings", "{\"fov\":90,\"hideHud\":true}").getAsJsonObject();
        check(settings.get("fov").getAsInt() == 90, "fov 90: " + settings);
        check(settings.get("hideHud").getAsBoolean(), "hud hidden");
        JsonObject back = Calls.call(ctx, "client.settings", "{\"fov\":70,\"hideHud\":false}").getAsJsonObject();
        check(!back.get("hideHud").getAsBoolean() && back.get("fov").getAsInt() == 70, "restored: " + back);
        check(Calls.call(ctx, "client.settings", "{}").getAsJsonObject().has("windowSize"), "read-only call returns values");
    }
}
```

`AgentGameTests.java`: add `InputSettingsChecks.run(ctx, sp);` after `CaptureChecks.run(ctx, sp);`.

Run: `./gradlew :agent-fabric:runClientGameTest`
Expected: FAIL with `UNKNOWN_METHOD` for `input`.

- [ ] **Step 2: Implement the handlers**

`handlers/InputHandler.java`:
```java
package com.uxplima.craftwire.fabric.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.fabric.ClientScheduler;
import com.uxplima.craftwire.fabric.Params;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

final class InputHandler {
    private InputHandler() {}

    private static final Map<String, Function<Options, KeyMapping>> KEYS = new LinkedHashMap<>();

    static {
        KEYS.put("forward", o -> o.keyUp);
        KEYS.put("back", o -> o.keyDown);
        KEYS.put("left", o -> o.keyLeft);
        KEYS.put("right", o -> o.keyRight);
        KEYS.put("jump", o -> o.keyJump);
        KEYS.put("sneak", o -> o.keyShift);
        KEYS.put("sprint", o -> o.keySprint);
        KEYS.put("attack", o -> o.keyAttack);
        KEYS.put("use", o -> o.keyUse);
        KEYS.put("pick", o -> o.keyPickItem);
        KEYS.put("drop", o -> o.keyDrop);
        KEYS.put("inventory", o -> o.keyInventory);
        KEYS.put("swap_hands", o -> o.keySwapOffhand);
        KEYS.put("chat", o -> o.keyChat);
        KEYS.put("command", o -> o.keyCommand);
        KEYS.put("player_list", o -> o.keyPlayerList);
        KEYS.put("perspective", o -> o.keyTogglePerspective);
        KEYS.put("hide_gui", o -> o.keyToggleGui);
        KEYS.put("screenshot", o -> o.keyScreenshot);
    }

    static JsonElement handle(JsonObject p, ClientScheduler s) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) throw Params.notInWorld();
        String mode = Params.optString(p, "mode").orElse("press");
        int durationMs = Params.optInt(p, "durationMs").orElse(0);

        List<InputConstants.Key> keys = new ArrayList<>();
        JsonArray names = p.has("keys") && p.get("keys").isJsonArray() ? p.getAsJsonArray("keys") : new JsonArray();
        for (JsonElement n : names) {
            Function<Options, KeyMapping> f = KEYS.get(n.getAsString());
            if (f == null) {
                throw new AgentError("INVALID_PARAMS", "Unknown key \"" + n.getAsString() + "\".", "Valid keys: " + String.join(", ", KEYS.keySet()));
            }
            keys.add(KeyMappingHelper.getBoundKeyOf(f.apply(mc.options)));
        }
        for (InputConstants.Key key : keys) {
            switch (mode) {
                case "hold" -> KeyMapping.set(key, true);
                case "release" -> KeyMapping.set(key, false);
                case "press" -> {
                    KeyMapping.click(key);
                    if (durationMs > 0) {
                        KeyMapping.set(key, true);
                        s.delay((int) Math.ceil(durationMs / 50.0)).thenRun(() -> KeyMapping.set(key, false));
                    }
                }
                default -> throw Params.invalid("mode must be press, hold or release");
            }
        }

        if (p.has("look") && p.get("look").isJsonObject()) {
            JsonObject look = p.getAsJsonObject("look");
            if (look.has("yaw")) player.setYRot(player.getYRot() + look.get("yaw").getAsFloat());
            if (look.has("pitch")) player.setXRot(Mth.clamp(player.getXRot() + look.get("pitch").getAsFloat(), -90f, 90f));
        }
        Params.optInt(p, "hotbar").ifPresent(h -> {
            if (h < 1 || h > 9) throw Params.invalid("hotbar must be 1-9");
            player.getInventory().setSelectedSlot(h - 1);
        });

        JsonObject o = new JsonObject();
        o.add("keys", names);
        o.addProperty("mode", mode);
        o.addProperty("yaw", player.getYRot());
        o.addProperty("pitch", player.getXRot());
        o.addProperty("selectedSlot", player.getInventory().getSelectedSlot() + 1);
        return o;
    }
}
```

`handlers/ClientSettingsHandler.java`:
```java
package com.uxplima.craftwire.fabric.handlers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.Window;
import com.uxplima.craftwire.fabric.Params;
import com.uxplima.craftwire.fabric.mixin.HudAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

final class ClientSettingsHandler {
    private ClientSettingsHandler() {}

    static JsonElement handle(JsonObject p) {
        Minecraft mc = Minecraft.getInstance();
        Options o = mc.options;
        Params.optInt(p, "guiScale").ifPresent(v -> o.guiScale().set(v));
        Params.optInt(p, "fov").ifPresent(v -> o.fov().set(v));
        Params.optInt(p, "renderDistance").ifPresent(v -> o.renderDistance().set(v));
        Params.optBool(p, "hideHud").ifPresent(v -> ((HudAccessor) mc.gui.hud).craftwire$setHidden(v));
        if (p.has("windowSize") && p.get("windowSize").isJsonObject()) {
            JsonObject ws = p.getAsJsonObject("windowSize");
            mc.getWindow().setWindowed(ws.get("width").getAsInt(), ws.get("height").getAsInt());
        }
        Window w = mc.getWindow();
        JsonObject out = new JsonObject();
        out.addProperty("guiScale", o.guiScale().get());
        out.addProperty("fov", o.fov().get());
        out.addProperty("renderDistance", o.renderDistance().get());
        out.addProperty("hideHud", ((HudAccessor) mc.gui.hud).craftwire$isHidden());
        JsonObject size = new JsonObject();
        size.addProperty("width", w.getScreenWidth());
        size.addProperty("height", w.getScreenHeight());
        out.add("windowSize", size);
        return out;
    }
}
```

`handlers/Handlers.java`: add to `registerAll`:
```java
        agent.dispatcher().register("input", p -> s.call(() -> InputHandler.handle(p, s)));
        agent.dispatcher().register("client.settings", p -> s.call(() -> ClientSettingsHandler.handle(p)));
```

- [ ] **Step 3: Run the gametest to verify it passes**

Run: `./gradlew :agent-fabric:runClientGameTest`
Expected: BUILD SUCCESSFUL with all five check groups passing.

- [ ] **Step 4: Commit**

```bash
git add agent-fabric
git commit -m "feat(fabric): input simulation and client settings"
```

---

### Task 14: Claude Code plugin, skills and README

**Files:**
- Create: `.claude-plugin/marketplace.json`
- Create: `claude-plugin/.claude-plugin/plugin.json`, `claude-plugin/.mcp.json`
- Create: `claude-plugin/skills/craftwire/SKILL.md`, `claude-plugin/skills/minecraft-promo-shots/SKILL.md`
- Modify: `README.md`
- Test: `hub/test/plugin-manifest.test.ts`

**Interfaces:**
- Consumes: the tool names from Tasks 4–5 and the npm package name and version from `hub/package.json`.
- Produces: an installable marketplace `uxplima` with the plugin `craftwire`, whose MCP server is named `craftwire`.
- Skills `paper-plugin-dev` and `fabric-mod-dev` (spec §8) ship with M2 and M3, where their tools exist.

- [ ] **Step 1: Write the failing manifest test**

`hub/test/plugin-manifest.test.ts`:
```ts
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const root = join(__dirname, "..", "..");
const read = (p: string) => JSON.parse(readFileSync(join(root, p), "utf8"));

describe("Claude Code plugin packaging", () => {
  const hubPkg = read("hub/package.json");

  it("marketplace lists the craftwire plugin", () => {
    const m = read(".claude-plugin/marketplace.json");
    expect(m.name).toBe("uxplima");
    expect(m.plugins).toContainEqual(expect.objectContaining({ name: "craftwire", source: "./claude-plugin" }));
  });

  it("plugin version is lockstep with the hub", () => {
    expect(read("claude-plugin/.claude-plugin/plugin.json").version).toBe(hubPkg.version);
  });

  it(".mcp.json starts the pinned hub via npx", () => {
    const mcp = read("claude-plugin/.mcp.json");
    expect(mcp.mcpServers.craftwire.command).toBe("npx");
    expect(mcp.mcpServers.craftwire.args).toEqual(["-y", `craftwire@${hubPkg.version}`]);
  });

  it("skills have name and description front matter", () => {
    for (const skill of ["craftwire", "minecraft-promo-shots"]) {
      const text = readFileSync(join(root, "claude-plugin/skills", skill, "SKILL.md"), "utf8");
      expect(text).toMatch(new RegExp(`^---\\nname: ${skill}\\ndescription: .+\\n---`, "m"));
    }
  });
});
```

Run: `cd hub && npx vitest run test/plugin-manifest.test.ts`
Expected: FAIL (files missing).

- [ ] **Step 2: Create the manifests**

`.claude-plugin/marketplace.json`:
```json
{
  "name": "uxplima",
  "owner": { "name": "UXPLIMA" },
  "plugins": [
    {
      "name": "craftwire",
      "source": "./claude-plugin",
      "description": "See and drive Minecraft from Claude Code: screenshots, camera, GUIs, chat, input.",
      "version": "0.1.0"
    }
  ]
}
```

`claude-plugin/.claude-plugin/plugin.json`:
```json
{
  "name": "craftwire",
  "version": "0.1.0",
  "description": "Craftwire by UXPLIMA: let Claude see and drive Minecraft (screenshots, camera, GUIs, chat, input) through the Craftwire Agent mod.",
  "author": { "name": "UXPLIMA" },
  "license": "MIT",
  "repository": "https://github.com/uxplima/craftwire",
  "keywords": ["minecraft", "fabric", "paper", "mcp"]
}
```

`claude-plugin/.mcp.json`:
```json
{
  "mcpServers": {
    "craftwire": {
      "command": "npx",
      "args": ["-y", "craftwire@0.1.0"]
    }
  }
}
```

- [ ] **Step 3: Write the skills**

`claude-plugin/skills/craftwire/SKILL.md`:
```markdown
---
name: craftwire
description: Use when driving Minecraft through the craftwire MCP tools (screenshot, camera, gui_read, gui_action, input, chat, hud_read, player_state, wait_for) — covers the reliable order of calls, menu automation and recovering from errors.
---

# Driving Minecraft with Craftwire

## Always start here
1. `list_instances` — if empty, the player must start Minecraft with the Craftwire Agent mod (and join a world/server). Never guess an `instance`; pass it only when several are listed.
2. `player_state` — where the player is, what they hold, what they look at.

## Opening and using a menu (plugin GUIs)
1. `chat {action:"command", text:"/builders crew"}`
2. `wait_for {condition:"screen_open", timeoutMs:5000}` — menus open a tick or more later.
3. `gui_read` — slots are numbered; use the `slot` field, not the visual position.
4. `gui_action {action:"hover", slot:N}` then `screenshot` to capture the tooltip.
5. `gui_action {action:"close"}` when done.
If `gui_action` returns `SLOT_OUT_OF_RANGE` or `NO_SCREEN_OPEN`, the screen changed: `gui_read` again.

## Screenshots
- `hud:false` hides the HUD (and the Craftwire indicator); open menus still render.
- `savePath` writes the full-resolution PNG (relative paths are relative to the current project); the image you see is downscaled to `maxSize`.
- For a fixed angle use `camera {action:"set", …}` once, then take several screenshots; `camera {action:"reset"}` afterwards.
- The camera is render-only and should stay within render distance of the player.

## Errors
Every error has `code`, `message`, `hint` — follow the hint. `PAUSED_BY_USER` means the human pressed F8: stop and ask them. Use `operationId` on actions you may retry (clicks, commands) so a retry never runs twice.
```

`claude-plugin/skills/minecraft-promo-shots/SKILL.md`:
```markdown
---
name: minecraft-promo-shots
description: Use when producing marketing/promo screenshots of a Minecraft plugin or mod with craftwire (forum covers, store pages, showcase images) — shot planning, world setup, camera framing, HUD and menu captures, progress series.
---

# Promo shots with Craftwire

## Prepare the scene (server commands via chat)
- `/time set noon`, `/weather clear`, `/gamerule doDaylightCycle false` (restore after).
- Clear clutter from the hotbar if it will be visible; `client_settings {fov:70, guiScale:3}`.

## Shot types
1. **Hero world shot** — `camera {action:"frame_area", area:{min,max}, pitch:25}` around the subject; `screenshot {hud:false, savePath:"shots/hero.png"}`. Try 2–3 yaw values (e.g. 35, 135, 225) and keep the best.
2. **Progress series** (something being built/changed) — set the camera once with `camera set`, then repeatedly: `wait_for` (chat/hud pattern for the milestone) → `screenshot {hud:false}`. Never move the camera between frames.
3. **Menu with tooltip** — open the menu, `gui_action hover` on the most impressive item, `screenshot {hud:true}` (menus need the HUD layer). Crop later; the GUI is centred.
4. **HUD feature** (bossbar/actionbar) — `screenshot {hud:true}` while it is shown; confirm with `hud_read` first.

## Quality checklist
- Look at every returned image before moving on; retake if a chat message, crosshair or half-loaded chunk is visible.
- Save full-resolution files with descriptive names (`shots/b-02a-25pct.png`).
- `camera reset` and restore any gamerules you changed.
```

- [ ] **Step 4: Update the README quick start**

Replace `README.md` with:
```markdown
# Craftwire

Let AI agents (Claude Code and any MCP client) **see and drive Minecraft**: screenshots, a free camera, reading and clicking GUIs, chat and commands, HUD reading and input. Server control, scripting, a plugin dev loop and bots follow in the next milestones.

By [UXPLIMA](https://github.com/uxplima) · MIT licensed · Minecraft 26.2 (Fabric)

## Quick start (Claude Code)

1. Install the plugin:
   ```
   /plugin marketplace add uxplima/craftwire
   /plugin install craftwire@uxplima
   ```
2. Install **Craftwire Agent** (Fabric mod, requires Fabric API) into your Minecraft 26.2 profile.
3. Start Minecraft and join a world. A green **⚡ Craftwire connected** appears top-left. **F8** pauses AI control at any time.

Ask Claude: *"take a screenshot of what I'm looking at"*.

> Windows: if the MCP server does not start, edit `.mcp.json` in the plugin to use `"command": "cmd", "args": ["/c", "npx", "-y", "craftwire@0.1.0"]`.

## How it works

`craftwire` (npm) is an MCP server over stdio. It listens on `127.0.0.1` only and writes its port and a random token to `~/.craftwire/hub.json`. The mod reads that file and connects out to the hub — the game opens no ports. Every tool call is logged to `~/.craftwire/logs/`.

## Tools (M1)

`list_instances` · `wait_for` · `get_request_status` · `screenshot` · `camera` · `gui_read` · `gui_action` · `input` · `chat` · `hud_read` · `player_state` · `client_settings`

## Development

- Hub: `cd hub && npm install && npm test`
- Agents: `./gradlew :agent-core:test :agent-fabric:test :agent-fabric:runClientGameTest`
- Dev client with the mod: `./gradlew :agent-fabric:runClient`
```

- [ ] **Step 5: Run the tests and validate the plugin**

Run: `cd hub && npx vitest run test/plugin-manifest.test.ts`
Expected: PASS (4 tests).

Then dispatch the `plugin-dev:plugin-validator` agent on `claude-plugin/` and `.claude-plugin/marketplace.json`, and fix anything it reports.

- [ ] **Step 6: Commit**

```bash
git add .claude-plugin claude-plugin README.md hub/test/plugin-manifest.test.ts
git commit -m "feat: Claude Code plugin, marketplace and craftwire/promo-shots skills"
```

---

### Task 15: CI and the M1 acceptance run

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `docs/acceptance/m1-builders-shots.md` (the acceptance script and its result)

**Interfaces:**
- Consumes: all earlier tasks.
- Produces: green CI on push and a recorded acceptance run.

- [ ] **Step 1: Write the workflow**

`.github/workflows/ci.yml`:
```yaml
name: CI
on:
  push:
  pull_request:

jobs:
  hub:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: hub
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: 22
          cache: npm
          cache-dependency-path: hub/package-lock.json
      - run: npm ci
      - run: npm run typecheck
      - run: npm test

  java:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 25
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew :agent-core:test :agent-fabric:test :agent-fabric:build

  client-gametest:
    runs-on: ubuntu-latest
    needs: java
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 25
      - uses: gradle/actions/setup-gradle@v4
      - run: sudo apt-get update && sudo apt-get install -y xvfb
      - run: xvfb-run -a -s "-screen 0 1920x1080x24" ./gradlew :agent-fabric:runClientGameTest
      - if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: gametest-logs
          path: agent-fabric/build/run/**/logs/
```

- [ ] **Step 2: Push and confirm CI**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: hub, java and client gametest jobs"
```
Push to the GitHub repository (create `uxplima/craftwire` first if needed; ask the user before creating a public repository). Expected: all three jobs green. If `client-gametest` is flaky under xvfb (spec §10), mark it `continue-on-error: true`, open an issue, and keep the local `runClientGameTest` as the gate.

- [ ] **Step 3: Manual end-to-end with Claude Code (local hub, dev client)**

```bash
cd hub && npm run build && cd ..
claude mcp add craftwire-dev -- node "$(pwd)/hub/dist/cli.js"
./gradlew :agent-fabric:runClient
```
In a fresh Claude Code session (so the new MCP server loads):
1. `list_instances` shows `client-1`.
2. `screenshot` returns an image of the game.
3. Run `/give @s diamond`, open the inventory (`input {keys:["inventory"]}`), then `gui_read`, `gui_action hover`, and `screenshot`. The tooltip is visible.
4. `camera frame_area` around the spawn area, `screenshot {hud:false, savePath:"shots/test.png"}`, then `camera reset`.
5. Press F8 in game: the next call returns `PAUSED_BY_USER`. Press F8 again to resume.
6. Alt-tab to the terminal: the game does not open the pause menu.

Then remove the dev server with `claude mcp remove craftwire-dev`.

- [ ] **Step 4: Acceptance — uxmBuilders promo shots (spec §1)**

Install the built mod jar (`agent-fabric/build/libs/craftwire-agent-fabric-0.1.0.jar`) into the user's Modrinth "Fabric 26.2" profile and start the user's Paper 26.2 test server with uxmBuilders. In Claude Code, with the `minecraft-promo-shots` skill, have Claude produce:
- `b-01` hero NPC build shot with the bossbar
- `b-02a/b/c` progress series from one fixed camera
- `b-03` ghost preview
- `b-04` crew menu with a tooltip
- `b-05` shop menu

The human may only start the game and the server. Record what worked, what needed help and any defects in `docs/acceptance/m1-builders-shots.md`. Defects become issues or follow-up tasks; anything that can only be done with M2 server tools (for example creating packages via the console) is listed as M2 input.

- [ ] **Step 5: Commit**

```bash
git add docs/acceptance/m1-builders-shots.md
git commit -m "docs: M1 acceptance run (uxmBuilders promo shots)"
```
