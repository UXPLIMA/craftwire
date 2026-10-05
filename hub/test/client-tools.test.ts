import { resolve } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { connectFakeAgent } from "./helpers/fakeAgent.js";
import { json, startHub, TOKEN } from "./helpers/hub.js";

let hub: Awaited<ReturnType<typeof startHub>> | undefined;
afterEach(async () => { await hub?.close(); hub = undefined; });

async function withAgent() {
  hub = await startHub();
  const agent = await connectFakeAgent(hub.port, { token: TOKEN, name: "Sirac" });
  return { hub, agent };
}

describe("client tools", () => {
  const forwards: Array<[string, string, Record<string, unknown>]> = [
    ["player_state", "player.state", {}],
    ["hud_read", "hud.read", {}],
    ["gui_read", "gui.read", {}],
    ["gui_action", "gui.action", { action: "click", slot: 3 }],
    ["input", "input", { keys: ["jump"], mode: "press" }],
    ["camera", "camera", { action: "set", x: 1, y: 2, z: 3, yaw: 90, pitch: 10 }],
    ["client_settings", "client.settings", { guiScale: 3 }],
  ];

  for (const [tool, method, args] of forwards) {
    it(`${tool} forwards to ${method}`, async () => {
      const { hub, agent } = await withAgent();
      agent.onRequest(method, (p) => ({ got: p }));
      const res = await hub.call(tool, args);
      expect(res.isError).toBeFalsy();
      expect(json(res)).toEqual({ got: expect.objectContaining(args) });
    });
  }

  it("returns NO_INSTANCE when no client is connected", async () => {
    hub = await startHub();
    const res = await hub.call("player_state");
    expect(res.isError).toBe(true);
    expect(json(res)).toMatchObject({ code: "NO_INSTANCE", hint: expect.stringContaining("Craftwire Agent") });
  });

  it("surfaces agent errors with their hint", async () => {
    const { hub, agent } = await withAgent();
    agent.onRequest("gui.read", () => { throw Object.assign(new Error("No screen is open"), { code: "NO_SCREEN_OPEN", hint: "open a menu" }); });
    expect(json(await hub.call("gui_read"))).toEqual({ code: "NO_SCREEN_OPEN", message: "No screen is open", hint: "open a menu" });
  });

  it("deduplicates retries that share an operationId", async () => {
    const { hub, agent } = await withAgent();
    let calls = 0;
    agent.onRequest("gui.action", (p) => ({ calls: ++calls, operationId: p.operationId }));
    await hub.call("gui_action", { action: "click", slot: 1, operationId: "op-1" });
    const second = json(await hub.call("gui_action", { action: "click", slot: 1, operationId: "op-1" }));
    expect(second).toEqual({ calls: 1, operationId: "op-1" });
  });

  it("screenshot returns an image item and absolutises savePath", async () => {
    const { hub, agent } = await withAgent();
    let seen: Record<string, unknown> = {};
    agent.onRequest("screenshot", (p) => {
      seen = p;
      return { mime: "image/png", data: "iVBORw0KGgo=", width: 2, height: 1, fullWidth: 4, fullHeight: 2, savedPath: p.savePath };
    });
    const res = await hub.call("screenshot", { hud: false, savePath: "shots/a.png" });
    expect(res.content[0]).toEqual({ type: "image", data: "iVBORw0KGgo=", mimeType: "image/png" });
    expect(JSON.parse((res.content[1] as { text: string }).text)).toMatchObject({ width: 2, fullWidth: 4, savedPath: resolve("shots/a.png") });
    expect(seen).toMatchObject({ hud: false, maxSize: 1600, savePath: resolve("shots/a.png") });
  });

  it("chat send and command forward to chat.send", async () => {
    const { hub, agent } = await withAgent();
    const got: unknown[] = [];
    agent.onRequest("chat.send", (p) => { got.push(p); return { sent: true }; });
    await hub.call("chat", { action: "send", text: "hello" });
    await hub.call("chat", { action: "command", text: "/builders crew" });
    expect(got).toEqual([{ text: "hello", command: false }, { text: "/builders crew", command: true }]);
  });

  it("chat read filters buffered chat and actionbar events", async () => {
    const { hub, agent } = await withAgent();
    agent.emit("chat", { text: "Welcome!", kind: "game" });
    agent.emit("hud", { element: "actionbar", text: "Preview: house" });
    agent.emit("screen", { open: true, title: "x", type: "y" });
    await new Promise((r) => setTimeout(r, 50));
    const all = json(await hub.call("chat", { action: "read" }));
    expect(all.messages.map((m: { text: string }) => m.text)).toEqual(["Welcome!", "Preview: house"]);
    const filtered = json(await hub.call("chat", { action: "read", contains: "preview" }));
    expect(filtered.messages).toHaveLength(1);
  });

  it("chat send without text is INVALID_PARAMS", async () => {
    const { hub } = await withAgent();
    expect(json(await hub.call("chat", { action: "send" })).code).toBe("INVALID_PARAMS");
  });
});
