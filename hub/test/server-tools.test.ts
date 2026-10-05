import { afterEach, describe, expect, it } from "vitest";
import { connectFakeAgent } from "./helpers/fakeAgent.js";
import { json, startHub, TOKEN } from "./helpers/hub.js";

let hub: Awaited<ReturnType<typeof startHub>> | undefined;
afterEach(async () => { await hub?.close(); hub = undefined; });

async function withServer() {
  hub = await startHub();
  const agent = await connectFakeAgent(hub.port, { token: TOKEN, kind: "server", name: "server" });
  return { hub, agent };
}

describe("server tools", () => {
  const forwards: Array<[string, string, Record<string, unknown>]> = [
    ["server_command", "server.command", { command: "time query gametime" }],
    ["server_eval", "server.eval", { code: "1 + 1" }],
    ["world_query", "world.query", { action: "block", x: 1, y: 2, z: 3 }],
    ["world_edit", "world.edit", { action: "fill", min: { x: 0, y: 0, z: 0 }, max: { x: 1, y: 1, z: 1 }, block: "stone" }],
    ["server_info", "server.info", {}],
    ["plugin_manage", "plugin.manage", { action: "list" }],
  ];

  for (const [tool, method, args] of forwards) {
    it(`${tool} forwards to ${method}`, async () => {
      const { hub, agent } = await withServer();
      agent.onRequest(method, (p) => ({ got: p }));
      const res = await hub.call(tool, args);
      expect(res.isError).toBeFalsy();
      expect(json(res)).toEqual({ got: expect.objectContaining(args) });
    });
  }

  it("applies parameter defaults before forwarding", async () => {
    const { hub, agent } = await withServer();
    agent.onRequest("server.command", (p) => p);
    agent.onRequest("server.eval", (p) => p);
    expect(json(await hub.call("server_command", { command: "list" }))).toEqual({ command: "list", collectMs: 250 });
    expect(json(await hub.call("server_eval", { code: "1" }))).toEqual({ code: "1", timeoutMs: 5000, reset: false });
  });

  it("server_eval waits for the script's own timeout, not the default request timeout", async () => {
    const { hub, agent } = await withServer();
    agent.onRequest("server.eval", async () => {
      await new Promise((r) => setTimeout(r, 2500));   // longer than the test hub's 2000 ms request timeout
      return { result: 1, output: "" };
    });
    const res = await hub.call("server_eval", { code: "slow()", timeoutMs: 3000 });
    expect(res.isError).toBeFalsy();
  }, 10_000);

  it("server tools never route to a client", async () => {
    hub = await startHub();
    await connectFakeAgent(hub.port, { token: TOKEN, kind: "client" });
    expect(json(await hub.call("server_info"))).toMatchObject({ code: "NO_INSTANCE", hint: expect.stringContaining("Craftwire plugin") });
  });

  it("world_edit rejects schematic names that could escape the folder", async () => {
    const { hub } = await withServer();
    const res = await hub.call("world_edit", { action: "save_schematic", name: "../evil", min: { x: 0, y: 0, z: 0 }, max: { x: 0, y: 0, z: 0 } });
    expect(res.isError).toBe(true);
  });
});
