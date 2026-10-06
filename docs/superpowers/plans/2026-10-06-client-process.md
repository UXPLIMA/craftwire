# client_process Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The hub downloads, starts and stops a hidden Minecraft 26.2 Fabric client with the Craftwire agent, joined to a dev server, so the AI can use every client tool without the user opening the game.

**Architecture:** An in-hub launcher (`hub/src/client/`) reads Mojang's version JSON and Fabric's loader profile, downloads sha1-verified files into `~/.craftwire/client/`, writes a per-username game dir and spawns `java`. A `ClientManager` (modelled on `dev/server-manager.ts`) owns the processes and maps them to agents through a new `gameDir` hello field. The agent mod hides its window when `-Dcraftwire.hidden=true` and answers `client.quit`.

**Tech Stack:** TypeScript (Node ≥ 20, vitest, zod, ws), Java 25 Fabric mod (Mixin), Gradle.

**Spec:** `docs/superpowers/specs/2026-10-06-client-process-design.md`

## Global Constraints

- No third-party runtime projects: launcher code is ours; downloads only from `piston-meta.mojang.com`, `piston-data.mojang.com`, `libraries.minecraft.net`, `resources.download.minecraft.net`, `meta.fabricmc.net`, `maven.fabricmc.net`.
- Every download verified against its published sha1; `.part` files never become cache entries.
- Never read or write the user's `.minecraft` or launcher profiles; never change `server.properties`.
- Offline mode only; username `^[A-Za-z0-9_]{3,16}$`, default `Craftwire`.
- Versions from `gradle.properties`: Minecraft `26.2`, Loader `0.19.5`, Fabric API `0.161.0+26.2` (sha1 `332da34ebb72171e603a0c538de17f4c54ea9e29`).
- Cache root `~/.craftwire/client/` (`versions/`, `libraries/`, `assets/`, `instances/<username>/`); `~` is `craftwireHome()`'s parent folder logic: use `join(craftwireHome(), "client")`.
- Sound assets (`.ogg`) skipped unless `sounds:true`.
- Repo text English; replies to the user Turkish.
- Commands: hub tests `cd hub && npx vitest run <file>`; Java `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew.bat …`. Bash heredocs and Python string literals eat backslashes: write code with the Write/Edit tools.

## Review Focus

1. A cached file that was corrupted or truncated on disk → re-downloaded, never trusted (Task 4 test "re-fetches a cached file whose sha1 is wrong").
2. Two `start` calls for the same username at once → the second gets `ALREADY_RUNNING`, not two JVMs on one game dir (Task 9 test "a second start for a running username fails").
3. User edits `options.txt` (e.g. guiScale) between runs → kept, only our keys added if missing (Task 8 test "keeps the user's keys").
4. Extra `mods` path that does not exist → `INVALID_PARAMS` before anything is downloaded (Task 8 test "rejects a missing extra mod").
5. Hub exits while a client runs → the client process is stopped (Task 9 test "shutdown stops running clients").

---

### Task 1: `gameDir` in the agent hello (protocol, both sides)

**Files:**
- Modify: `agent-core/src/main/java/com/uxplima/craftwire/core/Hello.java`
- Modify: `agent-core/src/main/java/com/uxplima/craftwire/core/RpcCodec.java:13-30`
- Test: `agent-core/src/test/java/com/uxplima/craftwire/core/RpcCodecTest.java`
- Modify: `hub/src/protocol.ts:5-15`, `hub/src/agents.ts:12-22,150-160`
- Modify: `hub/test/helpers/fakeAgent.ts`
- Test: `hub/test/agents.test.ts`

**Interfaces:**
- Produces: Java `new Hello(kind, version, mc, name, serverDir, pid, gameDir)`; TS `InstanceInfo.gameDir?: string`; `connectFakeAgent(port, {…, gameDir?})`.

- [ ] **Step 1: Failing Java test** in `RpcCodecTest`:

```java
@Test
void helloCarriesTheClientGameDir() {
    String json = RpcCodec.hello("t", new Hello("client", "0.5.0", "26.2", "Craftwire", null, null, "/tmp/cw/instances/Craftwire"));
    JsonObject params = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("params");
    assertEquals("/tmp/cw/instances/Craftwire", params.get("gameDir").getAsString());
    assertFalse(params.has("serverDir"));
}
```

- [ ] **Step 2:** `./gradlew.bat :agent-core:test --tests '*RpcCodecTest*'` → compile failure (no 7-arg constructor).
- [ ] **Step 3: Implement.** `Hello`:

```java
/** First message an agent sends. {@code serverDir} and {@code pid} are set by server agents, {@code gameDir} by client agents. */
public record Hello(String agentKind, String agentVersion, String mcVersion, String instanceName, String serverDir, Long pid, String gameDir) {
    public Hello(String agentKind, String agentVersion, String mcVersion, String instanceName) {
        this(agentKind, agentVersion, mcVersion, instanceName, null, null, null);
    }

    public Hello(String agentKind, String agentVersion, String mcVersion, String instanceName, String serverDir, Long pid) {
        this(agentKind, agentVersion, mcVersion, instanceName, serverDir, pid, null);
    }
}
```

`RpcCodec.hello`: after the `pid` line add `if (h.gameDir() != null) params.addProperty("gameDir", h.gameDir());`.
- [ ] **Step 4:** agent-core tests pass.
- [ ] **Step 5: Failing hub test** in `agents.test.ts`:

```ts
it("keeps a client's gameDir", async () => {
  const a = await connectFakeAgent(port, { token, kind: "client", gameDir: "/cw/instances/Bob" });
  expect(agents.instances().find((i) => i.id === a.instanceId)?.gameDir).toBe("/cw/instances/Bob");
  await a.close();
});
```

(Use the file's existing `port`/`token`/`agents` setup.)
- [ ] **Step 6:** `npx vitest run test/agents.test.ts` → fails (helper has no `gameDir`, or field missing).
- [ ] **Step 7: Implement.** `protocol.ts` HelloParams: `gameDir: z.string().optional(),`. `agents.ts` InstanceInfo: `/** Client agents: the game directory (agents >= 0.5.0). */ gameDir?: string;` and in the info object `...(params.gameDir !== undefined ? { gameDir: params.gameDir } : {}),`. `fakeAgent.ts`: add `gameDir?: string` to opts and `...(opts.gameDir !== undefined ? { gameDir: opts.gameDir } : {}),` in params.
- [ ] **Step 8:** hub suite green: `npx vitest run`.
- [ ] **Step 9: Commit** `feat(protocol): client agents report their game directory`.

### Task 2: Agent mod: hidden window, `client.quit`, gameDir

**Files:**
- Create: `agent-fabric/src/main/java/com/uxplima/craftwire/fabric/mixin/WindowMixin.java`
- Modify: `agent-fabric/src/main/resources/craftwire-agent.mixins.json` (add `"WindowMixin"` to `client`)
- Modify: `agent-fabric/src/main/java/com/uxplima/craftwire/fabric/CraftwireAgent.java` (hello, refusal log text)
- Modify: `agent-fabric/src/main/java/com/uxplima/craftwire/fabric/handlers/Handlers.java`
- Test: `agent-fabric/src/gametest/java/com/uxplima/craftwire/fabric/gametest/LifecycleChecks.java`

**Interfaces:**
- Consumes: Task 1 `Hello` 7-arg constructor.
- Produces: system property `craftwire.hidden`; agent method `client.quit` → `{quitting:true}`.

- [ ] **Step 1: Failing gametest** in `LifecycleChecks` (called from the existing suite): the window's GLFW visibility matches the property.

```java
static void windowVisibilityFollowsTheHiddenFlag(ClientGameTestContext ctx) {
    boolean hidden = Boolean.getBoolean("craftwire.hidden");
    int visible = ctx.computeOnClient(mc -> org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(mc.getWindow().handle(), org.lwjgl.glfw.GLFW.GLFW_VISIBLE));
    check((visible == 0) == hidden, "window visible=" + visible + " but craftwire.hidden=" + hidden);
}
```

and in `agent-fabric/build.gradle` gametest run config add `vmArg "-Dcraftwire.hidden=true"` so CI exercises the hidden path (screenshot checks then prove hidden windows render). Check the accessor name for the GLFW handle with `javap -p -cp <merged jar> com.mojang.blaze3d.platform.Window | grep long` and use it.
- [ ] **Step 2:** `./gradlew.bat :agent-fabric:runClientGameTest` → fails: visible=1 while hidden=true.
- [ ] **Step 3: Implement** `WindowMixin` (the static `createGlfwWindow(int,int,String,long,GpuBackend)` sets hints then calls `glfwCreateWindow`):

```java
package com.uxplima.craftwire.fabric.mixin;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** client_process starts clients with -Dcraftwire.hidden=true: the window is created invisible and still renders. */
@Mixin(Window.class)
public abstract class WindowMixin {
    @Inject(method = "createGlfwWindow", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"))
    private static void craftwire$hide(CallbackInfoReturnable<Long> cir) {
        if (Boolean.getBoolean("craftwire.hidden")) GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
    }
}
```

`Handlers.registerAll`: `agent.dispatcher().register("client.quit", p -> s.call(() -> { Minecraft.getInstance().stop(); JsonObject o = new JsonObject(); o.addProperty("quitting", true); return o; }));` (match the file's existing `s.call` lambda types; import `net.minecraft.client.Minecraft`, `com.google.gson.JsonObject`).

`CraftwireAgent.hello()`: `return new Hello("client", VERSION, SharedConstants.getCurrentVersion().name(), mc.getUser().getName(), null, null, FabricLoader.getInstance().getGameDir().toAbsolutePath().toString());`. Refusal log: replace "Restart Claude Code" with "Restart your AI client".
- [ ] **Step 4:** gametest passes (all existing checks too, now hidden). Then `./gradlew.bat :agent-fabric:build`.
- [ ] **Step 5: Commit** `feat(agent-fabric): hidden window flag, client.quit, gameDir in hello`.

### Task 3: Pins and the packaged agent jar

**Files:**
- Create: `hub/src/client/pins.ts`, `hub/test/client-pins.test.ts`, `hub/scripts/copy-agent.mjs`
- Modify: `hub/package.json` (prepack, files), `.gitignore` (`hub/agent/`)

**Interfaces:**
- Produces: `PINS: { minecraft: string; loader: string; fabricApi: string; fabricApiSha1: string }`; `agentJar(): string` (absolute path of `craftwire-agent-fabric-<HUB_VERSION>.jar`; packaged `../agent/` first, repo `../../agent-fabric/build/libs/` second; throws `CraftwireError("AGENT_JAR_MISSING", …)`).

- [ ] **Step 1: Failing test:**

```ts
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { PINS } from "../src/client/pins.js";

const props = Object.fromEntries(readFileSync(join(__dirname, "..", "..", "gradle.properties"), "utf8")
  .split(/\r?\n/).map((l) => /^([\w.]+)=(.*)$/.exec(l.trim())).filter((m) => m).map((m) => [m![1], m![2]]));

describe("client pins", () => {
  it("match the versions the agent is built for", () => {
    expect(PINS.minecraft).toBe(props.minecraft_version);
    expect(PINS.loader).toBe(props.loader_version);
    expect(PINS.fabricApi).toBe(props.fabric_api_version);
    expect(PINS.fabricApiSha1).toMatch(/^[0-9a-f]{40}$/);
  });
});
```

- [ ] **Step 2:** fails (module missing).
- [ ] **Step 3: Implement** `pins.ts`:

```ts
import { existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { CraftwireError } from "../errors.js";
import { HUB_VERSION } from "../version.js";

/** The client client_process runs: the versions the agent mod is built for (gradle.properties; a test keeps them equal). */
export const PINS = {
  minecraft: "26.2",
  loader: "0.19.5",
  fabricApi: "0.161.0+26.2",
  fabricApiSha1: "332da34ebb72171e603a0c538de17f4c54ea9e29",
} as const;

/** The agent mod matching this hub: packed into the npm package by prepack, or built in a repo checkout. */
export function agentJar(): string {
  const here = dirname(fileURLToPath(import.meta.url));
  const name = `craftwire-agent-fabric-${HUB_VERSION}.jar`;
  for (const dir of [join(here, "..", "..", "agent"), join(here, "..", "..", "..", "agent-fabric", "build", "libs")]) {
    if (existsSync(join(dir, name))) return join(dir, name);
  }
  throw new CraftwireError("AGENT_JAR_MISSING", `${name} is not next to the hub`, "Reinstall craftwire, or in a checkout run ./gradlew :agent-fabric:build.");
}
```

`scripts/copy-agent.mjs` copies `../agent-fabric/build/libs/craftwire-agent-fabric-<version from package.json>.jar` into `agent/`, exiting 1 with a message when it is missing. `package.json`: `"prepack": "npm run build && node scripts/copy-skills.mjs && node scripts/copy-agent.mjs"`, `"files": ["dist", "skills", "agent"]`.
- [ ] **Step 4:** test passes; `npm pack --dry-run` lists `agent/craftwire-agent-fabric-<v>.jar`.
- [ ] **Step 5: Commit** `feat(hub): client pins and the agent jar in the npm package`.

### Task 4: Verified downloads

**Files:**
- Create: `hub/src/client/download.ts`, `hub/test/client-download.test.ts`

**Interfaces:**
- Produces: `interface FileSpec { url: string; sha1: string; size?: number; dest: string }`; `fetchVerified(f: FileSpec, opts?: { fetchImpl?: typeof fetch; retries?: number }): Promise<number>` (bytes fetched, 0 when cached); `fetchAll(files: FileSpec[], opts?: { concurrency?: number; onProgress?: (done: number, total: number) => void; fetchImpl?: typeof fetch }): Promise<number>`; `sha1File(path): string`.

- [ ] **Step 1: Failing tests** with a local `node:http` server serving `/good` (bytes "hello"), `/bad` (bytes "nope"), `/flaky` (500 once, then "hello"):

```ts
const HELLO_SHA1 = "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d";
it("downloads and verifies", async () => {
  expect(await fetchVerified({ url: `${base}/good`, sha1: HELLO_SHA1, dest: join(dir, "a/x.bin") })).toBe(5);
  expect(readFileSync(join(dir, "a/x.bin"), "utf8")).toBe("hello");
});
it("discards a file whose sha1 differs and leaves no .part", async () => {
  await expect(fetchVerified({ url: `${base}/bad`, sha1: HELLO_SHA1, dest: join(dir, "b.bin") }, { retries: 0 })).rejects.toMatchObject({ code: "CHECKSUM_MISMATCH" });
  expect(existsSync(join(dir, "b.bin"))).toBe(false);
  expect(existsSync(join(dir, "b.bin.part"))).toBe(false);
});
it("retries after a server error", async () => {
  expect(await fetchVerified({ url: `${base}/flaky`, sha1: HELLO_SHA1, dest: join(dir, "c.bin") }, { retries: 2 })).toBe(5);
});
it("does not refetch a cached file with the right sha1", async () => {
  writeFileSync(join(dir, "d.bin"), "hello");
  expect(await fetchVerified({ url: `${base}/missing`, sha1: HELLO_SHA1, dest: join(dir, "d.bin") })).toBe(0);
});
it("re-fetches a cached file whose sha1 is wrong", async () => {
  writeFileSync(join(dir, "e.bin"), "hel");
  expect(await fetchVerified({ url: `${base}/good`, sha1: HELLO_SHA1, dest: join(dir, "e.bin") })).toBe(5);
});
it("reports DOWNLOAD_FAILED with the url after the retries", async () => {
  await expect(fetchVerified({ url: `${base}/missing`, sha1: HELLO_SHA1, dest: join(dir, "f.bin") }, { retries: 1 }))
    .rejects.toMatchObject({ code: "DOWNLOAD_FAILED", message: expect.stringContaining("/missing") });
});
it("fetchAll reports progress", async () => {
  const seen: number[] = [];
  await fetchAll([1, 2, 3].map((i) => ({ url: `${base}/good`, sha1: HELLO_SHA1, dest: join(dir, `g${i}.bin`) })), { onProgress: (d) => seen.push(d) });
  expect(seen.at(-1)).toBe(3);
});
```

- [ ] **Step 2:** fails (module missing).
- [ ] **Step 3: Implement** `download.ts`: `sha1File` via `createHash("sha1")` over `readFileSync`; `fetchVerified`: if `dest` exists and `sha1File(dest) === sha1` return 0; loop attempts `0..retries` (default 3) with `sleep(500 * 2 ** attempt)` between: `res = await (fetchImpl ?? fetch)(url, { headers: { "User-Agent": "craftwire (https://github.com/uxplima/craftwire)" } })`; non-2xx → remember `HTTP <status>` and retry; body → `Buffer`; if `size` set and length differs, or sha1 differs → `CraftwireError("CHECKSUM_MISMATCH", "<url>: expected sha1 …, got …", "The download was corrupted or the source changed; retry.")` (not retried when sha1 differs and `retries` exhausted); write `${dest}.part` then `renameSync`. After the loop: `CraftwireError("DOWNLOAD_FAILED", "<url>: <last error>", "Check the network connection, then retry client_process start.")`. `fetchAll`: a simple worker pool (`concurrency` default 8) over the list, summing bytes, calling `onProgress(done, files.length)` after each file.
- [ ] **Step 4:** tests pass.
- [ ] **Step 5: Commit** `feat(hub): sha1-verified downloads for the client launcher`.

### Task 5: Mojang version JSON → libraries, assets, arguments

**Files:**
- Create: `hub/src/client/mojang.ts`, `hub/test/client-mojang.test.ts`, `hub/test/fixtures/mojang-26.2-excerpt.json`

**Interfaces:**
- Produces:
  - `type Os = { name: "windows" | "osx" | "linux"; arch: string }`; `currentOs(platform = process.platform, arch = process.arch): Os` (`ia32` → `x86`).
  - `type Rule = { action: "allow" | "disallow"; os?: { name?: string; arch?: string }; features?: Record<string, boolean> }`; `allowed(rules: Rule[] | undefined, os: Os, features: Record<string, boolean>): boolean` (no rules → true; else last matching rule wins, starting from false).
  - `interface VersionJson` (fields used: `id`, `type`, `mainClass`, `javaVersion.majorVersion`, `assetIndex {id,sha1,url}`, `downloads.client {sha1,size,url}`, `libraries[]`, `arguments {game[], jvm[]}`, `logging.client {argument, file {id,sha1,url}}`).
  - `libraryFiles(v: VersionJson, os: Os, libDir: string): FileSpec[]` (allowed libraries' `downloads.artifact` → `{url, sha1, size, dest: join(libDir, path)}`).
  - `argumentList(entries: (string | { rules: Rule[]; value: string | string[] })[], os: Os, features: Record<string, boolean>): string[]`.
  - `assetFiles(index: { objects: Record<string, { hash: string; size: number }> }, objectsDir: string, sounds: boolean): FileSpec[]` (url `https://resources.download.minecraft.net/<hash[0..2]>/<hash>`, dest `objectsDir/<hash[0..2]>/<hash>`; `.ogg` keys skipped unless `sounds`; duplicates by hash collapsed).
  - `fetchVersion(minecraft: string, cacheDir: string, fetchImpl?): Promise<VersionJson>` (manifest `https://piston-meta.mojang.com/mc/game/version_manifest_v2.json` → entry `id === minecraft` → `fetchVerified` to `versions/<id>/<id>.json` → parse; `UNKNOWN_VERSION` if absent).

- [ ] **Step 1: Fixture.** Save a real excerpt of the 26.2 JSON (download `https://piston-meta.mojang.com/v1/packages/c7868781b30aaf24be0dac894c94a34e5d6df10d/26.2.json`; keep `id,type,mainClass,javaVersion,assetIndex,downloads,logging,arguments` whole and these libraries: one without rules, `ca.weblite:java-objc-bridge:1.1` (osx), `com.mojang:jtracy:1.0.37:natives-linux`, `org.lwjgl:lwjgl-glfw:3.4.1:natives-windows`, `org.lwjgl:lwjgl-glfw:3.4.1:natives-windows-arm64`).
- [ ] **Step 2: Failing tests:**

```ts
const v = JSON.parse(readFileSync(join(__dirname, "fixtures", "mojang-26.2-excerpt.json"), "utf8")) as VersionJson;
const win = { name: "windows", arch: "x64" } as const;
it("keeps only the libraries allowed on this OS", () => {
  const names = libraryFiles(v, win, "/lib").map((f) => f.dest.replace(/\\/g, "/"));
  expect(names.some((n) => n.includes("natives-windows.jar"))).toBe(true);
  expect(names.some((n) => n.includes("natives-windows-arm64"))).toBe(true); // LWJGL picks by arch at runtime
  expect(names.some((n) => n.includes("java-objc-bridge"))).toBe(false);
  expect(names.some((n) => n.includes("natives-linux"))).toBe(false);
});
it("evaluates feature rules in arguments", () => {
  const args = argumentList(v.arguments.game, win, { has_custom_resolution: true, is_quick_play_multiplayer: true });
  expect(args).toContain("--quickPlayMultiplayer");
  expect(args).toContain("--width");
  expect(args).not.toContain("--demo");
  expect(args).not.toContain("--quickPlayPath");
});
it("adds the macOS-only JVM flag only on macOS", () => {
  expect(argumentList(v.arguments.jvm, win, {})).not.toContain("-XstartOnFirstThread");
  expect(argumentList(v.arguments.jvm, { name: "osx", arch: "arm64" }, {})).toContain("-XstartOnFirstThread");
});
it("maps ia32 to x86", () => expect(currentOs("win32", "ia32")).toEqual({ name: "windows", arch: "x86" }));
it("skips sounds unless asked", () => {
  const index = { objects: { "minecraft/sounds/a.ogg": { hash: "ab12", size: 3 }, "minecraft/lang/x.json": { hash: "cd34", size: 4 } } };
  expect(assetFiles(index, "/o", false).map((f) => f.url)).toEqual(["https://resources.download.minecraft.net/cd/cd34"]);
  expect(assetFiles(index, "/o", true)).toHaveLength(2);
});
```

- [ ] **Step 3:** fails (module missing).
- [ ] **Step 4: Implement** per the Interfaces block. `allowed`: `let ok = false; for (const r of rules) if (matches(r)) ok = r.action === "allow"; return ok;` where `matches` checks `os.name === r.os.name` when given, `new RegExp(r.os.arch).test(os.arch)` when given, and every `features[k] === v`. `argumentList`: strings pass through; objects add `value` (string or array) when `allowed(rules)`.
- [ ] **Step 5:** tests pass.
- [ ] **Step 6: Commit** `feat(hub): read Mojang version JSON (rules, libraries, assets, arguments)`.

### Task 6: Fabric loader profile

**Files:**
- Create: `hub/src/client/fabric.ts`, `hub/test/client-fabric.test.ts`

**Interfaces:**
- Consumes: `VersionJson`, `FileSpec`.
- Produces:
  - `mavenPath(coord: string): string` (`"net.fabricmc:fabric-loader:0.19.5"` → `net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar`; with classifier `g:a:v:c` → `…/a-v-c.jar`).
  - `interface FabricProfile { mainClass: string; arguments: { jvm?: string[]; game?: string[] }; libraries: { name: string; url: string; sha1?: string; size?: number }[] }`.
  - `fabricLibraryFiles(p: FabricProfile, libDir: string): FileSpec[]` (url `<lib.url><mavenPath>`; `sha1` required, else `CraftwireError("DOWNLOAD_FAILED", "no sha1 for <name>")`).
  - `mergeLibraries(mojang: FileSpec[], fabric: FileSpec[], mojangNames: string[], fabricNames: string[]): FileSpec[]` (drops a Mojang library when Fabric has the same `group:artifact[:classifier]`).
  - `fetchFabricProfile(minecraft, loader, fetchImpl?): Promise<FabricProfile>` (`https://meta.fabricmc.net/v2/versions/loader/<mc>/<loader>/profile/json`; non-2xx → `DOWNLOAD_FAILED`).
  - `fabricApiFile(modsDir: string): FileSpec` (`https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/<encodeURIComponent(v)>/fabric-api-<encodeURIComponent(v)>.jar`, `PINS.fabricApiSha1`, dest `modsDir/fabric-api-<v>.jar`).
- [ ] **Step 1: Failing tests:**

```ts
it("maps Maven coordinates to paths", () => {
  expect(mavenPath("net.fabricmc:fabric-loader:0.19.5")).toBe("net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar");
  expect(mavenPath("org.lwjgl:lwjgl:3.4.1:natives-windows")).toBe("org/lwjgl/lwjgl/3.4.1/lwjgl-3.4.1-natives-windows.jar");
});
it("lets Fabric's copy of a library win", () => {
  const m = [{ url: "m/asm", sha1: "1", dest: "/l/asm-9.6.jar" }, { url: "m/gson", sha1: "2", dest: "/l/gson.jar" }];
  const f = [{ url: "f/asm", sha1: "3", dest: "/l/asm-9.10.jar" }];
  const out = mergeLibraries(m, f, ["org.ow2.asm:asm:9.6", "com.google.code.gson:gson:2.11"], ["org.ow2.asm:asm:9.10.1"]);
  expect(out.map((x) => x.url)).toEqual(["m/gson", "f/asm"]);
});
it("needs a sha1 for every Fabric library", () => {
  expect(() => fabricLibraryFiles({ mainClass: "x", arguments: {}, libraries: [{ name: "a:b:1", url: "https://maven.fabricmc.net/" }] }, "/l"))
    .toThrow(/no sha1/);
});
it("points Fabric API at the pinned version on Fabric's Maven", () => {
  expect(fabricApiFile("/m").url).toBe("https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.161.0%2B26.2/fabric-api-0.161.0%2B26.2.jar");
});
```

- [ ] **Step 2:** fails. **Step 3:** implement. **Step 4:** passes.
- [ ] **Step 5: Commit** `feat(hub): Fabric loader profile and Fabric API for the client launcher`.

### Task 7: Launch arguments

**Files:**
- Create: `hub/src/client/launch-args.ts`, `hub/test/client-launch-args.test.ts`

**Interfaces:**
- Consumes: `VersionJson`, `argumentList`, `currentOs`, `FabricProfile`.
- Produces:
  - `offlineUuid(name: string): string` (32 hex chars, UUID v3 of `OfflinePlayer:<name>`).
  - `interface LaunchInput { version: VersionJson; fabric: FabricProfile; os: Os; java: string; classpath: string[]; gameDir: string; assetsDir: string; nativesDir: string; logConfig?: string; username: string; server?: string; width: number; height: number; hidden: boolean; launcherVersion: string; pathSep: string }`.
  - `buildLaunch(i: LaunchInput): { command: string; args: string[] }`.
- [ ] **Step 1: Failing tests** (minimal `VersionJson`/`FabricProfile` literals built in the test, using the real 26.2 game/jvm argument arrays from the Task 5 fixture):

```ts
it("computes the vanilla offline UUID", () => expect(offlineUuid("Notch")).toBe("b50ad385829d3141a2167e7d7539ba7f"));
it("fills every placeholder", () => {
  const { args } = buildLaunch(input({ server: "127.0.0.1:25599" }));
  expect(args.join(" ")).not.toMatch(/\$\{/);
  expect(args).toEqual(expect.arrayContaining(["--username", "Craftwire", "--quickPlayMultiplayer", "127.0.0.1:25599", "--width", "1280", "--accessToken", "0"]));
  expect(args).toContain("-Dcraftwire.hidden=true");
  expect(args.indexOf("net.fabricmc.loader.impl.launch.knot.KnotClient")).toBeGreaterThan(args.indexOf("-cp"));
});
it("joins the classpath with the OS separator", () => {
  const { args } = buildLaunch(input({ pathSep: ";" }));
  expect(args[args.indexOf("-cp") + 1]).toBe("/l/a.jar;/v/26.2.jar");
});
it("leaves out quick play without a server, and the hidden flag when visible", () => {
  const { args } = buildLaunch(input({ server: undefined, hidden: false }));
  expect(args).not.toContain("--quickPlayMultiplayer");
  expect(args).not.toContain("-Dcraftwire.hidden=true");
});
```

(`input(over)` builds a `LaunchInput` with `classpath: ["/l/a.jar", "/v/26.2.jar"]`, `pathSep: ":"`, `username: "Craftwire"`, `width: 1280`, `height: 720`, `hidden: true`, plus `over`.)
- [ ] **Step 2:** fails. **Step 3: Implement:** JVM args = `argumentList(version.arguments.jvm)` + `fabric.arguments.jvm` + logging argument (`${path}` → `logConfig`) when present + `-Xmx2G` + `-Dcraftwire.hidden=true` when hidden; then `fabric.mainClass`; then game args = `argumentList(version.arguments.game, os, { has_custom_resolution: true, is_quick_play_multiplayer: server !== undefined })` + `fabric.arguments.game`. Substitute with a map (`auth_player_name`, `version_name`=version.id, `game_directory`, `assets_root`, `assets_index_name`=assetIndex.id, `auth_uuid`=offlineUuid, `auth_access_token`="0", `clientid`="", `auth_xuid`="", `version_type`=version.type, `natives_directory`, `launcher_name`="craftwire", `launcher_version`, `classpath`=classpath.join(pathSep), `classpath_separator`=pathSep, `library_directory`, `resolution_width`, `resolution_height`, `quickPlayMultiplayer`=server); unknown `${x}` → empty string. Drop an argument pair whose value became empty (`--clientId ""`, `--xuid ""`).
- [ ] **Step 4:** passes. **Step 5: Commit** `feat(hub): build the client launch command`.

### Task 8: Instance folder

**Files:**
- Create: `hub/src/client/instance.ts`, `hub/test/client-instance.test.ts`

**Interfaces:**
- Produces: `prepareInstance(root: string, o: { username: string; agentJar: string; fabricApiJar: string; extraMods: string[]; width: number; height: number }): string` (returns the game dir; recreates `mods/` with exactly the agent, Fabric API and extra mods; writes/merges `options.txt`); `DEFAULT_OPTIONS: Record<string, string>`.
- [ ] **Step 1: Failing tests:**

```ts
it("creates the game dir with exactly the expected mods", () => {
  const dir = prepareInstance(root, { username: "Bob", agentJar: a, fabricApiJar: f, extraMods: [extra], width: 1280, height: 720 });
  expect(dir).toBe(join(root, "instances", "Bob"));
  expect(readdirSync(join(dir, "mods")).sort()).toEqual([basename(a), basename(extra), basename(f)].sort());
});
it("removes mods left from a previous start", () => {
  prepareInstance(root, { …, extraMods: [extra] });
  prepareInstance(root, { …, extraMods: [] });
  expect(readdirSync(join(root, "instances", "Bob", "mods"))).not.toContain(basename(extra));
});
it("writes test-friendly options and keeps the user's keys", () => {
  const dir = prepareInstance(root, base);
  writeFileSync(join(dir, "options.txt"), "guiScale:4\ntutorialStep:movement\n");
  prepareInstance(root, base);
  const text = readFileSync(join(dir, "options.txt"), "utf8");
  expect(text).toContain("guiScale:4");
  expect(text).toContain("tutorialStep:movement"); // the user's value wins
  expect(text).toContain("pauseOnLostFocus:false");
  expect(text).toContain("inactivityFpsLimit:minimized");
});
it("rejects a missing extra mod", () => {
  expect(() => prepareInstance(root, { …, extraMods: [join(root, "nope.jar")] })).toThrow(/nope\.jar/);
});
```

- [ ] **Step 2:** fails. **Step 3: Implement.** `DEFAULT_OPTIONS = { pauseOnLostFocus: "false", tutorialStep: "none", onboardAccessibility: "false", soundCategory_master: "0.0", narrator: "0", renderDistance: "8", inactivityFpsLimit: "minimized" }`. Validate every extra mod exists (`CraftwireError("INVALID_PARAMS", "<path> does not exist", "Pass absolute paths to mod jars.")`) before touching anything. `rmSync(mods, {recursive, force})`, `mkdirSync`, `copyFileSync` each. Options: parse existing `key:value` lines into a Map, add defaults only for absent keys, write back in original order with new keys appended.
- [ ] **Step 4:** passes. **Step 5: Commit** `feat(hub): per-username client instance folders`.

### Task 9: ClientManager

**Files:**
- Create: `hub/src/client/client-manager.ts`, `hub/src/client/launcher.ts`, `hub/test/client-manager.test.ts`
- Modify: `hub/src/dev/launch.ts` (export `serverAddress(serverDir): { host: string; port: number; onlineMode: boolean }` reading `server.properties`)

**Interfaces:**
- Consumes: Tasks 3–8, `AgentServer`, `ServerManager.status()`, `probeJavaMajor`, `killTree`, `waitUntil`, `pruneStopped`, `RingBuffer`, `pathKey`, `samePath`.
- Produces:
  - `launcher.ts`: `prepareClient(o: ClientStartOptions & { root: string; onProgress }): Promise<{ command: string; args: string[]; gameDir: string; downloadedBytes: number }>` — the real pipeline (fetchVersion → fabric profile → files → fetchAll → prepareInstance → buildLaunch, java check against `version.javaVersion.majorVersion` → `JAVA_TOO_OLD`, Linux display check → `xvfb-run -a` wrapper or `NO_DISPLAY`).
  - `interface ClientStartOptions { server?: string; username?: string; mods?: string[]; visible?: boolean; windowSize?: { width: number; height: number }; java?: string; sounds?: boolean; timeoutMs?: number }`.
  - `class ClientManager { constructor(o: { agents: AgentServer; servers: ServerManager; home: string; prepare?: typeof prepareClient; stopTimeoutMs?: number; readyPollMs?: number }); start(o): Promise<ClientStatus>; stop(username?: string): Promise<{ username: string; stopped: true; forced: boolean }>; status(tail?: number): { clients: ClientStatus[] }; shutdown(): Promise<void>; }`.
  - `interface ClientStatus { username: string; state: "downloading" | "starting" | "running" | "stopping" | "stopped" | "crashed"; instance?: string | null; server?: string; pid?: number; gameDir?: string; progress?: { done: number; total: number }; downloadedBytes?: number; readyMs?: number; exitCode?: number | null; logTail: string[] }`.
- [ ] **Step 1: Failing tests** (`prepare` injected: returns `{ command: process.execPath, args: ["-e", script], gameDir, downloadedBytes: 0 }`; a real `AgentServer` on port 0 as in `server-manager.test.ts`; the test connects a fake client agent with that `gameDir` to simulate the game):

```ts
it("is ready when the agent with its gameDir is in a world", async () => {
  const start = mgr.start({ username: "Bob", server: "127.0.0.1:1" });
  await waitFor(() => mgr.status().clients[0]?.state === "starting");
  const agent = await connectFakeAgent(port, { token, kind: "client", gameDir: gameDirOf("Bob") });
  agent.onRequest("player.state", () => ({ position: { x: 0, y: 64, z: 0 } }));
  const s = await start;
  expect(s).toMatchObject({ username: "Bob", state: "running", instance: agent.instanceId });
});
it("reports CLIENT_CRASHED with the log tail when the game exits before ready", async () => {
  prepareScript = "console.log('Exception in thread main: boom'); process.exit(3)";
  await expect(mgr.start({ username: "Bob" })).rejects.toMatchObject({ code: "CLIENT_CRASHED", details: { logTail: expect.arrayContaining([expect.stringContaining("boom")]) } });
});
it("reports JOIN_FAILED with the disconnect screen", async () => {
  // agent answers player.state with NOT_IN_WORLD and gui.read with a DisconnectedScreen
});
it("a second start for a running username fails", async () => { /* ALREADY_RUNNING */ });
it("stop sends client.quit, then kills after the timeout", async () => { /* quitSeen === true, forced === true */ });
it("shutdown stops running clients", async () => { /* state stopped after shutdown() */ });
it("uses the single managed server and refuses an online-mode one", async () => {
  // fake ServerManager.status() with one running server whose server.properties has online-mode=true → ONLINE_MODE_SERVER, prepare never called
});
it("without a server and several managed servers asks which one", async () => { /* NO_SERVER */ });
```

Write each placeholder comment above as full test code following the first test's pattern before running (fake agent answers `gui.read` with `{ open: true, type: "DisconnectedScreen", title: "Failed to connect to the server", texts: ["Outdated client"] }`).
- [ ] **Step 2:** fails (module missing).
- [ ] **Step 3: Implement** `ClientManager` with the `ServerManager` patterns: a `Map<username, Managed>`, an `exclusive` queue, `agents.on("connected")` matching `i.kind === "client" && i.gameDir && samePath(i.gameDir, m.gameDir)`. `start`: validate username (`INVALID_PARAMS`), `ALREADY_RUNNING`, resolve server (explicit; else the single live entry of `servers.status().servers` → `serverAddress(dir)`, `ONLINE_MODE_SERVER` when `onlineMode`; several → `NO_SERVER`), set state `downloading`, `await prepare(...)`, state `starting`, spawn (`windowsHide: true`, `detached` on POSIX, `cwd: gameDir`, env `CRAFTWIRE_HOME: home`), log lines into a `RingBuffer(2000)`, race agent+in-world (poll `player.state` every `readyPollMs` (500); on `NOT_IN_WORLD` also call `gui.read`: a `type` containing `Disconnected` → `JOIN_FAILED` with that screen in details) vs exit (`CLIENT_CRASHED` with `logTail` and the newest `crash-reports/*.txt` path) vs `timeoutMs` (`TIMEOUT`, process kept). Without a server, ready = agent connected. `stop`: `client.quit` request (5 s timeout, errors ignored), wait `stopTimeoutMs` (10 000) for exit, else `killTree`. `shutdown`: stop all live.
- [ ] **Step 4:** tests pass; full hub suite green.
- [ ] **Step 5: Commit** `feat(hub): ClientManager runs and stops headless clients`.

### Task 10: The `client_process` tool

**Files:**
- Create: `hub/src/tools/client-process-tools.ts`, `hub/test/client-process-tools.test.ts`
- Modify: `hub/src/tools/registry.ts` (`ToolContext.clients: ClientManager`), `hub/src/server.ts` (register + instructions line), `hub/src/cli.ts` (construct `ClientManager`, shutdown order: clients, then servers), `hub/test/cli.test.ts` (tool list), test contexts that build a `ToolContext`

**Interfaces:**
- Consumes: `ClientManager`.
- Produces: MCP tool `client_process {action: start|stop|status, server?, username?, mods?, visible?, windowSize?, java?, sounds?, timeoutMs?, tail?}`.
- [ ] **Step 1: Failing test:** tool registered with the description and `start` forwards to a stub `ClientManager` (`start` resolves `{username:"Bob",state:"running"}`), `status` returns `{clients:[]}`; `cli.test.ts` tool list includes `client_process`.
- [ ] **Step 2:** fails. **Step 3: Implement** with zod: `username: z.string().regex(/^[A-Za-z0-9_]{3,16}$/).optional()`, `mods: z.array(z.string()).max(50).optional()`, `windowSize: z.object({ width: z.number().int().min(320).max(7680), height: z.number().int().min(240).max(4320) }).optional()`, `timeoutMs: z.number().int().min(1000).max(900_000).default(300_000)`, `tail: z.number().int().min(0).max(500).default(20)`. Description: "Start, stop or inspect a headless Minecraft client run by the hub (offline mode, hidden window). start downloads Minecraft 26.2 + Fabric on first use (cached in ~/.craftwire/client), starts the client with the Craftwire agent and joins `server` (default: the server started by server_process; it must run online-mode=false) and returns once the player is in the world — then every client tool works on it (instance in the result). mods adds extra mod jars (e.g. the mod you are developing, Sodium). Clients stop when the hub exits." Instructions line in `server.ts`: "No game open? client_process starts a hidden client the hub runs itself (offline, dev servers)."
- [ ] **Step 4:** passes; full suite green.
- [ ] **Step 5: Commit** `feat(hub): client_process tool`.

### Task 11: Real end-to-end run

**Files:**
- Create: `hub/test-e2e/client-process.e2e.test.ts`
- Modify: `.github/workflows/ci.yml` (dev-loop-e2e job: `xvfb-run -a npm run test:e2e`, `actions/cache` for `~/.craftwire-e2e-client` keyed on `hashFiles('gradle.properties')`)

**Interfaces:**
- Consumes: everything; the E2E Paper setup in `hub/test-e2e/paper.ts`/`dev-loop.e2e.test.ts` (server dir, `eula=true` for the suite's own throwaway server, `online-mode=false`, the test-fixtures plugin).
- [ ] **Step 1: Spike check (spec "Spike first").** Locally: build, then drive a hub with `client_process start {server}` against a throwaway server (as in the README showcase: scratchpad server, own `CRAFTWIRE_HOME`). Confirm: window invisible, `screenshot` returns a rendered frame at normal speed, no crash without sound assets. If the hidden window does not render, ledger a ruling and switch `hidden` to an off-screen window (`glfwSetWindowPos` far outside the desktop, still `GLFW_VISIBLE`).
- [ ] **Step 2: E2E test:** start Paper (`server_process`), `client_process start` (CRAFTWIRE_HOME and cache root inside the e2e dir), expect `running`; `screenshot {hud:false}` → decode PNG, assert not one flat colour; `chat {action:"command", command:"cwfixture menu"}` + `wait_for screen_open` + `gui_read` title `Fixture Menu`; `client_process stop` → `stopped`.
- [ ] **Step 3:** `npm run test:e2e` locally: all pass (first run downloads ~150 MB).
- [ ] **Step 4:** CI change, push branch, CI green.
- [ ] **Step 5: Commit** `test(e2e): client_process joins a real Paper server`.

### Task 12: Docs and skills

**Files:**
- Modify: `README.md` (section "Let the AI run its own client" under "What it can do", tools table row "Dev loop" gains `client_process`, FAQ "Do I have to keep the game open?"), `hub/README.md`, `claude-plugin/skills/craftwire/SKILL.md`, `claude-plugin/skills/paper-plugin-dev/SKILL.md`, `claude-plugin/skills/fabric-mod-dev/SKILL.md`, `docs/acceptance/m5-client-process.md`
- [ ] **Step 1:** Write the docs: what `client_process` does, offline/dev-server requirement (`online-mode=false`), first-run download size, `mods` for mod testing, "you must own Minecraft Java Edition; offline mode is for local testing, the same model as Fabric's development client".
- [ ] **Step 2:** `npx vitest run` (plugin-manifest/skill tests) green.
- [ ] **Step 3: Commit** `docs: client_process`.
