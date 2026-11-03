/** The extras: off in a new session, turned on by more_tools or --tools, and by scenarios that use them. */
import { ToolListChangedNotificationSchema } from "@modelcontextprotocol/sdk/types.js";
import { afterEach, describe, expect, it } from "vitest";
import { inProcessCaller } from "../src/scenario/caller.js";
import { createCraftwireServer } from "../src/server.js";
import { parseHubArgs, TOOL_GROUPS } from "../src/tool-policy.js";
import { connectFakeAgent } from "./helpers/fakeAgent.js";
import { json, startHub } from "./helpers/hub.js";

let hub: Awaited<ReturnType<typeof startHub>> | undefined;
afterEach(async () => {
  await hub?.close();
  hub = undefined;
});

const names = async () => (await hub!.client.listTools()).tools.map((t) => t.name);
const EXTRAS = [...TOOL_GROUPS.extras!];

describe("more_tools", () => {
  it("the extras are off at first; turning one on tells the client and leaves the rest off", async () => {
    hub = await startHub();
    let changed = 0;
    hub.client.setNotificationHandler(ToolListChangedNotificationSchema, async () => { changed++; });
    const before = await names();
    for (const t of EXTRAS) expect(before).not.toContain(t);
    expect((await hub.call("load_test", { ramp: [1] })).isError).toBe(true);

    expect(json(await hub.call("more_tools", { enable: ["load_test"] }))).toMatchObject({ enabled: ["load_test"] });
    await expect.poll(() => changed).toBeGreaterThan(0);
    const after = await names();
    expect(after).toContain("load_test");
    expect(after).not.toContain("dupe_hunt");
    expect(json(await hub.call("more_tools", { enable: ["load_test"] }))).toMatchObject({ enabled: [], alreadyOn: ["load_test"] });
  });

  it("all turns every extra on, and a name that is not one is refused", async () => {
    hub = await startHub();
    const r = await hub.call("more_tools", { enable: ["server_command"] });
    expect(r.isError).toBe(true);
    expect(JSON.stringify(r.content)).toContain("Not an extra tool: server_command");
    await hub.call("more_tools", { enable: "all" });
    const all = await names();
    for (const t of EXTRAS) expect(all).toContain(t);
  });

  it("a hub started with --tools naming extras has them on, and no more_tools", async () => {
    hub = await startHub({ policy: parseHubArgs(["--tools", "hub,bots,extras"]).policy! });
    const listed = await names();
    for (const t of EXTRAS) expect(listed).toContain(t);
    expect(listed).not.toContain("more_tools");
  });

  it("--tools without extras leaves them out altogether", async () => {
    hub = await startHub({ policy: parseHubArgs(["--tools", "hub,bots"]).policy! });
    const listed = await names();
    expect(listed).not.toContain("more_tools");
    expect(listed).not.toContain("bot_behavior");
  });

  it("a scenario step turns on the extra it calls", async () => {
    hub = await startHub();
    const agent = await connectFakeAgent(hub.port, { token: hub.token, kind: "server", name: "srv" });
    agent.onRequest("bot.behavior", () => ({ bots: 1, behaviors: [{ bot: "Bot1", type: "wander" }] }));
    const { call, close } = await inProcessCaller(createCraftwireServer(hub.ctx));
    try {
      const r = await call("bot_behavior", { bot: "Bot1", action: "status" });
      expect(r).toEqual({ ok: true, data: { bots: 1, behaviors: [{ bot: "Bot1", type: "wander" }] } });
    } finally {
      await close();
    }
  });
});

describe("unlisted fields", () => {
  it("are accepted and passed on although the tool does not list them", async () => {
    hub = await startHub();
    const agent = await connectFakeAgent(hub.port, { token: hub.token, kind: "server", name: "srv" });
    let seen: Record<string, unknown> = {};
    agent.onRequest("bot.action", (p) => { seen = p; return { ok: true }; });
    const schema = (await hub.client.listTools()).tools.find((t) => t.name === "bot_action")!.inputSchema;
    expect(Object.keys(schema.properties ?? {})).not.toContain("lines");
    await hub.call("bot_action", { bot: "Bot1", action: "sign_edit", block: { x: 1, y: 2, z: 3 }, lines: ["a", "b"], side: "back" });
    expect(seen).toMatchObject({ bot: "Bot1", action: "sign_edit", lines: ["a", "b"], side: "back" });
  });

  it("are still checked, and a wrong one names the field", async () => {
    hub = await startHub();
    const r = await hub.call("bot_action", { bot: "Bot1", action: "sign_edit", side: "top" });
    expect(r.isError).toBe(true);
    expect(JSON.stringify(r.content)).toContain("side");
    const tooMany = await hub.call("bot_spawn", { count: 1, perTick: 1000 });
    expect(JSON.stringify(tooMany.content)).toContain("perTick");
  });
});
