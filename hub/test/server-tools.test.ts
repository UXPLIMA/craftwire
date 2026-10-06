import { existsSync, mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
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
    ["events", "events", { action: "query", type: "PlayerJoinEvent", since: 5 }],
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

  it("world_render returns the PNG as an image, with what it shows, and can save it", async () => {
    const { hub, agent } = await withServer();
    const png = Buffer.from("89504e470d0a1a0a", "hex").toString("base64");
    let got: Record<string, unknown> | undefined;
    agent.onRequest("world.render", (p) => {
      got = p;
      return { mime: "image/png", data: png, width: 64, height: 64, view: "top", region: { x1: 0, z1: 0, x2: 15, z2: 15 }, scale: 4, origin: { x: 0, y: 0 }, orientation: "North is up" };
    });
    const dir = mkdtempSync(join(tmpdir(), "cw-render-"));
    const res = await hub.call("world_render", { x1: 0, z1: 0, x2: 15, z2: 15, savePath: join(dir, "map.png") });
    expect(got).toMatchObject({ x1: 0, z1: 0, x2: 15, z2: 15, view: "top", grid: 0, players: true, generate: false });
    expect(got).not.toHaveProperty("savePath");
    expect(res.content[0]).toEqual({ type: "image", data: png, mimeType: "image/png" });
    const meta = JSON.parse((res.content[1] as { text: string }).text);
    expect(meta).toMatchObject({ width: 64, scale: 4, orientation: "North is up", savedPath: join(dir, "map.png") });
    expect(meta).not.toHaveProperty("data");
    expect(existsSync(join(dir, "map.png")) && readFileSync(join(dir, "map.png")).toString("hex")).toBe("89504e470d0a1a0a");
  });

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
