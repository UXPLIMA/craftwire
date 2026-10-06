import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { connectFakeAgent } from "./helpers/fakeAgent.js";
import { json, startHub, TOKEN } from "./helpers/hub.js";

let hub: Awaited<ReturnType<typeof startHub>> | undefined;
afterEach(async () => { await hub?.close(); hub = undefined; });

describe("hub tools", () => {
  it("list_instances is empty, then shows a connected client", async () => {
    hub = await startHub();
    expect(json(await hub.call("list_instances"))).toEqual({ instances: [], hint: expect.any(String) });
    await connectFakeAgent(hub.port, { token: TOKEN, name: "Sirac" });
    expect(json(await hub.call("list_instances")).instances).toMatchObject([{ id: "client-1", name: "Sirac" }]);
  });

  it("get_request_status reports unknown ids", async () => {
    hub = await startHub();
    expect(json(await hub.call("get_request_status", { operationId: "nope" }))).toEqual({ status: "unknown" });
  });

  it("wait_for resolves on a matching event that arrives later", async () => {
    hub = await startHub();
    const agent = await connectFakeAgent(hub.port, { token: TOKEN });
    const pending = hub.call("wait_for", { condition: "chat_match", pattern: "build complete", timeoutMs: 3000 });
    setTimeout(() => { agent.emit("chat", { text: "irrelevant", kind: "game" }); agent.emit("chat", { text: "Your build complete!", kind: "game" }); }, 50);
    const res = json(await pending);
    expect(res).toMatchObject({ matched: true, instance: "client-1", event: { type: "chat", data: { text: "Your build complete!" } } });
  });

  it("wait_for screen_open ignores close events", async () => {
    hub = await startHub();
    const agent = await connectFakeAgent(hub.port, { token: TOKEN });
    const pending = hub.call("wait_for", { condition: "screen_open", timeoutMs: 3000 });
    setTimeout(() => { agent.emit("screen", { open: false, title: "", type: "" }); agent.emit("screen", { open: true, title: "Crew", type: "ContainerScreen" }); }, 50);
    expect(json(await pending).event.data.title).toBe("Crew");
  });

  it("wait_for times out with TIMEOUT", async () => {
    hub = await startHub();
    await connectFakeAgent(hub.port, { token: TOKEN });
    const res = await hub.call("wait_for", { condition: "chat_match", pattern: "never", timeoutMs: 100 });
    expect(res.isError).toBe(true);
    expect(json(res).code).toBe("TIMEOUT");
  });

  it("wait_for rejects an invalid regex", async () => {
    hub = await startHub();
    const res = await hub.call("wait_for", { condition: "chat_match", pattern: "(", timeoutMs: 100 });
    expect(json(res).code).toBe("INVALID_PARAMS");
  });

  it("wait_for sends server conditions to the server agent and returns what held", async () => {
    hub = await startHub();
    const server = await connectFakeAgent(hub.port, { token: TOKEN, kind: "server", name: "server" });
    let got: Record<string, unknown> | undefined;
    server.onRequest("wait", (p) => { got = p; return { matched: true, condition: "block", elapsedMs: 420, value: "minecraft:stone" }; });
    const res = json(await hub.call("wait_for", { condition: "block", x: 1, y: 64, z: -2, is: "stone", timeoutMs: 2000 }));
    expect(got).toEqual({ condition: "block", x: 1, y: 64, z: -2, is: "stone", timeoutMs: 2000 });
    expect(res).toEqual({ matched: true, instance: "server-1", condition: "block", elapsedMs: 420, value: "minecraft:stone" });
  });

  it("wait_for reports a server condition's last value on timeout", async () => {
    hub = await startHub();
    const server = await connectFakeAgent(hub.port, { token: TOKEN, kind: "server", name: "server" });
    server.onRequest("wait", () => ({ matched: false, condition: "inventory", elapsedMs: 300, last: 2 }));
    const res = await hub.call("wait_for", { condition: "inventory", player: "Bot1", item: "diamond", count: 3, timeoutMs: 300 });
    expect(res.isError).toBe(true);
    expect(json(res)).toMatchObject({ code: "TIMEOUT", details: { last: 2, elapsedMs: 300 } });
  });

  it("wait_for checks a server condition's required fields before sending it", async () => {
    hub = await startHub();
    await connectFakeAgent(hub.port, { token: TOKEN, kind: "server", name: "server" });
    const res = json(await hub.call("wait_for", { condition: "player_near", player: "Bot1", timeoutMs: 100 }));
    expect(res.code).toBe("INVALID_PARAMS");
    expect(res.message).toMatch(/x, y, z/);
  });

  it("writes an audit line per call", async () => {
    hub = await startHub();
    await hub.call("list_instances");
    const dir = join(hub.home, "logs");
    const [file] = readdirSync(dir);
    const line = JSON.parse(readFileSync(join(dir, file!), "utf8").trim().split("\n")[0]!);
    expect(line).toMatchObject({ tool: "list_instances", ok: true });
  });
});
