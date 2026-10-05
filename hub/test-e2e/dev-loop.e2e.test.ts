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
    const gradlew = process.platform === "win32" ? ".\\gradlew.bat" : "sh ./gradlew";
    const r = json(await hub.call("plugin_deploy", {
      projectDir: repoRoot, buildCommand: `${gradlew} :test-fixtures:jar --console=plain`, jarGlob: "test-fixtures/build/libs/*.jar",
      ...(javaHome ? { javaHome } : {}), serverDir, timeoutMs: 600_000,
    }));
    expect(r.build?.command, JSON.stringify(r)).toContain(":test-fixtures:jar");
    expect(r.install.replaced).toHaveLength(1);
    expect(r.loaded.enabled).toBe(true);
  });

  it("stops the server gracefully", async () => {
    expect(json(await hub.call("server_process", { action: "stop", serverDir }))).toMatchObject({ stopped: true, forced: false });
    expect(json(await hub.call("server_process", { action: "status" })).servers[0].state).toBe("stopped");
  });
});
