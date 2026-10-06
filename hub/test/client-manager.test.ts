import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { AgentServer } from "../src/agents.js";
import { ClientManager, type ClientManagerOptions, type PrepareClient } from "../src/client/client-manager.js";
import { writeHubConfig } from "../src/config.js";
import { waitUntil } from "../src/dev/server-manager.js";
import { connectFakeAgent, type FakeAgent } from "./helpers/fakeAgent.js";

const TOKEN = "e".repeat(64);
const STAY = "setInterval(() => {}, 1000)";
const cleanups: (() => Promise<void>)[] = [];
afterEach(async () => {
  while (cleanups.length) await cleanups.pop()!();
});

interface Setup {
  mgr: ClientManager;
  port: number;
  root: string;
  prepared: Parameters<PrepareClient>[0][];
  gameDirOf: (name: string) => string;
}

async function setup(o: { script?: string; servers?: { serverDir: string; state: string }[] } & Partial<ClientManagerOptions> = {}): Promise<Setup> {
  const home = mkdtempSync(join(tmpdir(), "cw-cm-"));
  const root = join(home, "client");
  const agents = new AgentServer({ token: TOKEN, port: 0 });
  const port = await agents.listen();
  writeHubConfig({ port, token: TOKEN }, home);
  const prepared: Parameters<PrepareClient>[0][] = [];
  const gameDirOf = (name: string) => join(root, "instances", name);
  const prepare: PrepareClient = async (p) => {
    prepared.push(p);
    mkdirSync(gameDirOf(p.username), { recursive: true });
    return { command: process.execPath, args: ["-e", o.script ?? STAY], gameDir: gameDirOf(p.username), downloadedBytes: 42 };
  };
  const { script: _script, servers, ...rest } = o;
  const mgr = new ClientManager({
    agents, home, root, prepare,
    servers: { status: () => ({ servers: servers ?? [] }) },
    stopTimeoutMs: 1500, quitTimeoutMs: 500, readyPollMs: 50,
    ...rest,
  });
  cleanups.push(async () => { await mgr.shutdown(); await agents.close(); });
  return { mgr, port, root, prepared, gameDirOf };
}

async function inWorldAgent(port: number, gameDir: string): Promise<FakeAgent> {
  const a = await connectFakeAgent(port, { token: TOKEN, kind: "client", gameDir });
  a.onRequest("player.state", () => ({ position: { x: 0, y: 64, z: 0 } }));
  a.onRequest("client.quit", () => ({ quitting: true }));
  cleanups.push(() => a.close());
  return a;
}

const notInWorld = () => { throw Object.assign(new Error("The player is not in a world."), { code: "NOT_IN_WORLD" }); };

function serverDir(props: string): string {
  const dir = mkdtempSync(join(tmpdir(), "cw-srv-"));
  writeFileSync(join(dir, "server.properties"), props);
  return dir;
}

describe("ClientManager", () => {
  it("is ready when the agent with its gameDir is in a world", async () => {
    const { mgr, port, gameDirOf } = await setup();
    const start = mgr.start({ username: "Bob", server: "127.0.0.1:25599" });
    await waitUntil(() => mgr.status().clients[0]?.state === "starting", 5000, 20);
    const agent = await inWorldAgent(port, gameDirOf("Bob"));
    const s = await start;
    expect(s).toMatchObject({ username: "Bob", state: "running", instance: agent.instanceId, server: "127.0.0.1:25599", downloadedBytes: 42 });
    expect(s.pid).toBeGreaterThan(0);
  });

  it("waits for the player to reach the world, not just for the agent", async () => {
    const { mgr, port, gameDirOf } = await setup();
    const start = mgr.start({ username: "Bob", server: "127.0.0.1:25599" });
    await waitUntil(() => mgr.status().clients[0]?.state === "starting", 5000, 20);
    const agent = await connectFakeAgent(port, { token: TOKEN, kind: "client", gameDir: gameDirOf("Bob") });
    cleanups.push(() => agent.close());
    let inWorld = false;
    agent.onRequest("player.state", () => (inWorld ? { position: {} } : notInWorld()));
    agent.onRequest("gui.read", () => ({ open: true, type: "ConnectScreen", title: "Connecting to the server..." }));
    await new Promise((r) => setTimeout(r, 300));
    expect(mgr.status().clients[0]!.state).toBe("starting");
    inWorld = true;
    expect((await start).state).toBe("running");
  });

  it("without a server, is ready as soon as the agent connects", async () => {
    const { mgr, port, gameDirOf, prepared } = await setup();
    const start = mgr.start({ username: "Bob" });
    await waitUntil(() => mgr.status().clients[0]?.state === "starting", 5000, 20);
    const agent = await connectFakeAgent(port, { token: TOKEN, kind: "client", gameDir: gameDirOf("Bob") });
    cleanups.push(() => agent.close());
    expect((await start).state).toBe("running");
    expect(prepared[0]!.server).toBeUndefined();
  });

  it("reports CLIENT_CRASHED with the log tail when the game exits before ready", async () => {
    const { mgr } = await setup({ script: "console.log('Exception in thread main: boom'); process.exit(3)" });
    const err = await mgr.start({ username: "Bob" }).catch((e: unknown) => e);
    expect(err).toMatchObject({ code: "CLIENT_CRASHED", details: { exitCode: 3 } });
    expect((err as { details: { logTail: string[] } }).details.logTail.join("\n")).toContain("boom");
    expect(mgr.status().clients[0]!.state).toBe("crashed");
  });

  it("reports JOIN_FAILED with the disconnect screen", async () => {
    const { mgr, port, gameDirOf } = await setup();
    const start = mgr.start({ username: "Bob", server: "127.0.0.1:25599" }).catch((e: unknown) => e);
    await waitUntil(() => mgr.status().clients[0]?.state === "starting", 5000, 20);
    const agent = await connectFakeAgent(port, { token: TOKEN, kind: "client", gameDir: gameDirOf("Bob") });
    cleanups.push(() => agent.close());
    agent.onRequest("player.state", notInWorld);
    agent.onRequest("gui.read", () => ({ open: true, type: "DisconnectedScreen", title: "Failed to connect to the server", texts: ["Connection refused"] }));
    agent.onRequest("client.quit", () => ({ quitting: true }));
    expect(await start).toMatchObject({ code: "JOIN_FAILED", details: { screen: { type: "DisconnectedScreen" } } });
  });

  it("a client that missed the timeout still becomes running once it is ready", async () => {
    const { mgr, port, gameDirOf } = await setup();
    await expect(mgr.start({ username: "Bob", timeoutMs: 1000 })).rejects.toMatchObject({ code: "TIMEOUT" });
    expect(mgr.status().clients[0]!.state).toBe("starting");
    await inWorldAgent(port, gameDirOf("Bob"));
    expect(await waitUntil(() => mgr.status().clients[0]!.state === "running", 3000, 20)).toBe(true);
  });

  it("a second start for a running username fails", async () => {
    const { mgr, port, gameDirOf } = await setup();
    const first = mgr.start({ username: "Bob" });
    await waitUntil(() => mgr.status().clients[0]?.state === "starting", 5000, 20);
    await inWorldAgent(port, gameDirOf("Bob"));
    await first;
    await expect(mgr.start({ username: "Bob" })).rejects.toMatchObject({ code: "ALREADY_RUNNING" });
  });

  it("rejects an invalid username before preparing anything", async () => {
    const { mgr, prepared } = await setup();
    await expect(mgr.start({ username: "a b" })).rejects.toMatchObject({ code: "INVALID_PARAMS" });
    expect(prepared).toHaveLength(0);
  });

  it("stop sends client.quit, then kills a client that does not exit", async () => {
    const { mgr, port, gameDirOf } = await setup();
    const start = mgr.start({ username: "Bob" });
    await waitUntil(() => mgr.status().clients[0]?.state === "starting", 5000, 20);
    const agent = await inWorldAgent(port, gameDirOf("Bob"));
    let quitSeen = false;
    agent.onRequest("client.quit", () => { quitSeen = true; return { quitting: true }; });
    await start;
    const r = await mgr.stop("Bob");
    expect(quitSeen).toBe(true);
    expect(r).toMatchObject({ username: "Bob", stopped: true, forced: true });
    expect(mgr.status().clients[0]!.state).toBe("stopped");
  });

  it("shutdown stops running clients", async () => {
    const { mgr, port, gameDirOf } = await setup();
    const start = mgr.start({ username: "Bob" });
    await waitUntil(() => mgr.status().clients[0]?.state === "starting", 5000, 20);
    await inWorldAgent(port, gameDirOf("Bob"));
    await start;
    await mgr.shutdown();
    expect(mgr.status().clients[0]!.state).toBe("stopped");
  });

  it("joins the single running managed server by default", async () => {
    const dir = serverDir("server-port=25601\nonline-mode=false\n");
    const { mgr, port, gameDirOf, prepared } = await setup({ servers: [{ serverDir: dir, state: "running" }] });
    const start = mgr.start({ username: "Bob" });
    await waitUntil(() => mgr.status().clients[0]?.state === "starting", 5000, 20);
    await inWorldAgent(port, gameDirOf("Bob"));
    expect((await start).server).toBe("127.0.0.1:25601");
    expect(prepared[0]!.server).toBe("127.0.0.1:25601");
  });

  it("refuses an online-mode default server before downloading", async () => {
    const dir = serverDir("server-port=25601\nonline-mode=true\n");
    const { mgr, prepared } = await setup({ servers: [{ serverDir: dir, state: "running" }] });
    await expect(mgr.start({ username: "Bob" })).rejects.toMatchObject({ code: "ONLINE_MODE_SERVER" });
    expect(prepared).toHaveLength(0);
  });

  it("asks which server when several run", async () => {
    const a = serverDir("server-port=25601\nonline-mode=false\n");
    const b = serverDir("server-port=25602\nonline-mode=false\n");
    const { mgr } = await setup({ servers: [{ serverDir: a, state: "running" }, { serverDir: b, state: "running" }] });
    await expect(mgr.start({ username: "Bob" })).rejects.toMatchObject({ code: "NO_SERVER" });
  });
});
