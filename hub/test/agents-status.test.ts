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
