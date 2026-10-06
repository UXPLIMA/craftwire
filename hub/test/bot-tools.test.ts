import { afterEach, describe, expect, it } from "vitest";
import { connectFakeAgent } from "./helpers/fakeAgent.js";
import { json, startHub } from "./helpers/hub.js";

let hub: Awaited<ReturnType<typeof startHub>> | undefined;
afterEach(async () => {
  await hub?.close();
  hub = undefined;
});

async function withServerAgent() {
  hub = await startHub();
  const agent = await connectFakeAgent(hub.port, { token: hub.token, kind: "server", name: "srv" });
  return { hub, agent };
}

describe("bot tools", () => {
  it("bot_spawn forwards count, prefix and location with defaults", async () => {
    const { hub, agent } = await withServerAgent();
    let seen: Record<string, unknown> = {};
    agent.onRequest("bot.spawn", (p) => { seen = p; return { bots: [{ name: "Bot1" }] }; });
    const r = json(await hub.call("bot_spawn", { location: { x: 1, y: 64, z: 2 } }));
    expect(r.bots[0].name).toBe("Bot1");
    expect(seen).toMatchObject({ count: 1, namePrefix: "Bot", location: { x: 1, y: 64, z: 2 } });
  });

  it("bot_spawn rejects invalid names before reaching the server", async () => {
    const { hub } = await withServerAgent();
    const r = await hub.call("bot_spawn", { names: ["no spaces"] });
    expect(r.isError).toBe(true);
  });

  it("bot_remove forwards a name or all", async () => {
    const { hub, agent } = await withServerAgent();
    const calls: Record<string, unknown>[] = [];
    agent.onRequest("bot.remove", (p) => { calls.push(p); return { removed: [] }; });
    await hub.call("bot_remove", { name: "Bot1" });
    await hub.call("bot_remove", { all: true });
    expect(calls).toEqual([{ name: "Bot1", all: false }, { all: true }]);
  });

  it("bot_action waits as long as a move_to may take", async () => {
    const { hub, agent } = await withServerAgent(); // startHub's default request timeout is 2000 ms
    agent.onRequest("bot.action", async (p) => {
      await new Promise((r) => setTimeout(r, 2500));
      return { reached: true, action: p.action };
    });
    const r = json(await hub.call("bot_action", { bot: "Bot1", action: "move_to", x: 1, y: 2, z: 3, timeoutMs: 4000 }));
    expect(r).toEqual({ reached: true, action: "move_to" });
  });

  it("bot_action passes structured errors through", async () => {
    const { hub, agent } = await withServerAgent();
    agent.onRequest("bot.action", () => { throw Object.assign(new Error("No bot named Ghost"), { code: "BOT_NOT_FOUND", hint: "bot_spawn creates bots" }); });
    expect(json(await hub.call("bot_action", { bot: "Ghost", action: "state" }))).toMatchObject({ code: "BOT_NOT_FOUND" });
  });
});
