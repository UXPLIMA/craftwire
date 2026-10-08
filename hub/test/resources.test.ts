import { ResourceUpdatedNotificationSchema } from "@modelcontextprotocol/sdk/types.js";
import { afterEach, describe, expect, it, vi } from "vitest";
import { connectFakeAgent } from "./helpers/fakeAgent.js";
import { startHub, TOKEN } from "./helpers/hub.js";

let hub: Awaited<ReturnType<typeof startHub>> | undefined;
afterEach(async () => { await hub?.close(); hub = undefined; });

const text = (r: { contents: Array<{ text?: unknown }> }) => String(r.contents[0]!.text);

describe("resources", () => {
  it("lists the instances, and a log and screenshot per game, chat per client", async () => {
    hub = await startHub();
    await connectFakeAgent(hub.port, { token: TOKEN, kind: "server", name: "srv" });
    await connectFakeAgent(hub.port, { token: TOKEN, kind: "client", name: "Steve" });
    const uris = (await hub.client.listResources()).resources.map((r) => r.uri);
    expect(uris).toContain("craftwire://instances");
    expect(uris).toContain("craftwire://exceptions");
    expect(uris).toContain("craftwire://instances/srv/log");
    expect(uris).toContain("craftwire://instances/Steve/log");
    expect(uris).toContain("craftwire://instances/Steve/chat");
    expect(uris).toContain("craftwire://instances/Steve/screenshot");
    expect(uris).not.toContain("craftwire://instances/srv/chat");
    expect(uris).toContain("craftwire://docs/scenarios");
    expect(uris).toContain("craftwire://docs/video");

    const instances = JSON.parse(text(await hub.client.readResource({ uri: "craftwire://instances" })));
    expect(instances.map((i: { name: string }) => i.name).sort()).toEqual(["Steve", "srv"]);
  });

  it("reads a game's log with stack traces folded, by name or id", async () => {
    hub = await startHub();
    const agent = await connectFakeAgent(hub.port, { token: TOKEN, kind: "server", name: "srv" });
    agent.emit("log", { level: "INFO", logger: "Minecraft", message: "Done (3.1s)!" });
    agent.emit("log", { level: "ERROR", logger: "Shop", message: "Could not save", thrown: "java.io.IOException: disk\n\tat x" });
    const h = hub;
    await vi.waitFor(() => expect(h.agents.events(agent.instanceId)).toHaveLength(2));
    const log = text(await hub.client.readResource({ uri: "craftwire://instances/srv/log" }));
    expect(log).toMatch(/INFO \[Minecraft\] Done \(3\.1s\)!/);
    expect(log).toMatch(/ERROR \[Shop\] Could not save\n {2}java\.io\.IOException: disk/);
    expect(text(await hub.client.readResource({ uri: `craftwire://instances/${agent.instanceId}/log` }))).toBe(log);
  });

  it("encodes instance names that are not URI-safe, and reads them back", async () => {
    hub = await startHub();
    const agent = await connectFakeAgent(hub.port, { token: TOKEN, kind: "server", name: "Test Server #2" });
    agent.emit("log", { level: "INFO", logger: "Minecraft", message: "Done (3.1s)!" });
    const h = hub;
    await vi.waitFor(() => expect(h.agents.events(agent.instanceId)).toHaveLength(1));
    const uri = (await hub.client.listResources()).resources.map((r) => r.uri).find((u) => u.endsWith("/log"));
    expect(uri).toBe("craftwire://instances/Test%20Server%20%232/log");
    expect(text(await hub.client.readResource({ uri: uri! }))).toMatch(/Done \(3\.1s\)!/);
  });

  it("reads a client's chat and takes a screenshot when read", async () => {
    hub = await startHub();
    const client = await connectFakeAgent(hub.port, { token: TOKEN, kind: "client", name: "Steve" });
    client.emit("chat", { text: "<Alex> hi", kind: "chat", sender: "Alex" });
    client.onRequest("screenshot", () => ({ data: Buffer.from("png").toString("base64"), mime: "image/png", width: 1, height: 1 }));
    const h = hub;
    await vi.waitFor(() => expect(h.agents.events(client.instanceId)).toHaveLength(1));
    expect(text(await hub.client.readResource({ uri: "craftwire://instances/Steve/chat" }))).toMatch(/<Alex> hi/);
    const shot = (await hub.client.readResource({ uri: "craftwire://instances/Steve/screenshot" })).contents[0] as { blob: string; mimeType: string };
    expect(shot.mimeType).toBe("image/png");
    expect(Buffer.from(shot.blob, "base64").toString()).toBe("png");
  });

  it("serves the docs shipped with the hub", async () => {
    hub = await startHub();
    const doc = await hub.client.readResource({ uri: "craftwire://docs/scenarios" });
    expect(text(doc)).toMatch(/^# Scenarios/);
    await expect(hub.client.readResource({ uri: "craftwire://docs/nope" })).rejects.toThrow();
  });

  it("notifies subscribers when a log grows and when games come and go", async () => {
    hub = await startHub();
    const agent = await connectFakeAgent(hub.port, { token: TOKEN, kind: "server", name: "srv" });
    const updated: string[] = [];
    hub.client.setNotificationHandler(ResourceUpdatedNotificationSchema, (n) => { updated.push(n.params.uri); });
    await hub.client.subscribeResource({ uri: "craftwire://instances/srv/log" });
    await hub.client.subscribeResource({ uri: "craftwire://instances" });
    agent.emit("log", { level: "INFO", logger: "x", message: "one" });
    agent.emit("log", { level: "INFO", logger: "x", message: "two" });
    await vi.waitFor(() => expect(updated).toContain("craftwire://instances/srv/log"));
    await connectFakeAgent(hub.port, { token: TOKEN, kind: "client", name: "Steve" });
    await vi.waitFor(() => expect(updated).toContain("craftwire://instances"));
    await hub.client.unsubscribeResource({ uri: "craftwire://instances/srv/log" });
    const before = updated.filter((u) => u.endsWith("/log")).length;
    agent.emit("log", { level: "INFO", logger: "x", message: "three" });
    await new Promise((r) => setTimeout(r, 1300));
    expect(updated.filter((u) => u.endsWith("/log")).length).toBe(before);
  });
});

describe("prompts", () => {
  it("offers the workflows, filled with what is connected", async () => {
    hub = await startHub();
    const srv = await connectFakeAgent(hub.port, { token: TOKEN, kind: "server", name: "srv" });
    srv.onRequest("plugin.manage", () => ({ plugins: [{ name: "MyShop", enabled: true }, { name: "Craftwire", enabled: true }] }));
    const names = (await hub.client.listPrompts()).prompts.map((p) => p.name).sort();
    expect(names).toEqual(["debug_lag", "setup", "test_plugin", "write_scenario"]);

    const test = await hub.client.getPrompt({ name: "test_plugin", arguments: { plugin: "MyShop" } });
    const body = (test.messages[0]!.content as { text: string }).text;
    expect(body).toContain("MyShop");
    expect(body).toContain("scenario_run");
    expect(body).toMatch(/srv/);

    const lag = await hub.client.getPrompt({ name: "debug_lag", arguments: {} });
    expect((lag.messages[0]!.content as { text: string }).text).toContain("profile");

    const setup = await hub.client.getPrompt({ name: "setup", arguments: {} });
    expect((setup.messages[0]!.content as { text: string }).text).toMatch(/No Minecraft client is connected/);
  });
});
