/**
 * scenario_record's recorder against its real inputs: calls written by the audit log (with its clipping and result
 * previews), and the scenario it makes parsed and replayed by the scenario runner.
 */
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { AuditLog } from "../src/audit.js";
import { recordScenario } from "../src/scenario/record.js";
import { runScenario, type ToolCaller } from "../src/scenario/runner.js";
import { parseScenario } from "../src/scenario/scenario.js";

type Args = Record<string, unknown>;

/** A session as the hub's audit log keeps it: each call with its tool result as the tool returned it. */
function session(calls: Array<{ tool: string; args: Args; ok?: boolean; result?: unknown }>) {
  const audit = new AuditLog(mkdtempSync(join(tmpdir(), "cw-audit-")));
  for (const c of calls) {
    audit.write({
      tool: c.tool, args: c.args, ok: c.ok ?? true, ms: 1, session: "s1", user: "alice",
      result: { content: [{ type: "text", text: JSON.stringify(c.result ?? {}) }] },
    });
  }
  return audit.calls("s1");
}

/** Replays a scenario on fake tools and returns the calls it made, by phase. */
async function replay(scenario: unknown) {
  const calls: Array<[string, Args]> = [];
  const call: ToolCaller = async (tool, args) => {
    calls.push([tool, args]);
    return { ok: true, data: tool === "exceptions" ? { total: 0, exceptions: [] } : {} };
  };
  const report = await runScenario(parseScenario(scenario), call);
  return { report, calls: calls.filter(([t]) => t !== "exceptions") };
}

describe("scenario_record", () => {
  it("a recorded session replays the calls that changed something, in order", async () => {
    const calls = session([
      { tool: "list_instances", args: {} },
      { tool: "bot_spawn", args: { names: ["Buyer"], operationId: "op-1" }, result: { spawned: 1, names: "Buyer" } },
      { tool: "bot_action", args: { bot: "Buyer", action: "command", command: "/shop" } },
      { tool: "wait_for", args: { condition: "screen_open", bot: "Buyer" } },
      { tool: "bot_action", args: { bot: "Buyer", action: "gui_read" } },
      { tool: "bot_action", args: { bot: "Buyer", action: "gui_click", slot: 13 } },
      { tool: "server_command", args: { command: "give Buyer diamond" }, ok: false },
      { tool: "bot_action", args: { bot: "Buyer", action: "chat", text: "thanks" } },
    ]);
    const { scenario, skipped } = recordScenario(calls, { name: "shop" });
    const { report, calls: replayed } = await replay(scenario);

    expect(report.passed).toBe(true);
    expect(replayed).toEqual([
      ["bot_spawn", { names: ["Buyer"] }],
      ["bot_action", { bot: "Buyer", action: "command", command: "/shop" }],
      ["wait_for", { condition: "screen_open", bot: "Buyer" }],
      ["bot_action", { bot: "Buyer", action: "gui_click", slot: 13 }],
      ["bot_action", { bot: "Buyer", action: "chat", text: "thanks" }],
      ["bot_remove", { bots: ["Buyer"] }],
    ]);
    expect(skipped.map((s) => [s.index, s.tool, s.why])).toEqual([
      [1, "list_instances", "sets up the environment or only inspects the hub"],
      [5, "bot_action", "only reads"],
      [7, "server_command", "the call failed"],
    ]);
  });

  it("a replay never reuses the session's operation ids", () => {
    const calls = session([{ tool: "world_edit", args: { action: "fill", operationId: "abc" } }]);
    expect(JSON.stringify(recordScenario(calls, { name: "x" }).scenario)).not.toContain("abc");
  });

  it("keeps reading calls when asked, and only the chosen range", () => {
    const calls = session([
      { tool: "server_command", args: { command: "time set day" } },
      { tool: "world_query", args: { action: "block", x: 1, y: 2, z: 3 } },
      { tool: "server_command", args: { command: "weather clear" } },
    ]);
    const steps = recordScenario(calls, { name: "x", from: 2, to: 3, includeReads: true }).scenario.steps as unknown[];
    expect(steps).toEqual([
      { tool: "world_query", args: { action: "block", x: 1, y: 2, z: 3 } },
      { command: "weather clear" },
      { expect_no_exceptions: {} },
    ]);
  });

  it("removes in cleanup only the bots the session left behind", async () => {
    const calls = session([
      { tool: "bot_spawn", args: { count: 3, kind: "java", address: "localhost" }, result: { spawned: 3, names: "Bot1..Bot3" } },
      { tool: "bot_spawn", args: { count: 50, group: "load" }, result: { spawned: 50, names: "Load1..Load50" } },
      { tool: "bot_spawn", args: { count: 5, group: "temp" }, result: { spawned: 5, names: "Temp1..Temp5" } },
      { tool: "bot_remove", args: { bot: "Bot2" } },
      { tool: "bot_remove", args: { group: "temp" } },
      { tool: "bot_action", args: { bot: "Bot1", action: "jump" } },
    ]);
    const { scenario } = recordScenario(calls, { name: "x" });
    expect(scenario.cleanup).toEqual([
      { tool: "bot_remove", args: { group: "load" } },
      { tool: "bot_remove", args: { bots: ["Bot1", "Bot3"], kind: "java" } },
    ]);
  });

  /** A spawn of many bots answers with a summary of each; the audit log keeps only the first 2,000 characters. */
  it("still removes the bots of a spawn whose result the audit log clipped", () => {
    const bots = Array.from({ length: 60 }, (_, i) => ({ name: `Bot${i + 1}`, world: "world", x: i, y: 64, z: i, uuid: "0".repeat(36) }));
    const calls = session([
      { tool: "bot_spawn", args: { count: 60, detail: true }, result: { spawned: 60, names: "Bot1..Bot60", ms: 900, bots } },
      { tool: "bot_action", args: { bot: "Bot1", action: "jump" } },
    ]);
    const cleanup = recordScenario(calls, { name: "x" }).scenario.cleanup as Array<{ args: { bots: string[] } }>;
    expect(cleanup[0]!.args.bots).toHaveLength(60);
  });

  it("refuses a range with nothing to repeat", () => {
    const calls = session([{ tool: "list_instances", args: {} }, { tool: "logs", args: {} }]);
    expect(() => recordScenario(calls, { name: "x" })).toThrow(/nothing a scenario can repeat/);
  });
});
