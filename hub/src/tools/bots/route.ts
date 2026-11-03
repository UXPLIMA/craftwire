/** Finds the agents that run bots (Paper servers for plugin bots, bot runners for java and bedrock) and the one for a bot. */
import type { InstanceInfo } from "../../agents.js";
import { CraftwireError } from "../../errors.js";
import type { ToolContext } from "../registry.js";
import { type BotKind, directoryFor } from "./directory.js";

/** The bot kind an agent runs for a request: plugin on servers, else the asked kind (java when not asked). */
export function kindOf(i: InstanceInfo, asked?: BotKind): BotKind {
  return i.kind === "server" ? "plugin" : asked ?? (i.botKinds?.[0] ?? "java");
}

function fits(i: InstanceInfo, kind?: BotKind): boolean {
  if (i.kind === "server") return kind === undefined || kind === "plugin";
  if (i.kind !== "botrunner") return false;
  if (kind === undefined) return true;
  return kind !== "plugin" && (i.botKinds ?? ["java", "bedrock"]).includes(kind);
}

/** The agents that run bots of `kind` (every bot-running agent when kind is undefined), narrowed by `instance`. */
export function botAgents(c: ToolContext, kind?: BotKind, instance?: string): InstanceInfo[] {
  let list = c.agents.instances().filter((i) => fits(i, kind));
  if (instance !== undefined) {
    const s = instance.toLowerCase();
    list = list.filter((i) => i.id === instance || i.name.toLowerCase() === s);
    if (list.length === 0) throw new CraftwireError("NO_INSTANCE", `No bot-running instance matches "${instance}"`, "Call list_instances.");
  }
  return list;
}

export function requireAgents(c: ToolContext, kind?: BotKind, instance?: string): InstanceInfo[] {
  const list = botAgents(c, kind, instance);
  if (list.length > 0) return list;
  if (kind === "java" || kind === "bedrock") {
    throw new CraftwireError("NO_BOTRUNNER", `No bot runner for ${kind} bots is connected`,
      "Start one with botrunner_process {action:'start'}, or run craftwire-botrunner with the hub token on another machine.");
  }
  throw new CraftwireError("NO_INSTANCE", "No Paper server (Craftwire plugin) is connected",
    "Start the Paper server with the Craftwire plugin installed, or spawn kind:'java' bots from a bot runner.");
}

/** The agent that runs one bot: from the spawn directory, else the only candidate, else the one that says it has it. */
export async function agentForBot(c: ToolContext, name: string, kind?: BotKind, instance?: string): Promise<InstanceInfo> {
  const dir = directoryFor(c.agents);
  const candidates = requireAgents(c, kind, instance);
  const home = dir.locate(name);
  const known = home === undefined ? undefined : candidates.find((i) => i.id === home.instanceId);
  if (known !== undefined) return known;
  if (candidates.length === 1) return candidates[0]!;
  for (const i of candidates) {
    const r = (await c.agents.request(i.id, "bot.list", { bot: name, limit: 1 }, 10_000).catch(() => undefined)) as { count?: number } | undefined;
    if (r !== undefined && (r.count ?? 0) > 0) {
      dir.recordSpawn(i.id, kindOf(i, kind), name);
      return i;
    }
  }
  throw new CraftwireError("BOT_NOT_FOUND", `No bot named ${name}`, "bot_list shows the bots of every kind.");
}

export interface Answer {
  instance: InstanceInfo;
  result?: unknown;
  error?: unknown;
}

/** Sends one request to several agents at once; every answer comes back, failures included. */
export async function broadcast(c: ToolContext, agents: InstanceInfo[], method: string, params: Record<string, unknown>,
  timeoutMs: number): Promise<Answer[]> {
  const settled = await Promise.allSettled(agents.map((i) => c.agents.request(i.id, method, params, timeoutMs)));
  return settled.map((s, n) => s.status === "fulfilled"
    ? { instance: agents[n]!, result: s.value }
    : { instance: agents[n]!, error: s.reason });
}

/** The error of the first failed answer when every answer failed, so a single-agent call reports the agent's own error. */
export function allFailed(answers: Answer[]): unknown {
  return answers.length > 0 && answers.every((a) => a.error !== undefined) ? answers[0]!.error : undefined;
}
