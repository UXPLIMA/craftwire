/** bot_spawn routing: plugin bots go to one Paper server, protocol bots are split across the bot runners that run their kind. */
import type { InstanceInfo } from "../../agents.js";
import { CraftwireError } from "../../errors.js";
import type { ToolContext } from "../registry.js";
import { type BotKind, directoryFor } from "./directory.js";
import { merge } from "./merge.js";
import type { Answer } from "./route.js";
import { requireAgents } from "./route.js";

export interface SpawnArgs extends Record<string, unknown> {
  instance?: string;
  operationId?: string;
  runner?: string;
  kind: BotKind;
  count: number;
  namePrefix: string;
  names?: string[];
  perTick?: number;
  spread?: { radius: number };
  address?: string;
}

/** Long enough for every join at `perTick` per 50 ms tick, plus chunk loading for spread spawns. */
export function spawnTimeout(a: SpawnArgs): number {
  const count = a.names?.length ?? a.count;
  const joining = Math.ceil(count / (a.perTick ?? 5)) * 50;
  const chunks = a.spread !== undefined ? count * 25 : 0;
  return Math.min(60_000 + joining + chunks, 30 * 60_000);
}

export async function spawnOn(c: ToolContext, a: SpawnArgs): Promise<unknown> {
  if (a.kind !== "plugin" && a.address === undefined) {
    throw new CraftwireError("INVALID_PARAMS", `${a.kind} bots need address (host or host:port of the server to join)`,
      "e.g. address:'localhost:25565' for Java, 'localhost:19132' for Bedrock.");
  }
  const { instance, operationId, runner, ...rest } = a;
  const timeout = spawnTimeout(a);
  const dir = directoryFor(c.agents);
  if (a.kind === "plugin") {
    const inst = c.agents.resolve("server", instance);
    const r = (await c.ops.run(operationId, () => c.agents.request(inst.id, "bot.spawn", rest, timeout))) as Record<string, unknown>;
    if (typeof r.names === "string") dir.recordSpawn(inst.id, "plugin", r.names);
    return r;
  }
  const agents = requireAgents(c, a.kind, runner ?? instance);
  return c.ops.run(operationId, async () => {
    const shares = split(a, agents, dir);
    const answers: Answer[] = await Promise.all(shares.map(async (s) => {
      try {
        return { instance: s.agent, result: await c.agents.request(s.agent.id, "bot.spawn", { ...rest, names: s.names }, timeout) };
      } catch (e) {
        return { instance: s.agent, error: e };
      }
    }));
    for (const ans of answers) {
      const names = (ans.result as { names?: unknown } | undefined)?.names;
      if (typeof names === "string") dir.recordSpawn(ans.instance.id, a.kind, names);
    }
    return merge(answers, { sum: ["spawned"], max: ["ms"], join: ["names"], concat: ["failed", "bots"], limit: 1000 });
  });
}

/**
 * Names for each runner, in proportion to its capacity: the hub names the bots itself so two runners never pick the same
 * name from one prefix.
 */
function split(a: SpawnArgs, agents: InstanceInfo[], dir: ReturnType<typeof directoryFor>): { agent: InstanceInfo; names: string[] }[] {
  const names = a.names ?? allocate(a.namePrefix, a.count, (n) => dir.locate(n) !== undefined);
  const weights = agents.map((i) => i.maxBots ?? 1000);
  const total = weights.reduce((x, y) => x + y, 0);
  const out: { agent: InstanceInfo; names: string[] }[] = [];
  let start = 0;
  agents.forEach((agent, n) => {
    const take = n === agents.length - 1 ? names.length - start : Math.round((names.length * weights[n]!) / total);
    if (take > 0) out.push({ agent, names: names.slice(start, start + take) });
    start += take;
  });
  return out;
}

function allocate(prefix: string, count: number, taken: (n: string) => boolean): string[] {
  const out: string[] = [];
  for (let i = 1; out.length < count; i++) {
    const name = `${prefix}${i}`;
    if (name.length > 16) throw new CraftwireError("INVALID_PARAMS", `No free names left for prefix ${prefix}`, "Use a shorter namePrefix.");
    if (name.length >= 3 && !taken(name)) out.push(name);
  }
  return out;
}
