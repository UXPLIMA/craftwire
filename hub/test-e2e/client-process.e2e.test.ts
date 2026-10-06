import { copyFileSync, existsSync, mkdirSync, readdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { prepareClient } from "../src/client/launcher.js";
import { json, startHub } from "../test/helpers/hub.js";
import { ensurePaper, freePort, gradleProperties, repoRoot } from "./paper.js";

const props = gradleProperties();
const e2eDir = join(repoRoot, "hub", ".e2e");
const serverDir = join(e2eDir, "client-server");
/** Minecraft, libraries and assets for client_process: kept between runs (and cached on CI). */
const clientRoot = join(e2eDir, "client-cache");
const pluginJar = process.env.CRAFTWIRE_E2E_PLUGIN ?? join(repoRoot, "agent-paper", "build", "libs", `craftwire-paper-${props.craftwire_version}.jar`);
const fixtureJar = process.env.CRAFTWIRE_E2E_FIXTURE ?? join(repoRoot, "test-fixtures", "build", "libs", `craftwire-test-fixtures-${props.craftwire_version}.jar`);
const agentJar = join(repoRoot, "agent-fabric", "build", "libs", `craftwire-agent-fabric-${props.craftwire_version}.jar`);
const javaHome = process.env.CRAFTWIRE_E2E_JAVA_HOME ?? process.env.JAVA_HOME;
let hub: Awaited<ReturnType<typeof startHub>>;

beforeAll(async () => {
  for (const f of [pluginJar, fixtureJar, agentJar]) {
    if (!existsSync(f)) throw new Error(`${f} is missing: run ./gradlew :agent-paper:build :agent-fabric:build :test-fixtures:build first`);
  }
  const paper = await ensurePaper(join(e2eDir, "cache"), props);
  const plugins = join(serverDir, "plugins");
  mkdirSync(plugins, { recursive: true });
  for (const f of readdirSync(serverDir)) if (f.startsWith("world")) rmSync(join(serverDir, f), { recursive: true, force: true });
  for (const f of readdirSync(plugins)) if (f.endsWith(".jar") || f === "Craftwire") rmSync(join(plugins, f), { recursive: true, force: true });
  copyFileSync(paper, join(serverDir, "paper.jar"));
  copyFileSync(pluginJar, join(plugins, "craftwire-paper.jar"));
  copyFileSync(fixtureJar, join(plugins, "craftwire-test-fixtures.jar"));
  writeFileSync(join(serverDir, "eula.txt"), "eula=true\n"); // this suite's own throwaway server, like the Java ITs
  writeFileSync(join(serverDir, "server.properties"), [
    "server-ip=127.0.0.1", `server-port=${await freePort()}`, "online-mode=false", "level-type=minecraft\\:flat",
    "generate-structures=false", "view-distance=4", "simulation-distance=4", "max-players=4", "motd=craftwire-e2e-client",
    "spawn-protection=0", "difficulty=peaceful", "white-list=false", // 26.3+ whitelists new servers by default
  ].join("\n") + "\n");
  const java = javaHome ? join(javaHome, "bin", process.platform === "win32" ? "java.exe" : "java") : "java";
  writeFileSync(join(serverDir, process.platform === "win32" ? "start.bat" : "start.sh"), `"${java}" -Xmx2G -jar paper.jar --nogui\n`);
  hub = await startHub({ writeHubJson: true, agentWaitMs: 60_000, stopTimeoutMs: 120_000, prepareClient: (p) => prepareClient(p), clientRoot, clientStopTimeoutMs: 30_000 });
  const r = json(await hub.call("server_process", { action: "start", serverDir, timeoutMs: 600_000 }));
  expect(r).toMatchObject({ state: "running" });
});

afterAll(async () => {
  await hub?.close();
});

describe("client_process against a real Paper server", () => {
  it("downloads, starts a hidden client and joins the server started by server_process", async () => {
    const r = json(await hub.call("client_process", { action: "start", username: "E2E", timeoutMs: 600_000 }));
    expect(r, JSON.stringify(r)).toMatchObject({ state: "running", username: "E2E", instance: expect.stringMatching(/^client-\d+$/) });
    // The client runs the version the server reports.
    expect(r.version).toBe(props.minecraft_version);
    const players = json(await hub.call("server_command", { command: "list", collectMs: 300 }));
    expect(players.output.join("\n")).toContain("E2E");
  });

  it("renders frames in the hidden window", async () => {
    const shot = join(e2eDir, "client-shot.png");
    rmSync(shot, { force: true });
    const r = await hub.call("screenshot", { instance: "E2E", hud: false, savePath: shot });
    expect(r.isError, JSON.stringify(r.content)).toBeFalsy();
    // A flat colour compresses to a few KB; a rendered world at 1280x720 is far larger.
    expect(statSync(shot).size).toBeGreaterThan(50_000);
  });

  it("reads a plugin menu opened on the client", async () => {
    await hub.call("chat", { instance: "E2E", action: "command", text: "cwfixture menu" });
    const w = await hub.call("wait_for", { condition: "screen_open", instance: "E2E", timeoutMs: 15_000 });
    expect(w.isError, JSON.stringify(w.content)).toBeFalsy();
    const gui = json(await hub.call("gui_read", { instance: "E2E" }));
    expect(gui.title).toBe("Fixture Menu");
    expect(gui.slots.find((s: { slot: number }) => s.slot === 4)?.id).toBe("minecraft:diamond");
  });

  it("stops the client cleanly", async () => {
    const r = json(await hub.call("client_process", { action: "stop", username: "E2E" }));
    expect(r).toMatchObject({ stopped: true, forced: false });
  });
});
