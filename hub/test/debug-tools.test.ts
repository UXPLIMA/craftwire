import { afterEach, describe, expect, it } from "vitest";
import { connectFakeAgent } from "./helpers/fakeAgent.js";
import { json, startHub, TOKEN } from "./helpers/hub.js";

let hub: Awaited<ReturnType<typeof startHub>> | undefined;
afterEach(async () => { await hub?.close(); hub = undefined; });

describe("profile and trace", () => {
  it("profile goes to the server by default and waits as long as it samples", async () => {
    hub = await startHub();
    const server = await connectFakeAgent(hub.port, { token: TOKEN, kind: "server", name: "srv" });
    const seen: unknown[] = [];
    server.onRequest("profile.run", async (p) => {
      seen.push(p);
      await new Promise((r) => setTimeout(r, 50));
      return { thread: "Server thread", samples: 3, owners: [{ owner: "MyShop", kind: "plugin", percent: 60 }] };
    });
    const r = json(await hub.call("profile", { durationMs: 2000, top: 5 }));
    expect(r).toMatchObject({ instance: server.instanceId, thread: "Server thread", owners: [{ owner: "MyShop" }] });
    expect(seen).toEqual([{ durationMs: 2000, top: 5 }]);
  });

  it("profile and trace work on a client too", async () => {
    hub = await startHub();
    const client = await connectFakeAgent(hub.port, { token: TOKEN, kind: "client", name: "Steve" });
    client.onRequest("profile.run", () => ({ thread: "Render thread", fps: { avg: 60, min: 55 } }));
    client.onRequest("trace.run", (p) => ({ filter: p.method, methods: [] }));
    expect(json(await hub.call("profile", { instance: "Steve" }))).toMatchObject({ thread: "Render thread" });
    expect(json(await hub.call("trace", { method: "net.minecraft.client.Camera::update" }))).toMatchObject({ filter: "net.minecraft.client.Camera::update" });
  });

  it("trace needs a method", async () => {
    hub = await startHub();
    const r = await hub.call("trace", {});
    expect(r.isError).toBe(true);
  });

  it("an agent from before profiling explains it must be updated", async () => {
    hub = await startHub();
    await connectFakeAgent(hub.port, { token: TOKEN, kind: "server", name: "old" });
    const r = await hub.call("profile", { durationMs: 1000 });
    expect(r.isError).toBe(true);
    expect(JSON.stringify(r.content)).toMatch(/UNKNOWN_METHOD|update/i);
  });

  it("client_eval goes to a client, never a server, and passes the script on", async () => {
    hub = await startHub();
    await connectFakeAgent(hub.port, { token: TOKEN, kind: "server", name: "srv" });
    const client = await connectFakeAgent(hub.port, { token: TOKEN, kind: "client", name: "Steve" });
    const seen: unknown[] = [];
    client.onRequest("client.eval", (p) => { seen.push(p); return { result: 2, output: "" }; });
    expect(json(await hub.call("client_eval", { code: "1 + 1" }))).toEqual({ result: 2, output: "" });
    expect(seen).toEqual([{ code: "1 + 1", timeoutMs: 5000, reset: false }]);
  });

  it("client_eval is refused on a read-only hub", async () => {
    const { ToolPolicy } = await import("../src/tool-policy.js");
    hub = await startHub({ policy: new ToolPolicy({ readOnly: true }) });
    const names = (await hub.client.listTools()).tools.map((t) => t.name);
    expect(names).toContain("profile");
    expect(names).toContain("trace");
    expect(names).not.toContain("client_eval");
  });
});
