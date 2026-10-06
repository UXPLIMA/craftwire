import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, join } from "node:path";
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
    const gradlew = process.platform === "win32" ? ".\\gradlew.bat" : "sh ./gradlew";
    const r = json(await hub.call("plugin_deploy", {
      projectDir: repoRoot, buildCommand: `${gradlew} :test-fixtures:jar --console=plain`, jarGlob: "test-fixtures/build/libs/*.jar",
      ...(javaHome ? { javaHome } : {}), serverDir, timeoutMs: 600_000,
    }));
    expect(r.build?.command, JSON.stringify(r)).toContain(":test-fixtures:jar");
    expect(r.install.replaced).toHaveLength(1);
    expect(r.loaded.enabled).toBe(true);
  });

  it("stages a jar into plugins/update for a running server, and Paper swaps it in on restart", async () => {
    const installed = join(serverDir, "plugins", basename(fixtureJar));
    // Same plugin, different bytes: a zip comment changes the file without changing its entries.
    const bytes = readFileSync(installed);
    const eocd = bytes.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
    const comment = Buffer.from("craftwire e2e update");
    const changed = Buffer.concat([bytes.subarray(0, eocd + 20), Buffer.from([comment.length, 0]), comment]);
    const newJar = join(mkdtempSync(join(tmpdir(), "cw-upd-")), basename(fixtureJar));
    writeFileSync(newJar, changed);

    const staged = json(await hub.call("plugin_deploy", { jar: newJar, serverDir, restart: false }));
    expect(staged.install).toMatchObject({ installed: join(serverDir, "plugins", "update", basename(fixtureJar)), needsRestart: true });
    expect(json(await hub.call("server_process", { action: "restart", serverDir, timeoutMs: 600_000 })).state).toBe("running");
    expect(readFileSync(installed).equals(changed)).toBe(true);
    expect(existsSync(join(serverDir, "plugins", "update", basename(fixtureJar)))).toBe(false);
    expect(json(await hub.call("plugin_manage", { action: "info", name: "CraftwireFixture" })).enabled).toBe(true);
  });

  it("a plugin's own tools from the Craftwire API show up in MCP and run on the server", async () => {
    const deadline = Date.now() + 10_000;
    let names: string[] = [];
    while (Date.now() < deadline && !names.includes("craftwirefixture_greet")) {
      names = (await hub.client.listTools()).tools.map((t) => t.name);
      await new Promise((r) => setTimeout(r, 100));
    }
    expect(names).toEqual(expect.arrayContaining(["craftwirefixture_greet", "craftwirefixture_refuse", "craftwirefixture_crash"]));
    expect(json(await hub.call("craftwirefixture_greet", { name: "Alex" }))).toMatchObject({ greeting: "Hello Alex", mainThread: true });
    expect(json(await hub.call("craftwirefixture_refuse", {}))).toMatchObject({ code: "FIXTURE_REFUSED", hint: "Ask nicely." });
    const crash = json(await hub.call("craftwirefixture_crash", {}));
    expect(crash.code).toBe("EXTENSION_FAILED");
    const bugs = json(await hub.call("exceptions", {})).exceptions;
    expect(bugs.some((b: { type: string; message: string }) => b.type === "java.lang.IllegalStateException" && b.message === "fixture tool crashed")).toBe(true);
  });

  it("drives a bot through a plugin menu", async () => {
    const spawned = json(await hub.call("bot_spawn", { names: ["E2eBot"] }));
    expect(spawned.bots[0].name).toBe("E2eBot");
    await hub.call("bot_action", { bot: "E2eBot", action: "command", command: "cwfixture menu", collectMs: 100 });
    const gui = json(await hub.call("bot_action", { bot: "E2eBot", action: "gui_read" }));
    expect(gui.title).toBe("Fixture Menu");
    const click = json(await hub.call("bot_action", { bot: "E2eBot", action: "gui_click", slot: 4 }));
    expect(click.gui.open).toBe(false);
    const inbox = json(await hub.call("bot_action", { bot: "E2eBot", action: "messages" }));
    expect(JSON.stringify(inbox)).toContain("fixture: clicked 4");
    expect(json(await hub.call("bot_remove", { all: true })).removed).toEqual(["E2eBot"]);
  });

  it("runs scenario files: a plugin flow that passes and one that fails with its context", async () => {
    const dir = mkdtempSync(join(tmpdir(), "cw-scenarios-"));
    writeFileSync(join(dir, "menu.cwtest.json"), JSON.stringify({
      name: "fixture menu and shop",
      bots: { names: ["Scn"], location: { x: 0.5, y: -60, z: 0.5 } },
      setup: [{ command: "minecraft:setblock 3 -60 3 minecraft:air" }],
      steps: [
        { bot: "Scn", command: "/cwfixture menu", expect: { path: "success", equals: true } },
        { bot: "Scn", action: "gui_read", expect: { path: "title", equals: "Fixture Menu" }, within: 2000 },
        { bot: "Scn", action: "gui_click", slot: 4 },
        { expect_message: { bot: "Scn", matches: "clicked 4" } },
        { bot: "Scn", command: "/cwfixture buy" },
        { expect_event: { type: "FixtureShopEvent", player: "Scn", matches: '"cancelled":true' } },
        { command: "minecraft:setblock 3 -60 3 minecraft:stone" },
        { expect_block: { x: 3, y: -60, z: 3, is: "stone" } },
        { wait: { condition: "player_near", player: "Scn", x: 0.5, y: -60, z: 0.5, radius: 3, timeoutMs: 2000 }, save: "near" },
        { expect: { tool: "server_command", args: { command: "say ${near.value.distance}" }, path: "output", exists: true } },
        { expect_no_exceptions: {} },
      ],
      cleanup: [{ command: "minecraft:setblock 3 -60 3 minecraft:air" }],
    }));
    writeFileSync(join(dir, "broken.cwtest.json"), JSON.stringify({
      name: "expects the wrong reply",
      bots: ["Scn2"],
      steps: [
        { bot: "Scn2", command: "/cwfixture blocked" },
        { expect_message: { bot: "Scn2", matches: "welcome", within: 500 } },
      ],
    }));
    const r = json(await hub.call("scenario_run", { files: [dir] }));
    const [broken, menu] = r.scenarios;
    expect(menu, JSON.stringify(menu, null, 2)).toMatchObject({ name: "fixture menu and shop", passed: true });
    expect(broken).toMatchObject({
      name: "expects the wrong reply", passed: false,
      failure: { index: 1, message: "Scn2 received no message matching /welcome/", actual: ["fixture: blocked"], context: { Scn2: { messages: expect.arrayContaining(["fixture: blocked"]) } } },
    });
    expect(json(await hub.call("world_query", { action: "players" })).players.some((p: { name: string }) => p.name.startsWith("Scn"))).toBe(false);
  });

  it("stops the server gracefully", async () => {
    expect(json(await hub.call("server_process", { action: "stop", serverDir }))).toMatchObject({ stopped: true, forced: false });
    expect(json(await hub.call("server_process", { action: "status" })).servers[0].state).toBe("stopped");
  });
});
