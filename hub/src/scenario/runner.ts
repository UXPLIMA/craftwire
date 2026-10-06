import { checkAssertion, type Assertion } from "./assert.js";
import { select } from "./path.js";
import type { Scenario, Step } from "./scenario.js";

/** Calls one Craftwire tool: `data` is the parsed result, or the error ({code, message, hint}) when not ok. */
export type ToolCaller = (tool: string, args: Record<string, unknown>) => Promise<{ ok: boolean; data: unknown }>;

export type Phase = "setup" | "steps" | "cleanup";

export interface StepReport {
  phase: Phase;
  index: number;
  label: string;
  ok: boolean;
  ms: number;
}

export interface Failure {
  phase: Phase;
  index: number;
  step: string;
  message: string;
  expected?: unknown;
  actual?: unknown;
  /** What the server and bots looked like when it failed. */
  context?: Record<string, unknown>;
}

export interface ScenarioReport {
  name: string;
  passed: boolean;
  durationMs: number;
  steps: StepReport[];
  failure?: Failure;
  /** Cleanup problems; they fail a scenario that otherwise passed. */
  cleanupErrors?: string[];
  /** Exception groups (distinct bugs) logged while the scenario ran. */
  newExceptions: number;
}

export interface RunOptions {
  now?: () => number;
  sleep?: (ms: number) => Promise<void>;
  pollMs?: number;
}

class StepFailed extends Error {
  constructor(message: string, readonly expected?: unknown, readonly actual?: unknown) {
    super(message);
  }
}

const SCENARIO_TOOLS = new Set(["scenario_run"]);

export async function runScenario(s: Scenario, call: ToolCaller, opts: RunOptions = {}): Promise<ScenarioReport> {
  const now = opts.now ?? Date.now;
  const sleep = opts.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)));
  const pollMs = opts.pollMs ?? 200;
  const start = now();
  const vars: Record<string, unknown> = { ...s.vars, start };
  const bots = new Set<string>(s.bots?.names ?? []);
  const reports: StepReport[] = [];
  let lastActionAt = start;
  let failure: Failure | undefined;

  const runStep = async (step: Step): Promise<void> => {
    switch (step.kind) {
      case "sleep":
        await sleep(step.ms);
        return;
      case "tool": {
        if (SCENARIO_TOOLS.has(step.tool)) throw new StepFailed("A scenario cannot run scenario_run");
        if (step.bot) bots.add(step.bot);
        const args = interpolate(step.args, vars) as Record<string, unknown>;
        const assertions = interpolate(step.expect, vars) as Assertion[];
        if (!step.check) lastActionAt = now();
        const data = await poll(step.within, async () => {
          const r = await call(step.tool, args);
          if (step.expectError !== undefined) {
            const code = r.ok ? undefined : (r.data as { code?: string }).code;
            if (code !== step.expectError) throw new StepFailed(`expected error ${step.expectError}`, step.expectError, r.ok ? r.data : code);
            return r.data;
          }
          if (!r.ok) throw new StepFailed(`${step.tool} failed: ${errorText(r.data)}`, undefined, r.data);
          for (const a of assertions) {
            const out = checkAssertion(a, r.data);
            if (!out.pass) throw new StepFailed(`expectation failed at ${a.path ? `"${a.path}"` : "the result"}`, expectationOf(a), out.actual);
          }
          return r.data;
        });
        if (step.save !== undefined) vars[step.save] = data;
        return;
      }
      case "expect_message": {
        const re = new RegExp(String(interpolate(step.matches, vars)), "i");
        await poll(step.within, async () => {
          const r = await call("bot_action", { bot: step.bot, action: "messages", since: lastActionAt, limit: 200 });
          if (!r.ok) throw new StepFailed(`bot_action messages failed: ${errorText(r.data)}`);
          const texts = select(r.data, "messages[*].text").values.map(String);
          if (!texts.some((t) => re.test(t))) throw new StepFailed(`${step.bot} received no message matching /${re.source}/`, re.source, texts);
        });
        bots.add(step.bot);
        return;
      }
      case "expect_hud": {
        const a = interpolate(step.assertion, vars) as Assertion;
        await poll(step.within, async () => {
          const r = await call("bot_action", { bot: step.bot, action: "hud_read" });
          if (!r.ok) throw new StepFailed(`bot_action hud_read failed: ${errorText(r.data)}`);
          const out = checkAssertion(a.path === undefined && a.matches !== undefined ? { ...a, path: "" } : a, r.data);
          if (!out.pass) throw new StepFailed(`${step.bot}'s HUD does not match`, expectationOf(a), out.actual);
        });
        bots.add(step.bot);
        return;
      }
      case "expect_event": {
        const re = step.matches === undefined ? undefined : new RegExp(String(interpolate(step.matches, vars)), "i");
        await poll(step.within, async () => {
          const args: Record<string, unknown> = { action: "query", type: step.type, since: lastActionAt, limit: 200 };
          if (step.player !== undefined) args.player = interpolate(step.player, vars);
          const r = await call("events", args);
          if (!r.ok) throw new StepFailed(`events query failed: ${errorText(r.data)}`);
          const events = select(r.data, "events[*]").values;
          if (!events.some((e) => re === undefined || re.test(JSON.stringify(e)))) {
            throw new StepFailed(`no ${step.type}${step.player ? ` for ${step.player}` : ""}${re ? ` matching /${re.source}/` : ""} since the last action`, re?.source ?? step.type, events.slice(-5));
          }
        });
        return;
      }
      case "expect_no_exceptions": {
        const args: Record<string, unknown> = { since: start };
        if (step.instance !== undefined) args.instance = step.instance;
        const r = await call("exceptions", args);
        if (!r.ok) throw new StepFailed(`exceptions failed: ${errorText(r.data)}`);
        const list = select(r.data, "exceptions").values[0] as unknown[] | undefined;
        if (list && list.length > 0) throw new StepFailed(`${list.length} new exception group(s) since the scenario started`, [], list);
        return;
      }
    }
  };

  /** Runs `attempt` until it passes or `within` ms are over; the last failure is the step's failure. */
  const poll = async <T>(within: number, attempt: () => Promise<T>): Promise<T> => {
    const deadline = now() + within;
    for (;;) {
      try {
        return await attempt();
      } catch (e) {
        if (!(e instanceof StepFailed) || now() + pollMs > deadline) throw e;
        await sleep(pollMs);
      }
    }
  };

  const runPhase = async (phase: Phase, list: Step[], stopOnFailure: boolean): Promise<string[]> => {
    const errors: string[] = [];
    for (const [index, step] of list.entries()) {
      if (phase !== "cleanup" && now() - start > s.timeoutMs) {
        failure = { phase, index, step: step.label, message: `the scenario ran longer than ${s.timeoutMs} ms` };
        return errors;
      }
      const t0 = now();
      try {
        await runStep(step);
        reports.push({ phase, index, label: step.label, ok: true, ms: now() - t0 });
      } catch (e) {
        reports.push({ phase, index, label: step.label, ok: false, ms: now() - t0 });
        const f: Failure = { phase, index, step: step.label, message: e instanceof Error ? e.message : String(e) };
        if (e instanceof StepFailed) {
          if (e.expected !== undefined) f.expected = e.expected;
          if (e.actual !== undefined) f.actual = e.actual;
        }
        if (!stopOnFailure) {
          errors.push(`${step.label}: ${f.message}`);
          continue;
        }
        f.context = await gatherContext(call, start, [...bots]);
        failure = f;
        return errors;
      }
    }
    return errors;
  };

  const spawn: Step[] = s.bots
    ? [{ kind: "tool", label: `spawn ${s.bots.names.join(", ")}`, tool: "bot_spawn", args: { names: s.bots.names, ...(s.bots.location ? { location: s.bots.location } : {}) }, expect: [], within: 0 }]
    : [];
  const despawn: Step[] = s.bots
    ? s.bots.names.map((n): Step => ({ kind: "tool", label: `remove ${n}`, tool: "bot_remove", args: { name: n }, expect: [], within: 0 }))
    : [];

  await runPhase("setup", [...spawn, ...s.setup], true);
  if (!failure) await runPhase("steps", s.steps, true);
  const cleanupErrors = await runPhase("cleanup", [...s.cleanup, ...despawn], false);

  const exceptions = await call("exceptions", { since: start }).catch(() => undefined);
  const newExceptions = exceptions?.ok ? Number((exceptions.data as { total?: number }).total ?? 0) : 0;
  const report: ScenarioReport = {
    name: s.name, passed: !failure && cleanupErrors.length === 0, durationMs: now() - start, steps: reports, newExceptions,
  };
  if (failure) report.failure = failure;
  if (cleanupErrors.length > 0) report.cleanupErrors = cleanupErrors;
  return report;
}

/** Best effort: what each bot received and shows, which events fired, new bugs and warnings since the start. */
async function gatherContext(call: ToolCaller, since: number, bots: string[]): Promise<Record<string, unknown>> {
  const ctx: Record<string, unknown> = {};
  const get = async (tool: string, args: Record<string, unknown>) => {
    try {
      const r = await call(tool, args);
      return r.ok ? r.data : undefined;
    } catch {
      return undefined;
    }
  };
  for (const bot of bots) {
    const messages = await get("bot_action", { bot, action: "messages", since, limit: 20 });
    const hud = await get("bot_action", { bot, action: "hud_read" });
    ctx[bot] = { messages: select(messages, "messages[*].text").values, hud };
  }
  const events = await get("events", { action: "summary", since });
  if (events !== undefined) ctx.events = select(events, "counts").values[0];
  const exceptions = await get("exceptions", { since, limit: 5 });
  if (exceptions !== undefined) ctx.exceptions = select(exceptions, "exceptions").values[0];
  const logs = await get("logs", { level: "WARN", since, limit: 20 });
  if (logs !== undefined) ctx.warnings = select(logs, "lines[*]").values.map((l) => (l as { message?: string }).message);
  return ctx;
}

function expectationOf(a: Assertion): unknown {
  const { path: _p, every: _e, ...checks } = a;
  return checks;
}

function errorText(data: unknown): string {
  const e = data as { code?: string; message?: string };
  return e && typeof e === "object" && e.message ? `${e.code ?? "ERROR"}: ${e.message}` : JSON.stringify(data);
}

const WHOLE = /^\$\{([^}]+)\}$/;
const PART = /\$\{([^}]+)\}/g;

/** Replaces `${name.path}` in strings: a whole-string reference keeps the value's type. */
export function interpolate(value: unknown, vars: Record<string, unknown>): unknown {
  if (typeof value === "string") {
    const whole = WHOLE.exec(value);
    if (whole) return lookup(whole[1]!, vars);
    return value.replace(PART, (_m, ref: string) => {
      const v = lookup(ref, vars);
      return typeof v === "string" ? v : JSON.stringify(v);
    });
  }
  if (Array.isArray(value)) return value.map((v) => interpolate(v, vars));
  if (value !== null && typeof value === "object") {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, interpolate(v, vars)]));
  }
  return value;
}

function lookup(ref: string, vars: Record<string, unknown>): unknown {
  const sel = select(vars, ref.trim());
  if (!sel.found) throw new StepFailed(`\${${ref}} is not defined (saved results and vars: ${Object.keys(vars).join(", ")})`);
  return ref.includes("[*]") ? sel.values : sel.values[0];
}
