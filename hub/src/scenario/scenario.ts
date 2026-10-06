import type { Assertion } from "./assert.js";

/**
 * A scenario file (`*.cwtest.json`): steps the hub runs in order, each one a Craftwire tool call or a check.
 * Shorthands are turned into plain tool steps here, so the runner only knows a few kinds of step.
 */
export interface Scenario {
  name: string;
  /** Constants for `${name}` in step arguments. */
  vars: Record<string, unknown>;
  /** Bots spawned before setup and removed after cleanup. */
  bots?: { names: string[]; location?: Record<string, unknown> };
  setup: Step[];
  steps: Step[];
  /** Always runs, also after a failure. */
  cleanup: Step[];
  timeoutMs: number;
}

export type Step =
  | { kind: "tool"; label: string; tool: string; args: Record<string, unknown>; save?: string; expect: Assertion[]; within: number; expectError?: string; bot?: string; /** Only checks the game: does not count as the action later expectations refer to. */ check?: boolean }
  | { kind: "sleep"; label: string; ms: number }
  | { kind: "expect_message"; label: string; bot: string; matches: string; within: number }
  | { kind: "expect_hud"; label: string; bot: string; assertion: Assertion; within: number }
  | { kind: "expect_event"; label: string; type: string; player?: string; matches?: string; within: number }
  | { kind: "expect_no_exceptions"; label: string; instance?: string };

export class ScenarioError extends Error {}

const DEFAULT_WITHIN = 5000;
const ASSERTION_KEYS = ["path", "equals", "matches", "contains", "exists", "lessThan", "greaterThan", "length", "every"] as const;
const STEP_KINDS = "tool, bot, command, wait, sleep, expect, expect_message, expect_hud, expect_block, expect_event, expect_no_exceptions";

type Raw = Record<string, unknown>;
const isObject = (v: unknown): v is Raw => v !== null && typeof v === "object" && !Array.isArray(v);

export function parseScenario(raw: unknown, fallbackName = "scenario"): Scenario {
  if (!isObject(raw)) throw new ScenarioError("A scenario is a JSON object with a steps array");
  if (!Array.isArray(raw.steps) || raw.steps.length === 0) throw new ScenarioError("steps: a non-empty array of steps is required");
  const s: Scenario = {
    name: typeof raw.name === "string" ? raw.name : fallbackName,
    vars: isObject(raw.vars) ? raw.vars : {},
    setup: steps(raw.setup, "setup"),
    steps: steps(raw.steps, "steps"),
    cleanup: steps(raw.cleanup, "cleanup"),
    timeoutMs: typeof raw.timeoutMs === "number" ? raw.timeoutMs : 300_000,
  };
  const bots = parseBots(raw.bots);
  if (bots) s.bots = bots;
  return s;
}

function parseBots(v: unknown): Scenario["bots"] {
  if (v === undefined) return undefined;
  if (Array.isArray(v) && v.every((n) => typeof n === "string")) return { names: v };
  if (isObject(v) && Array.isArray(v.names) && v.names.every((n) => typeof n === "string")) {
    return isObject(v.location) ? { names: v.names as string[], location: v.location } : { names: v.names as string[] };
  }
  throw new ScenarioError('bots: a list of names, or {names: [...], location: {x, y, z}}');
}

function steps(v: unknown, where: string): Step[] {
  if (v === undefined) return [];
  if (!Array.isArray(v)) throw new ScenarioError(`${where}: must be an array of steps`);
  return v.map((s, i) => {
    try {
      return parseStep(s);
    } catch (e) {
      throw new ScenarioError(`${where}[${i}]: ${(e as Error).message}`);
    }
  });
}

export function parseStep(raw: unknown): Step {
  if (!isObject(raw)) throw new Error(`a step is an object with one of: ${STEP_KINDS}`);
  const { name, save, expect, within, expectError, ...rest } = raw;
  const label = typeof name === "string" ? name : undefined;
  const assertions = parseAssertions(expect);
  const extras = { save: str(save, "save"), expect: assertions, within: num(within, "within") ?? 0, expectError: str(expectError, "expectError") };

  if (typeof rest.tool === "string") {
    const args = rest.args === undefined ? {} : rest.args;
    if (!isObject(args)) throw new Error("args must be an object");
    return tool(label ?? describeCall(rest.tool, args), rest.tool, args, extras);
  }
  if (typeof rest.sleep === "number") return { kind: "sleep", label: label ?? `sleep ${rest.sleep} ms`, ms: rest.sleep };
  if (isObject(rest.wait)) return tool(label ?? describeCall("wait_for", rest.wait), "wait_for", rest.wait, extras);
  if (typeof rest.bot === "string") return botStep(rest.bot, rest, label, extras);
  if (typeof rest.command === "string") {
    const { command, ...more } = rest;
    return tool(label ?? `server_command ${command}`, "server_command", { command, ...more }, extras);
  }
  if (isObject(raw.expect) && typeof raw.expect.tool === "string") {
    // {expect: {tool, args, path, equals…}}: a call made only to check its result.
    const { tool: t, args = {}, ...check } = raw.expect;
    if (!isObject(args)) throw new Error("expect.args must be an object");
    return { ...tool(label ?? `expect ${describeCall(String(t), args)}`, String(t), args, { ...extras, expect: [assertion(check)] }), check: true } as Step;
  }
  if (isObject(rest.expect_message)) {
    const e = rest.expect_message;
    return { kind: "expect_message", label: label ?? `expect ${e.bot} receives /${e.matches}/`, bot: req(e, "bot"), matches: req(e, "matches"), within: num(e.within, "within") ?? DEFAULT_WITHIN };
  }
  if (isObject(rest.expect_hud)) {
    const { bot, within: w, ...check } = rest.expect_hud;
    if (typeof bot !== "string") throw new Error("expect_hud.bot is required");
    return { kind: "expect_hud", label: label ?? `expect ${bot}'s HUD ${JSON.stringify(check)}`, bot, assertion: assertion(check), within: num(w, "within") ?? DEFAULT_WITHIN };
  }
  if (isObject(rest.expect_block)) {
    const { within: w, ...where } = rest.expect_block;
    const timeoutMs = num(w, "within") ?? DEFAULT_WITHIN;
    return { ...tool(label ?? `expect block ${where.x} ${where.y} ${where.z} ${where.is !== undefined ? `is ${where.is}` : `is not ${where.isNot}`}`,
      "wait_for", { condition: "block", ...where, timeoutMs }, extras), check: true } as Step;
  }
  if (isObject(rest.expect_event)) {
    const e = rest.expect_event;
    const step: Step = { kind: "expect_event", label: label ?? `expect event ${e.type}`, type: req(e, "type"), within: num(e.within, "within") ?? DEFAULT_WITHIN };
    if (typeof e.player === "string") step.player = e.player;
    if (typeof e.matches === "string") step.matches = e.matches;
    return step;
  }
  if (rest.expect_no_exceptions !== undefined) {
    const e = isObject(rest.expect_no_exceptions) ? rest.expect_no_exceptions : {};
    const step: Step = { kind: "expect_no_exceptions", label: label ?? "expect no new exceptions" };
    if (typeof e.instance === "string") step.instance = e.instance;
    return step;
  }
  throw new Error(`unknown step ${JSON.stringify(raw).slice(0, 120)}; a step has one of: ${STEP_KINDS}`);
}

/** {bot, command} / {bot, chat} / {bot, action, …}: bot_action. */
function botStep(bot: string, rest: Raw, label: string | undefined, extras: Extras): Step {
  const { bot: _b, command, chat, action, ...more } = rest;
  let args: Raw;
  if (typeof command === "string") args = { bot, action: "command", command, ...more };
  else if (typeof chat === "string") args = { bot, action: "chat", text: chat, ...more };
  else if (typeof action === "string") args = { bot, action, ...more };
  else throw new Error("a bot step needs command, chat or action");
  const what = typeof command === "string" ? command : typeof chat === "string" ? `says "${chat}"` : String(action);
  const step = tool(label ?? `${bot}: ${what}`, "bot_action", args, extras);
  if (step.kind === "tool") step.bot = bot;
  return step;
}

interface Extras { save: string | undefined; expect: Assertion[]; within: number; expectError: string | undefined }

function tool(label: string, name: string, args: Raw, extras: Extras): Step {
  const step: Step = { kind: "tool", label, tool: name, args, expect: extras.expect, within: extras.within };
  if (extras.save !== undefined) step.save = extras.save;
  if (extras.expectError !== undefined) step.expectError = extras.expectError;
  return step;
}

function parseAssertions(v: unknown): Assertion[] {
  if (v === undefined || (isObject(v) && typeof v.tool === "string")) return [];
  if (Array.isArray(v)) return v.map((a) => assertion(a));
  return [assertion(v)];
}

function assertion(v: unknown): Assertion {
  if (!isObject(v)) throw new Error("expect: an object like {path, equals|matches|contains|exists|lessThan|greaterThan|length}");
  const unknown = Object.keys(v).filter((k) => !(ASSERTION_KEYS as readonly string[]).includes(k));
  if (unknown.length > 0) throw new Error(`expect: unknown ${unknown.join(", ")} (use ${ASSERTION_KEYS.join(", ")})`);
  if (!ASSERTION_KEYS.some((k) => k !== "path" && k !== "every" && v[k] !== undefined)) {
    throw new Error("expect: needs one of equals, matches, contains, exists, lessThan, greaterThan, length");
  }
  if (typeof v.matches === "string") {
    try {
      new RegExp(v.matches, "i");
    } catch (e) {
      throw new Error(`expect.matches: ${(e as Error).message}`);
    }
  }
  return v as Assertion;
}

function describeCall(name: string, args: Raw): string {
  const brief = Object.entries(args).filter(([, v]) => typeof v !== "object").map(([k, v]) => `${k}=${String(v)}`).join(" ");
  return brief ? `${name} ${brief}` : name;
}

const str = (v: unknown, k: string) => {
  if (v !== undefined && typeof v !== "string") throw new Error(`${k} must be a string`);
  return v;
};
const num = (v: unknown, k: string) => {
  if (v !== undefined && (typeof v !== "number" || v < 0)) throw new Error(`${k} must be a number of milliseconds`);
  return v;
};
const req = (o: Raw, k: string): string => {
  if (typeof o[k] !== "string") throw new Error(`${k} is required`);
  return o[k];
};
