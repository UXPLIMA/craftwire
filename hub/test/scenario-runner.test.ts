import { describe, expect, it } from "vitest";
import { runScenario, type ToolCaller } from "../src/scenario/runner.js";
import { parseScenario, ScenarioError } from "../src/scenario/scenario.js";

/** A fake set of tools: each handler gets the args; calls are recorded. */
function tools(handlers: Record<string, (args: Record<string, unknown>) => unknown>) {
  const calls: Array<[string, Record<string, unknown>]> = [];
  const call: ToolCaller = async (tool, args) => {
    calls.push([tool, args]);
    const h = handlers[tool];
    if (!h) return { ok: true, data: tool === "exceptions" ? { total: 0, exceptions: [] } : {} };
    try {
      return { ok: true, data: h(args) };
    } catch (e) {
      return { ok: false, data: e };
    }
  };
  return { call, calls };
}

/** A clock that advances only when the runner sleeps. */
function clock() {
  let t = 1_000_000;
  return { now: () => t, sleep: async (ms: number) => { t += ms; } };
}

describe("parseScenario", () => {
  it("turns shorthands into tool calls", () => {
    const s = parseScenario({
      name: "shop",
      steps: [
        { bot: "Buyer", command: "/shop" },
        { bot: "Buyer", chat: "hi" },
        { bot: "Buyer", action: "gui_click", slot: 3 },
        { command: "time set day" },
        { wait: { condition: "screen_open" } },
        { expect: { tool: "world_query", args: { action: "block", x: 1, y: 2, z: 3 }, path: "block", equals: "minecraft:stone" } },
        { expect_block: { x: 1, y: 2, z: 3, is: "stone", within: 1000 } },
        { tool: "server_info" },
      ],
    });
    expect(s.steps.map((st) => (st.kind === "tool" ? [st.tool, st.args] : st.kind))).toEqual([
      ["bot_action", { bot: "Buyer", action: "command", command: "/shop" }],
      ["bot_action", { bot: "Buyer", action: "chat", text: "hi" }],
      ["bot_action", { bot: "Buyer", action: "gui_click", slot: 3 }],
      ["server_command", { command: "time set day" }],
      ["wait_for", { condition: "screen_open" }],
      ["world_query", { action: "block", x: 1, y: 2, z: 3 }],
      ["wait_for", { condition: "block", x: 1, y: 2, z: 3, is: "stone", timeoutMs: 1000 }],
      ["server_info", {}],
    ]);
    expect(s.steps[0]!.label).toBe("Buyer: /shop");
  });

  it("names the step that is wrong", () => {
    expect(() => parseScenario({ steps: [{ tool: "server_info" }, { teleport: true }] })).toThrow(/steps\[1\]: unknown step/);
    expect(() => parseScenario({ steps: [{ tool: "x", expect: { path: "a", equalz: 1 } }] })).toThrow(/steps\[0\]: expect: unknown equalz/);
    expect(() => parseScenario({ steps: [] })).toThrow(ScenarioError);
    expect(() => parseScenario({ steps: [{ bot: "B" }] })).toThrow(/command, chat or action/);
  });
});

describe("runScenario", () => {
  it("runs setup, steps and cleanup and passes when every expectation holds", async () => {
    const t = tools({ bot_action: (a) => (a.action === "command" ? { success: true, messages: [{ text: "Bought!" }] } : {}) });
    const s = parseScenario({
      name: "buy",
      bots: ["Buyer"],
      setup: [{ command: "give Buyer emerald 5" }],
      steps: [{ bot: "Buyer", command: "/shop buy", expect: { path: "messages[*].text", matches: "bought" } }],
      cleanup: [{ command: "clear Buyer" }],
    });
    const r = await runScenario(s, t.call, clock());
    expect(r).toMatchObject({ name: "buy", passed: true, newExceptions: 0 });
    expect(t.calls.map(([tool]) => tool)).toEqual(["bot_spawn", "server_command", "bot_action", "server_command", "bot_remove", "exceptions"]);
    expect(r.steps.map((st) => [st.phase, st.ok])).toEqual([["setup", true], ["setup", true], ["steps", true], ["cleanup", true], ["cleanup", true]]);
  });

  it("stops at the first failure, says what was expected and found, gathers context and still cleans up", async () => {
    const t = tools({
      bot_action: (a) => {
        if (a.action === "command") return { success: true, messages: [{ text: "Not enough coins" }] };
        if (a.action === "messages") return { messages: [{ text: "Not enough coins" }] };
        if (a.action === "hud_read") return { sidebar: { lines: ["Coins: 3"] } };
        return {};
      },
      events: () => ({ counts: [{ type: "PlayerCommandPreprocessEvent", count: 1 }] }),
      logs: () => ({ lines: [{ message: "Shop: price missing" }] }),
    });
    const s = parseScenario({
      bots: ["Buyer"],
      steps: [
        { bot: "Buyer", command: "/shop buy", expect: { path: "messages[*].text", matches: "bought" } },
        { command: "never runs" },
      ],
    });
    const r = await runScenario(s, t.call, clock());
    expect(r.passed).toBe(false);
    expect(r.failure).toMatchObject({
      phase: "steps", index: 0, step: "Buyer: /shop buy",
      expected: { matches: "bought" }, actual: ["Not enough coins"],
      context: {
        Buyer: { messages: ["Not enough coins"], hud: { sidebar: { lines: ["Coins: 3"] } } },
        events: [{ type: "PlayerCommandPreprocessEvent", count: 1 }],
        warnings: ["Shop: price missing"],
      },
    });
    expect(t.calls.some(([, a]) => a.command === "never runs")).toBe(false);
    expect(t.calls.some(([tool]) => tool === "bot_remove")).toBe(true);
  });

  it("retries an expectation within its time window", async () => {
    let n = 0;
    const t = tools({ world_query: () => ({ block: ++n >= 3 ? "minecraft:stone" : "minecraft:air" }) });
    const s = parseScenario({ steps: [{ expect: { tool: "world_query", args: {}, path: "block", equals: "minecraft:stone" }, within: 1000 }] });
    const r = await runScenario(s, t.call, clock());
    expect(r.passed).toBe(true);
    expect(n).toBe(3);
  });

  it("expect_message looks at messages since the last action, until `within` runs out", async () => {
    const c = clock();
    let commandAt = 0;
    const t = tools({
      bot_action: (a) => {
        if (a.action === "command") { commandAt = c.now(); return {}; }
        return { messages: c.now() - commandAt >= 400 ? [{ text: "Welcome to the shop" }] : [] };
      },
    });
    const s = parseScenario({ steps: [{ sleep: 50 }, { bot: "B1", command: "/shop" }, { expect_message: { bot: "B1", matches: "welcome" } }] });
    const r = await runScenario(s, t.call, c);
    expect(r.passed).toBe(true);
    const since = t.calls.filter(([, a]) => a.action === "messages").map(([, a]) => a.since);
    expect(commandAt).toBeGreaterThan(1_000_000);
    expect(new Set(since)).toEqual(new Set([commandAt]));

    const late = await runScenario(parseScenario({ steps: [{ bot: "B1", command: "/shop" }, { expect_message: { bot: "B1", matches: "welcome", within: 200 } }] }), t.call, c);
    expect(late.failure).toMatchObject({ message: "B1 received no message matching /welcome/", actual: [] });
  });

  it("saves results and substitutes ${vars}", async () => {
    const t = tools({ world_edit: (a) => (a.action === "snapshot" ? { snapshotId: "snap-7" } : { restored: a.id }) });
    const s = parseScenario({
      vars: { region: { min: { x: 0, y: 0, z: 0 }, max: { x: 1, y: 1, z: 1 } } },
      setup: [{ tool: "world_edit", args: { action: "snapshot", min: "${region.min}", max: "${region.max}" }, save: "snap" }],
      steps: [{ command: "say snapshot ${snap.snapshotId} taken at ${start}" }],
      cleanup: [{ tool: "world_edit", args: { action: "restore", id: "${snap.snapshotId}" } }],
    });
    const c = clock();
    const r = await runScenario(s, t.call, c);
    expect(r.passed).toBe(true);
    expect(t.calls[0]![1]).toEqual({ action: "snapshot", min: { x: 0, y: 0, z: 0 }, max: { x: 1, y: 1, z: 1 } });
    expect(t.calls[1]![1]).toEqual({ command: "say snapshot snap-7 taken at 1000000" });
    expect(t.calls[2]![1]).toEqual({ action: "restore", id: "snap-7" });
  });

  it("an undefined variable fails its step", async () => {
    const r = await runScenario(parseScenario({ steps: [{ command: "say ${nope}" }] }), tools({}).call, clock());
    expect(r.failure?.message).toMatch(/\$\{nope\} is not defined/);
  });

  it("expectError passes only on that error code", async () => {
    const t = tools({ bot_action: () => { throw { code: "BOT_NOT_FOUND", message: "No bot named Ghost" }; } });
    const ok = await runScenario(parseScenario({ steps: [{ bot: "Ghost", action: "state", expectError: "BOT_NOT_FOUND" }] }), t.call, clock());
    expect(ok.passed).toBe(true);
    const wrong = await runScenario(parseScenario({ steps: [{ bot: "Ghost", action: "state", expectError: "TIMEOUT" }] }), t.call, clock());
    expect(wrong.failure).toMatchObject({ expected: "TIMEOUT", actual: "BOT_NOT_FOUND" });
  });

  it("a failing tool call fails the step with its error", async () => {
    const t = tools({ server_command: () => { throw { code: "NO_INSTANCE", message: "No server is connected" }; } });
    const r = await runScenario(parseScenario({ steps: [{ command: "list" }] }), t.call, clock());
    expect(r.failure?.message).toBe("server_command failed: NO_INSTANCE: No server is connected");
  });

  it("expect_event matches the events since the last action", async () => {
    const t = tools({ events: (a) => (a.action === "query" ? { events: [{ type: "ShopBuyEvent", player: "B1", cancelled: true, fields: { price: 30 } }] } : {}) });
    const pass = await runScenario(parseScenario({ steps: [{ expect_event: { type: "ShopBuyEvent", player: "B1", matches: '"price":30' } }] }), t.call, clock());
    expect(pass.passed).toBe(true);
    const fail = await runScenario(parseScenario({ steps: [{ expect_event: { type: "ShopBuyEvent", matches: '"cancelled":false', within: 0 } }] }), t.call, clock());
    expect(fail.failure?.message).toMatch(/no ShopBuyEvent matching/);
  });

  it("expect_no_exceptions fails when a bug was logged since the start", async () => {
    const t = tools({ exceptions: (a) => ({ total: 1, exceptions: [{ id: "abcd1234", type: "java.lang.NullPointerException", since: a.since }] }) });
    const r = await runScenario(parseScenario({ steps: [{ expect_no_exceptions: true }] }), t.call, clock());
    expect(r.failure).toMatchObject({ message: "1 new exception group(s) since the scenario started" });
    expect(r.newExceptions).toBe(1);
  });

  it("a cleanup failure fails a passing scenario but every cleanup step runs", async () => {
    const t = tools({ server_command: (a) => { if (a.command === "bad") throw { code: "X", message: "nope" }; return {}; } });
    const r = await runScenario(parseScenario({ steps: [{ command: "ok" }], cleanup: [{ command: "bad" }, { command: "after" }] }), t.call, clock());
    expect(r.passed).toBe(false);
    expect(r.cleanupErrors).toEqual(["server_command bad: server_command failed: X: nope"]);
    expect(t.calls.some(([, a]) => a.command === "after")).toBe(true);
  });

  it("refuses to nest scenario_run", async () => {
    const r = await runScenario(parseScenario({ steps: [{ tool: "scenario_run", args: {} }] }), tools({}).call, clock());
    expect(r.failure?.message).toMatch(/cannot run scenario_run/);
  });
});
