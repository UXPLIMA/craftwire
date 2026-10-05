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
    // Attach the expectation before closing, so the rejection is never momentarily unhandled.
    const rejected = expect(server.request(agent.instanceId, "slow", {}, 5000)).rejects.toMatchObject({ code: "AGENT_DISCONNECTED" });
    await agent.close();
    await rejected;
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
