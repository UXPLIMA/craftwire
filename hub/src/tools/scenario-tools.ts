import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { CraftwireError } from "../errors.js";
import { inProcessCaller } from "../scenario/caller.js";
import { findScenarioFiles, loadScenarioFile } from "../scenario/files.js";
import { recordScenario } from "../scenario/record.js";
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
    "Run test scenarios (scenario inline, or files *.cwtest.json): setup, steps (tool calls, bot actions, commands, waits) with expect checks, cleanup. Stops at the first failure and reports the context. Format: craftwire://docs/scenarios.",
    {
      scenario: z.record(z.string(), z.unknown()).optional(),
      files: z.array(z.string()).optional(),
    },
    async (args) => {
      const scenarios = load(args.scenario, args.files, process.cwd());
      return ok(summarize(await runAll(scenarios, makeServer)));
    });

  defineTool(server, ctx, "scenario_record",
    "Turn this session's tool calls (or calls from..to, positions as in the web panel) into a scenario that repeats them: "
      + "bot actions, commands, waits and world changes; failed and read-only calls are left out. file writes a .cwtest.json.",
    {
      session: z.string().optional(),
      name: z.string().min(1).default("recorded"),
      from: z.number().int().min(1).optional(),
      to: z.number().int().min(1).optional(),
      includeReads: z.boolean().default(false),
      file: z.string().regex(/\.cwtest\.json$/).optional(),
      overwrite: z.boolean().default(false),
    },
    async (args, c) => {
      const calls = sessionCalls(c, args.session);
      const r = recordScenario(calls, args);
      const out: Record<string, unknown> = { steps: (r.scenario.steps as unknown[]).length, skipped: r.skipped };
      if (args.file !== undefined) out.file = writeScenario(c, args.file, args.overwrite, r.scenario);
      return ok({ ...out, scenario: r.scenario });
    });
}

/** A session's calls, if this person may read them: their own sessions, or any for the owner and the local user. */
function sessionCalls(c: ToolContext, session: string | undefined) {
  const id = session ?? c.session ?? "local";
  const calls = c.audit.calls(id).filter((e) => e.tool !== "scenario_record");
  if (calls.length === 0) {
    throw new CraftwireError("SESSION_NOT_FOUND", `No recorded calls for session ${id}`,
      "The hub keeps the newest 5000 calls since it started; the web panel's replay tab lists the sessions.");
  }
  const me = c.user ?? "local";
  if (me !== "owner" && me !== "local" && calls.some((e) => (e.user ?? "local") !== me)) {
    throw new CraftwireError("FORBIDDEN", `Session ${id} belongs to someone else`, "Record one of your own sessions, or ask the owner.");
  }
  return calls;
}

function writeScenario(c: ToolContext, file: string, overwrite: boolean, scenario: Record<string, unknown>): string {
  if (c.build?.http) {
    throw new CraftwireError("FILE_WRITE_DISABLED", "Over craftwire serve the hub does not write files for callers",
      "Leave file out and save the returned scenario yourself.");
  }
  const path = resolve(process.cwd(), file);
  if (existsSync(path) && !overwrite) {
    throw new CraftwireError("FILE_EXISTS", `${path} already exists`, "Pick another name or pass overwrite:true.");
  }
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, `${JSON.stringify(scenario, null, 2)}\n`);
  return path;
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
