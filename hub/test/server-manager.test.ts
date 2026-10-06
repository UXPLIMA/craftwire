import { spawn } from "node:child_process";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { AgentServer } from "../src/agents.js";
import { writeHubConfig } from "../src/config.js";
import { diagnoseCrash, pidAlive, pruneStopped, ServerManager, type ServerManagerOptions, waitUntil } from "../src/dev/server-manager.js";
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

  it("sees through a java launcher: reports the JVM's pid and a forced stop kills the whole tree", async () => {
    const { servers, agents } = await setup({ stopTimeoutMs: 1000 });
    const dir = makeServerDir({ craftwire: true });
    const s = await servers.start({ serverDir: dir, java: process.execPath, jvmArgs: [FAKE_PAPER, "--launcher", "--mode=agent", "--nostop"], jar: "server.jar" });
    const jvmPid = agents.instances()[0]!.pid!;
    expect(s.pid).toBe(jvmPid);
    expect(await servers.stop(dir)).toMatchObject({ forced: true });
    expect(await waitUntil(() => !pidAlive(jvmPid), 5000)).toBe(true);
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

  it("forgets all but the newest stopped servers, never a live one", () => {
    const m = new Map<string, { state: "running" | "stopped" | "crashed"; startedAt: number }>();
    for (let i = 0; i < 3; i++) m.set(`live${i}`, { state: "running", startedAt: i });
    for (let i = 0; i < 15; i++) m.set(`old${i}`, { state: i % 2 ? "stopped" : "crashed", startedAt: 100 + i });
    pruneStopped(m, 10);
    expect([...m.keys()].filter((k) => k.startsWith("live"))).toHaveLength(3);
    expect([...m.keys()].filter((k) => k.startsWith("old")).sort()).toEqual(
      Array.from({ length: 10 }, (_, i) => `old${i + 5}`).sort());
  });

  it("names known crash causes", () => {
    expect(diagnoseCrash(["java.io.IOException: session.lock: already locked (possibly by other Minecraft instance?)"]).code).toBe("WORLD_LOCKED");
    expect(diagnoseCrash(["UnsupportedClassVersionError: class file version 69.0"]).code).toBe("JAVA_TOO_OLD");
    expect(diagnoseCrash(["something odd"]).code).toBe("SERVER_CRASHED");
  });
});
