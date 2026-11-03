/** The proxy tool and wait_for server_switch against a fake Velocity agent. */
import { afterEach, describe, expect, it } from "vitest";
import { connectFakeAgent, type FakeAgent } from "./helpers/fakeAgent.js";
import { json, startHub } from "./helpers/hub.js";

let hub: Awaited<ReturnType<typeof startHub>> | undefined;
afterEach(async () => {
  await hub?.close();
  hub = undefined;
});

const tools = async () => (await hub!.client.listTools()).tools.map((t) => t.name);

async function proxy(): Promise<FakeAgent> {
  // the fake agent's typing knows clients and servers; the hub takes any agent kind in hello
  return connectFakeAgent(hub!.port, { token: hub!.token, kind: "proxy" as "server", name: "velocity" });
}

/** The event ProxyEvents sends when a player changes server (ServerConnectedEvent). */
const switched = (name: string, from: string | null, to: string) =>
  ({ action: "switch", name, server: to, ...(from ? { from } : {}), text: `${name} ${from ? `${from} ` : ""}-> ${to}` });

describe("proxy", () => {
  it("is offered only while a proxy is connected", async () => {
    hub = await startHub();
    expect(await tools()).not.toContain("proxy");
    const p = await proxy();
    await expect.poll(tools).toContain("proxy");
    await p.close();
    await expect.poll(tools).not.toContain("proxy");
  });

  it("forwards actions to the proxy and checks what each needs first", async () => {
    hub = await startHub();
    const p = await proxy();
    const seen: Record<string, unknown>[] = [];
    p.onRequest("proxy.send", (a) => { seen.push(a); return { sent: true, server: a.server }; });
    await expect.poll(tools).toContain("proxy");
    expect(json(await hub.call("proxy", { action: "send", player: "Bot1", server: "games" }))).toEqual({ sent: true, server: "games" });
    expect(seen).toEqual([{ player: "Bot1", server: "games" }]);
    const r = await hub.call("proxy", { action: "send", player: "Bot1" });
    expect(r.isError).toBe(true);
    expect(JSON.stringify(r.content)).toContain("send needs server");
    expect(seen).toHaveLength(1);
  });

  it("wait_for server_switch matches a switch, not a join, and by its text", async () => {
    hub = await startHub();
    const p = await proxy();
    const waiting = hub.call("wait_for", { condition: "server_switch", pattern: "^Bob lobby -> games$", timeoutMs: 5000 });
    await new Promise((r) => setTimeout(r, 200));
    p.emit("proxy", { action: "join", name: "Bob", text: "Bob joined the proxy" });
    p.emit("proxy", switched("Alice", "lobby", "games"));
    p.emit("proxy", switched("Bob", null, "lobby"));
    p.emit("proxy", switched("Bob", "lobby", "games"));
    const r = json(await waiting);
    expect(r.matched).toBe(true);
    expect(JSON.stringify(r)).toContain("Bob lobby -> games");
  });
});
