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

  it("writes an audit line per call", async () => {
    hub = await startHub();
    await hub.call("list_instances");
    const dir = join(hub.home, "logs");
    const [file] = readdirSync(dir);
    const line = JSON.parse(readFileSync(join(dir, file!), "utf8").trim().split("\n")[0]!);
    expect(line).toMatchObject({ tool: "list_instances", ok: true });
  });
});
