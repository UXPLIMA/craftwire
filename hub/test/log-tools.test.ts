import { afterEach, describe, expect, it, vi } from "vitest";
import { connectFakeAgent } from "./helpers/fakeAgent.js";
import { json, startHub, TOKEN } from "./helpers/hub.js";

let hub: Awaited<ReturnType<typeof startHub>> | undefined;
afterEach(async () => { await hub?.close(); hub = undefined; });

const messages = (r: { lines: Array<{ message: string }> }) => r.lines.map((l) => l.message);

describe("logs", () => {
  it("folds stack lines into the line above and filters by level, text and limit", async () => {
    hub = await startHub();
    const agent = await connectFakeAgent(hub.port, { token: TOKEN, kind: "server", name: "server" });
    agent.emit("log", { level: "INFO", logger: "Minecraft", message: "Done (3.1s)!" });
    agent.emit("log", { level: "WARN", logger: "", message: "java.lang.IllegalStateException: boom" });
    agent.emit("log", { level: "WARN", logger: "", message: "\tat com.example.Foo.bar(Foo.java:10)" });
    agent.emit("log", { level: "WARN", logger: "", message: "Caused by: java.io.IOException: disk" });
    agent.emit("log", { level: "ERROR", logger: "Shop", message: "Could not save", thrown: "java.io.IOException: disk\n\tat x" });
    const h = hub;
    await vi.waitFor(() => expect(h.agents.events(agent.instanceId)).toHaveLength(5));

    const all = json(await hub.call("logs"));
    expect(all.instance).toBe(agent.instanceId);
    expect(all.lines).toHaveLength(3);
    expect(all.lines[1]).toMatchObject({
      level: "WARN",
      message: "java.lang.IllegalStateException: boom",
      stack: ["at com.example.Foo.bar(Foo.java:10)", "Caused by: java.io.IOException: disk"],
    });
    expect(messages(json(await hub.call("logs", { level: "ERROR" })))).toEqual(["Could not save"]);
    expect(messages(json(await hub.call("logs", { contains: "FOO.JAVA" })))).toEqual(["java.lang.IllegalStateException: boom"]);
    expect(messages(json(await hub.call("logs", { limit: 1 })))).toEqual(["Could not save"]);
  });

  it("defaults to the server when a client is connected too", async () => {
    hub = await startHub();
    const client = await connectFakeAgent(hub.port, { token: TOKEN, kind: "client" });
    const server = await connectFakeAgent(hub.port, { token: TOKEN, kind: "server", name: "server" });
    client.emit("log", { level: "INFO", logger: "c", message: "client line" });
    server.emit("log", { level: "INFO", logger: "s", message: "server line" });
    const h = hub;
    await vi.waitFor(() => expect(h.agents.events(server.instanceId)).toHaveLength(1));
    await vi.waitFor(() => expect(h.agents.events(client.instanceId)).toHaveLength(1));
    const res = json(await hub.call("logs"));
    expect(res.instance).toBe(server.instanceId);
    expect(messages(res)).toEqual(["server line"]);
    expect(messages(json(await hub.call("logs", { instance: client.instanceId })))).toEqual(["client line"]);
  });

  it("asks for an instance when several clients and no server are connected", async () => {
    hub = await startHub();
    await connectFakeAgent(hub.port, { token: TOKEN, kind: "client", name: "A" });
    await connectFakeAgent(hub.port, { token: TOKEN, kind: "client", name: "B" });
    expect(json(await hub.call("logs"))).toMatchObject({ code: "AMBIGUOUS_INSTANCE" });
  });

  it("reports NO_INSTANCE when nothing is connected", async () => {
    hub = await startHub();
    expect(json(await hub.call("logs"))).toMatchObject({ code: "NO_INSTANCE" });
  });

  it("wait_for log_match matches server log messages", async () => {
    hub = await startHub();
    const agent = await connectFakeAgent(hub.port, { token: TOKEN, kind: "server" });
    const pending = hub.call("wait_for", { condition: "log_match", pattern: "^Done [(]", timeoutMs: 3000 });
    setTimeout(() => agent.emit("log", { level: "INFO", logger: "Minecraft", message: "Done (4.5s)! For help, type \"help\"" }), 50);
    expect(json(await pending)).toMatchObject({ matched: true, event: { type: "log" } });
  });
});
