import { EventEmitter } from "node:events";
import { describe, expect, it } from "vitest";
import { ExceptionTracker, parseThrown } from "../src/exceptions.js";

const NPE = [
  "java.lang.IllegalStateException: Shop closed",
  "\tat com.example.shop.ShopMenu.open(ShopMenu.java:42)",
  "\tat com.example.shop.ShopCommand.onCommand(ShopCommand.java:17)",
  "\tat org.bukkit.command.PluginCommand.execute(PluginCommand.java:45)",
  "Caused by: java.lang.NullPointerException: Cannot invoke \"Economy.balance()\" because \"eco\" is null",
  "\tat com.example.shop.Prices.of(Prices.java:9)",
  "\tat com.example.shop.ShopMenu.open(ShopMenu.java:40)",
  "\t... 2 more",
].join("\n");

describe("parseThrown", () => {
  it("reads the chain and takes the deepest cause as the root", () => {
    const p = parseThrown(NPE)!;
    expect(p.type).toBe("java.lang.IllegalStateException");
    expect(p.root.type).toBe("java.lang.NullPointerException");
    expect(p.root.message).toContain("because \"eco\" is null");
    expect(p.root.frames[0]).toBe("com.example.shop.Prices.of(Prices.java:9)");
  });

  it("is undefined for text that is not a stack trace", () => {
    expect(parseThrown("Done (3.2s)! For help, type \"help\"")).toBeUndefined();
  });
});

function harness() {
  const agents = new EventEmitter();
  const names: Record<string, string> = { "server-1": "Paper", "client-1": "Steve" };
  const t = new ExceptionTracker(agents as never, (id) => names[id]);
  let now = 1000;
  const log = (id: string, data: Record<string, unknown>, time = now++) => agents.emit("event", id, { type: "log", time, data });
  return { t, log, agents };
}

describe("ExceptionTracker", () => {
  it("groups repeats of one bug, keeps counts, times, origin and plugin", () => {
    const { t, log } = harness();
    log("server-1", { level: "ERROR", logger: "Shop", message: "Unhandled exception executing command 'shop'", thrown: NPE }, 5000);
    log("server-1", { level: "ERROR", logger: "Shop", message: "Unhandled exception executing command 'shop'", thrown: NPE.replace("ShopMenu.java:40", "ShopMenu.java:41") }, 6000);
    const [g, ...rest] = t.list();
    expect(rest).toHaveLength(0);
    expect(g).toMatchObject({
      type: "java.lang.NullPointerException", count: 2, firstSeen: 5000, lastSeen: 6000,
      origin: "com.example.shop.Prices.of(Prices.java:9)", plugin: "Shop", instances: ["Paper"],
    });
    expect(g!.id).toMatch(/^[0-9a-f]{8}$/);
    expect(t.get(g!.id)!.stack).toContain("Caused by: java.lang.NullPointerException");
  });

  it("names the plugin from Paper's event-dispatch message", () => {
    const { t, log } = harness();
    log("server-1", { level: "ERROR", logger: "Minecraft", message: "Could not pass event PlayerJoinEvent to Greeter v1.2.0", thrown: NPE });
    expect(t.list()[0]!.plugin).toBe("Greeter");
  });

  it("assembles a trace printed line by line to stderr", () => {
    const { t, log } = harness();
    for (const line of NPE.split("\n")) log("client-1", { level: "ERROR", logger: "STDERR", message: line });
    log("client-1", { level: "INFO", logger: "Minecraft", message: "Saving chunks" });
    expect(t.list()).toMatchObject([{ type: "java.lang.NullPointerException", count: 1, instances: ["Steve"] }]);
  });

  it("does not count lines twice when an agent replays its backlog after reconnecting", () => {
    const { t, log, agents } = harness();
    agents.emit("connected", { id: "server-1", name: "Paper" });
    log("server-1", { level: "ERROR", logger: "Shop", message: "boom", thrown: NPE }, 7000);
    agents.emit("connected", { id: "server-2", name: "Paper" });
    log("server-2", { level: "ERROR", logger: "Shop", message: "boom", thrown: NPE }, 7000); // replayed
    log("server-2", { level: "ERROR", logger: "Shop", message: "boom", thrown: NPE }, 8000); // new
    expect(t.list()[0]!.count).toBe(2);
  });

  it("counts identical errors thrown in the same millisecond (a loop) separately", () => {
    const { t, log } = harness();
    log("server-1", { level: "ERROR", logger: "Shop", message: "boom", thrown: NPE }, 9000);
    log("server-1", { level: "ERROR", logger: "Shop", message: "boom", thrown: NPE }, 9000);
    expect(t.list()[0]!.count).toBe(2);
  });

  it("filters by time and instance, newest first", () => {
    const { t, log } = harness();
    log("server-1", { level: "ERROR", logger: "Shop", message: "a", thrown: NPE }, 100);
    log("client-1", { level: "ERROR", logger: "Mod", message: "b", thrown: "java.lang.RuntimeException: x\n\tat com.example.mod.Thing.tick(Thing.java:3)" }, 200);
    expect(t.list().map((g) => g.type)).toEqual(["java.lang.RuntimeException", "java.lang.NullPointerException"]);
    expect(t.list({ since: 150 }).map((g) => g.type)).toEqual(["java.lang.RuntimeException"]);
    expect(t.list({ instance: "Paper" }).map((g) => g.type)).toEqual(["java.lang.NullPointerException"]);
  });

  it("counts traces logged as warnings (Bukkit's scheduler does), not INFO traces or lines without one", () => {
    const { t, log } = harness();
    log("server-1", { level: "WARN", logger: "Shop", message: "Plugin Shop v1 generated an exception while executing task 7", thrown: NPE });
    log("server-1", { level: "INFO", logger: "Shop", message: "debug", thrown: "java.lang.Exception: just showing\n\tat com.example.A.b(A.java:1)" });
    log("server-1", { level: "ERROR", logger: "Shop", message: "plain error" });
    expect(t.list()).toMatchObject([{ type: "java.lang.NullPointerException", count: 1, plugin: "Shop" }]);
  });
});

describe("exceptions tool", () => {
  it("lists the bugs agents logged and returns one with its full stack", async () => {
    const { startHub, json, TOKEN } = await import("./helpers/hub.js");
    const { connectFakeAgent } = await import("./helpers/fakeAgent.js");
    const { waitUntil } = await import("../src/dev/server-manager.js");
    const hub = await startHub();
    try {
      const agent = await connectFakeAgent(hub.port, { token: TOKEN, kind: "server", name: "Paper" });
      agent.emit("log", { level: "ERROR", logger: "Shop", message: "Unhandled exception executing command 'shop'", thrown: NPE });
      agent.emit("log", { level: "ERROR", logger: "Shop", message: "Unhandled exception executing command 'shop'", thrown: NPE });
      await waitUntil(() => hub.agents.events(agent.instanceId).length >= 2, 2000, 10);
      const list = json(await hub.call("exceptions", {}));
      expect(list.exceptions).toMatchObject([{ type: "java.lang.NullPointerException", count: 2, plugin: "Shop", instances: ["Paper"] }]);
      const one = json(await hub.call("exceptions", { id: list.exceptions[0].id }));
      expect(one.stack).toContain("at com.example.shop.Prices.of(Prices.java:9)");
      const missing = await hub.call("exceptions", { id: "ffffffff" });
      expect(missing.isError).toBe(true);
      await agent.close();
    } finally {
      await hub.close();
    }
  });
});
