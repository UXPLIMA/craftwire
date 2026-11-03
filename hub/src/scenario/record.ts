/**
 * Record and replay: turns a session's tool calls from the audit log (an agent's or a person's) into a `.cwtest.json`
 * scenario, so a flow tried once by hand runs again after every change. Failed calls, setup tools (server_process,
 * plugin_deploy …) and, unless asked, calls that only read are left out; wait_for stays, since it is what keeps the
 * replay in step with the game. Bots the session spawned and did not remove are removed in cleanup.
 */
import type { AuditEntry } from "../audit.js";
import { CraftwireError } from "../errors.js";
import { isReadingCall } from "../tool-policy.js";
import { expandNames } from "../tools/bots/directory.js";

/** Tools that set up the machine or look at the hub itself: a test replaying them would redo the environment. */
const NOT_REPLAYED = new Set([
  "scenario_run", "scenario_record", "list_instances", "get_request_status", "server_process", "client_process",
  "plugin_deploy", "botrunner_process", "load_test", "profile", "trace", "profile_diff", "screenshot", "record", "camera",
]);

export interface RecordOptions {
  name: string;
  /** First and last call to keep, 1-based positions in the session (as the panel numbers them). */
  from?: number;
  to?: number;
  /** Keep calls that only read (state, gui_read, world_query …) as steps; off by default, they change nothing. */
  includeReads?: boolean;
}

export interface Recorded {
  scenario: Record<string, unknown>;
  /** Calls left out and why, so the person sees what the replay does not repeat. */
  skipped: { index: number; tool: string; why: string }[];
}

type Raw = Record<string, unknown>;

export function recordScenario(calls: AuditEntry[], o: RecordOptions): Recorded {
  const from = Math.max(1, o.from ?? 1);
  const to = Math.min(calls.length, o.to ?? calls.length);
  const steps: Raw[] = [];
  const skipped: Recorded["skipped"] = [];
  const bots = new SpawnedBots();
  for (let i = from; i <= to; i++) {
    const c = calls[i - 1]!;
    const why = skipReason(c, o.includeReads ?? false);
    if (why !== undefined) {
      skipped.push({ index: i, tool: c.tool, why });
      continue;
    }
    const args = withoutOperationId(c.args as Raw);
    if (c.tool === "bot_spawn") bots.spawned(args, c.result);
    if (c.tool === "bot_remove") bots.removed(args);
    steps.push(toStep(c.tool, args));
  }
  if (steps.length === 0) {
    throw new CraftwireError("NOTHING_TO_RECORD", `Calls ${from}..${to} of this session have nothing a scenario can repeat`,
      "Pick a range with bot actions, commands or world changes (includeReads:true keeps reading calls too).");
  }
  steps.push({ expect_no_exceptions: {} });
  const cleanup = bots.cleanup();
  return { scenario: { name: o.name, steps, ...(cleanup.length > 0 ? { cleanup } : {}) }, skipped };
}

function skipReason(c: AuditEntry, includeReads: boolean): string | undefined {
  if (!c.ok) return "the call failed";
  if (NOT_REPLAYED.has(c.tool)) return "sets up the environment or only inspects the hub";
  const args = c.args as Raw | null;
  if (args === null || typeof args !== "object" || Array.isArray(args)) return "no arguments recorded";
  if (args.truncated === true) return "the arguments were too long for the audit log";
  if (c.tool !== "wait_for" && !includeReads && isReadingCall(c.tool, args)) return "only reads";
  return undefined;
}

/** A call as a step, in the scenario shorthands where one fits (they read better in a review). */
function toStep(tool: string, args: Raw): Raw {
  if (tool === "server_command" && typeof args.command === "string") return { ...args };
  if (tool === "wait_for") return { wait: args };
  if (tool === "bot_action" && typeof args.bot === "string") {
    const { action, text, command, ...rest } = args;
    if (action === "command" && typeof command === "string") return { ...rest, command };
    if (action === "chat" && typeof text === "string") return { ...rest, chat: text };
    return { ...rest, action, ...(text !== undefined ? { text } : {}), ...(command !== undefined ? { command } : {}) };
  }
  return { tool, args };
}

function withoutOperationId(args: Raw): Raw {
  // A replay must run every call again; an idempotency key would hand back the first run's result instead.
  const { operationId: _o, ...rest } = args;
  return rest;
}

/** The bots a session spawned and has not removed yet, by group or by name. */
class SpawnedBots {
  private readonly groups = new Map<string, unknown>();
  private readonly names = new Map<string, unknown>();

  spawned(args: Raw, result: unknown): void {
    if (typeof args.group === "string") {
      this.groups.set(args.group, args.kind);
      return;
    }
    for (const n of spawnedNames(result)) this.names.set(n, args.kind);
  }

  removed(args: Raw): void {
    if (args.all === true) {
      this.groups.clear();
      this.names.clear();
      return;
    }
    if (typeof args.group === "string") this.groups.delete(args.group);
    for (const n of [args.bot, args.name, ...(Array.isArray(args.bots) ? args.bots : [])]) {
      if (typeof n === "string") this.names.delete(n);
    }
  }

  cleanup(): Raw[] {
    const kind = (k: unknown) => (typeof k === "string" ? { kind: k } : {});
    const steps: Raw[] = [...this.groups].map(([group, k]) => ({ tool: "bot_remove", args: { group, ...kind(k) } }));
    const byKind = new Map<unknown, string[]>();
    for (const [n, k] of this.names) byKind.set(k, [...(byKind.get(k) ?? []), n]);
    for (const [k, names] of byKind) {
      for (let i = 0; i < names.length; i += 1000) steps.push({ tool: "bot_remove", args: { bots: names.slice(i, i + 1000), ...kind(k) } });
    }
    return steps;
  }
}

/** The names in a bot_spawn result preview: its text part is the tool's JSON, {spawned, names: "Bot1..Bot50"}. */
function spawnedNames(result: unknown): string[] {
  const text = Array.isArray(result) && typeof result[0] === "string" ? result[0] : undefined;
  if (text === undefined) return [];
  try {
    const names = (JSON.parse(text) as { names?: unknown }).names;
    return typeof names === "string" ? expandNames(names) : Array.isArray(names) ? names.filter((n): n is string => typeof n === "string") : [];
  } catch {
    return [];
  }
}
