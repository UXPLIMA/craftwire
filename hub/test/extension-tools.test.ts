import { ToolListChangedNotificationSchema } from "@modelcontextprotocol/sdk/types.js";
import { afterEach, describe, expect, it } from "vitest";
import { connectFakeAgent } from "./helpers/fakeAgent.js";
import { json, startHub, TOKEN } from "./helpers/hub.js";

let hub: Awaited<ReturnType<typeof startHub>> | undefined;
afterEach(async () => { await hub?.close(); hub = undefined; });

const greet = {
  name: "myshop_greet", namespace: "myshop", description: "Greets a player.",
  inputSchema: { type: "object", properties: { name: { type: "string", description: "Who" }, times: { type: "integer", minimum: 1 } }, required: ["name"] },
};

const until = async (cond: () => Promise<boolean> | boolean, ms = 2000) => {
  const end = Date.now() + ms;
  while (!(await cond())) {
    if (Date.now() > end) throw new Error("condition not met");
    await new Promise((r) => setTimeout(r, 20));
  }
};
const toolNames = async () => (await hub!.client.listTools()).tools.map((t) => t.name);

describe("extension tools", () => {
  it("appear when an agent announces them, with their schema plus instance, and tell the client the list changed", async () => {
    hub = await startHub();
    let changed = 0;
    hub.client.setNotificationHandler(ToolListChangedNotificationSchema, async () => { changed++; });
    const agent = await connectFakeAgent(hub.port, { token: TOKEN, kind: "server", name: "survival" });
    agent.emit("tools", { tools: [greet] });
    await until(async () => (await toolNames()).includes("myshop_greet"));
    const tool = (await hub.client.listTools()).tools.find((t) => t.name === "myshop_greet")!;
    expect(tool.description).toContain("Greets a player.");
    expect(tool.description).toContain("myshop");
    expect(tool.inputSchema.properties).toMatchObject({ name: { type: "string", description: "Who" }, times: { type: "integer" }, instance: { type: "string" } });
    expect(tool.inputSchema.required).toEqual(["name"]);
    await until(() => changed > 0);
  });

  it("forwards calls to the agent that has the tool and returns its result or error", async () => {
    hub = await startHub();
    const agent = await connectFakeAgent(hub.port, { token: TOKEN, kind: "server", name: "survival" });
    let got: Record<string, unknown> | undefined;
    agent.onRequest("ext.call", (p) => {
      got = p;
      if ((p.args as { name: string }).name === "nobody") throw Object.assign(new Error("No such player"), { code: "PLAYER_OFFLINE", hint: "Pick an online player." });
      return { greeting: "Hello Steve" };
    });
    agent.emit("tools", { tools: [greet] });
    await until(async () => (await toolNames()).includes("myshop_greet"));
    expect(json(await hub.call("myshop_greet", { name: "Steve" }))).toEqual({ greeting: "Hello Steve" });
    expect(got).toEqual({ tool: "myshop_greet", args: { name: "Steve" } });
    const err = json(await hub.call("myshop_greet", { name: "nobody" }));
    expect(err.code).toBe("PLAYER_OFFLINE");
    expect((await hub.call("myshop_greet", {})).isError).toBe(true);
  });

  it("disappear when the agent drops them or disconnects", async () => {
    hub = await startHub();
    const agent = await connectFakeAgent(hub.port, { token: TOKEN, kind: "server", name: "survival" });
    agent.emit("tools", { tools: [greet] });
    await until(async () => (await toolNames()).includes("myshop_greet"));
    agent.emit("tools", { tools: [] });
    await until(async () => !(await toolNames()).includes("myshop_greet"));
    agent.emit("tools", { tools: [greet] });
    await until(async () => (await toolNames()).includes("myshop_greet"));
    agent.close();
    await until(async () => !(await toolNames()).includes("myshop_greet"));
  });

  it("asks which instance when several have the tool, and list_instances shows each instance's tools", async () => {
    hub = await startHub();
    const a = await connectFakeAgent(hub.port, { token: TOKEN, kind: "server", name: "lobby" });
    const b = await connectFakeAgent(hub.port, { token: TOKEN, kind: "server", name: "survival" });
    a.onRequest("ext.call", () => ({ from: "lobby" }));
    b.onRequest("ext.call", () => ({ from: "survival" }));
    a.emit("tools", { tools: [greet] });
    b.emit("tools", { tools: [greet] });
    await until(() => hub!.ctx.extensions.instancesWith("myshop_greet").length === 2);
    await until(async () => (await toolNames()).includes("myshop_greet"));
    expect(json(await hub.call("myshop_greet", { name: "x" })).code).toBe("AMBIGUOUS_INSTANCE");
    expect(json(await hub.call("myshop_greet", { name: "x", instance: "survival" }))).toEqual({ from: "survival" });
    const instances = json(await hub.call("list_instances")).instances;
    expect(instances.map((i: { tools?: string[] }) => i.tools)).toEqual([["myshop_greet"], ["myshop_greet"]]);
  });

  it("never replaces a built-in tool", async () => {
    hub = await startHub();
    const agent = await connectFakeAgent(hub.port, { token: TOKEN, kind: "server", name: "survival" });
    agent.emit("tools", { tools: [{ ...greet, name: "bot_spawn", namespace: "bot" }, greet] });
    await until(async () => (await toolNames()).includes("myshop_greet"));
    const spawn = (await hub.client.listTools()).tools.find((t) => t.name === "bot_spawn")!;
    expect(spawn.description).not.toContain("Greets");
  });

  it("a schema the hub cannot convert still registers, taking any arguments", async () => {
    hub = await startHub();
    const agent = await connectFakeAgent(hub.port, { token: TOKEN, kind: "server", name: "survival" });
    agent.onRequest("ext.call", (p) => p.args);
    agent.emit("tools", { tools: [{ ...greet, name: "myshop_odd", inputSchema: { type: "object", properties: { a: { $ref: "#/definitions/missing" } } } }] });
    await until(async () => (await toolNames()).includes("myshop_odd"));
    expect(json(await hub.call("myshop_odd", { a: 1, b: "two" }))).toEqual({ a: 1, b: "two" });
  });
});
