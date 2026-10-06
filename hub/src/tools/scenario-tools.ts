import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { CraftwireError } from "../errors.js";
import { inProcessCaller } from "../scenario/caller.js";
import { findScenarioFiles, loadScenarioFile } from "../scenario/files.js";
import { runScenario, type ScenarioReport } from "../scenario/runner.js";
import { parseScenario, ScenarioError, type Scenario } from "../scenario/scenario.js";
import { defineTool, ok, type ToolContext } from "./registry.js";

export const SCENARIO_HELP =
  "A scenario: {name, vars?, bots?: [names], setup?: [steps], steps: [steps], cleanup?: [steps] (always runs), timeoutMs?}. " +
  "Steps: {tool, args} (any Craftwire tool); {bot, command} / {bot, chat} / {bot, action, …} (bot_action); {command} (server_command); {wait: {…wait_for}}; {sleep: ms}. " +
  "Any of these may add save: \"name\" (use the result later as ${name.path}), expect: {path, equals|matches|contains|exists|lessThan|greaterThan|length} (or a list), within: ms (retry until it holds) and expectError: \"CODE\". " +
  "Checks: {expect: {tool, args, path, equals…}}, {expect_message: {bot, matches}}, {expect_hud: {bot, path?, matches|equals|contains}}, {expect_block: {x,y,z, is|isNot}}, {expect_event: {type, player?, matches?}} " +
  "(these wait up to `within`, default 5000 ms, and look at what happened since the last action), {expect_no_exceptions: {}}. " +
  "Paths: a.b[0].c, [-1] for the last element, [*] for any element. ${start} is the scenario's start time.";

/** scenario_run: runs scenarios in this hub; `makeServer` builds the private MCP server their steps call. */
export function registerScenarioTools(server: McpServer, ctx: ToolContext, makeServer: () => McpServer): void {
  defineTool(server, ctx, "scenario_run",
    "Run test scenarios: steps (tool calls and checks) the hub runs in order, stopping at the first failure and reporting what was expected, what was found, and the context at that moment (bots' messages and HUD, events, new exceptions, warnings). Cleanup always runs. " +
    "Pass `scenario` (inline) or `files` (*.cwtest.json files or folders, relative to the hub's working directory). " + SCENARIO_HELP,
    {
      scenario: z.record(z.string(), z.unknown()).optional(),
      files: z.array(z.string()).optional(),
    },
    async (args) => {
      const scenarios = load(args.scenario, args.files, process.cwd());
      return ok(summarize(await runAll(scenarios, makeServer)));
    });
}

export function load(inline: Record<string, unknown> | undefined, files: string[] | undefined, cwd: string): Scenario[] {
  try {
    if (inline !== undefined) return [parseScenario(inline)];
    if (files === undefined) throw new ScenarioError("Pass scenario (inline) or files");
    const found = findScenarioFiles(files, cwd);
    if (found.length === 0) throw new ScenarioError("No *.cwtest.json files found");
    return found.map(loadScenarioFile);
  } catch (e) {
    if (e instanceof ScenarioError) throw new CraftwireError("INVALID_SCENARIO", e.message, "The format: https://github.com/uxplima/craftwire/blob/main/docs/scenarios.md");
    throw e;
  }
}

export async function runAll(scenarios: Scenario[], makeServer: () => McpServer, onReport?: (r: ScenarioReport) => void): Promise<ScenarioReport[]> {
  const caller = await inProcessCaller(makeServer());
  try {
    const reports: ScenarioReport[] = [];
    for (const s of scenarios) {
      const r = await runScenario(s, caller.call);
      reports.push(r);
      onReport?.(r);
    }
    return reports;
  } finally {
    await caller.close();
  }
}

export function summarize(reports: ScenarioReport[]) {
  return { passed: reports.filter((r) => r.passed).length, failed: reports.filter((r) => !r.passed).length, scenarios: reports };
}
