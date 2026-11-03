/** load_test (spawn in steps, run a behavior, sample the server and profile each step) and profile_diff. */
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { defineTool, ok, targetArgs, type ToolContext } from "../registry.js";
import type { BotKind } from "./directory.js";
import { writeLoadReport } from "./load-report.js";
import { merge } from "./merge.js";
import { profileDiff } from "./profile-diff.js";
import { broadcast, requireAgents } from "./route.js";
import { BEHAVIORS, KINDS, spawnShape } from "./schemas.js";
import { spawnOn } from "./spawn.js";

export interface StepResult {
  bots: number;
  spawnMs: number;
  tps: number;
  msptAvg: number;
  msptP95: number;
  msptMax: number;
  slowTicks: number;
  heapMb: number;
  chunks: number;
  topOwners: { owner: string; percent: number }[];
  /** Java and Bedrock bots: how the step's bots were spread over the bot runners, and each runner's tick time. */
  runners?: { name: string; bots: number; tickMs: number }[];
}

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

type Info = { tps?: number[]; mspt?: number; memory?: { usedMb?: number }; worlds?: { loadedChunks?: number }[] };
type Profile = { ticks?: { msptAvg?: number; p95?: number; max?: number; over50ms?: number }; owners?: { owner: string; percent: number }[] };

export function registerLoadTools(server: McpServer, ctx: ToolContext): void {
  defineTool(server, ctx, "load_test",
    "Load-test a server: spawn bots in steps (ramp, e.g. [10,50,100,200]), optionally run a behavior, and per step sample TPS, MSPT, "
      + "heap and chunks and profile who uses the tick. Returns the steps and an HTML report path. Bots are removed at the end unless keep.",
    {
      ...targetArgs,
      kind: z.enum(KINDS).default("plugin"),
      ramp: z.array(z.number().int().min(1).max(1000)).min(1).max(10),
      stepSeconds: z.number().int().min(10).max(600).default(30),
      behavior: z.object({ type: z.enum(BEHAVIORS), params: z.record(z.string(), z.unknown()).optional() }).optional(),
      profile: spawnShape.profile,
      spread: spawnShape.spread,
      perTick: spawnShape.perTick,
      namePrefix: z.string().regex(/^[A-Za-z0-9_]{1,13}$/).default("Load"),
      group: z.string().regex(/^[A-Za-z0-9_-]{1,32}$/).default("load"),
      address: spawnShape.address,
      runner: z.string().optional(),
      keep: z.boolean().default(false),
    },
    async (args, c) => ok(await run(c, args)));

  defineTool(server, ctx, "profile_diff",
    "Compare two profile results (before and after a change): MSPT, busy time, and the owners and hot methods that changed most.",
    { before: z.record(z.string(), z.unknown()), after: z.record(z.string(), z.unknown()) },
    async (args) => ok(profileDiff(args.before, args.after)));
}

interface RunArgs {
  instance?: string;
  kind: BotKind;
  ramp: number[];
  stepSeconds: number;
  behavior?: { type: string; params?: Record<string, unknown> };
  profile?: "light" | "realistic";
  spread?: { radius: number };
  perTick?: number;
  namePrefix: string;
  group: string;
  address?: string;
  runner?: string;
  keep: boolean;
}

async function run(c: ToolContext, a: RunArgs): Promise<unknown> {
  const server = c.agents.resolve("server", a.instance);
  const steps: StepResult[] = [];
  let current = 0;
  try {
    for (const target of [...a.ramp].sort((x, y) => x - y)) {
      const started = Date.now();
      if (target > current) {
        await spawnOn(c, {
          kind: a.kind, count: target - current, namePrefix: a.namePrefix, group: a.group, perTick: a.perTick, spread: a.spread,
          profile: a.profile, address: a.address, runner: a.runner, instance: a.kind === "plugin" ? a.instance : undefined,
        });
        current = target;
        if (a.behavior !== undefined) await setBehavior(c, a);
      }
      const spawnMs = Date.now() - started;
      const step = await measure(c, server.id, target, spawnMs, a.stepSeconds * 1000);
      if (a.kind !== "plugin") step.runners = await runnerShares(c, a.kind);
      steps.push(step);
    }
  } finally {
    if (!a.keep) await removeGroup(c, a).catch(() => undefined);
  }
  const report = writeLoadReport(c.home, { server: server.name, kind: a.kind, behavior: a.behavior?.type, steps });
  return { server: server.id, kind: a.kind, steps, report };
}

/** One load test over several runners is one report: each runner's share of the bots and its loop's tick time. */
async function runnerShares(c: ToolContext, kind: BotKind): Promise<{ name: string; bots: number; tickMs: number }[]> {
  const answers = await broadcast(c, requireAgents(c, kind), "runner.stats", {}, 10_000);
  return answers.filter((x) => x.error === undefined).map((x) => {
    const r = x.result as { bots?: number; tickMs?: number };
    return { name: x.instance.name, bots: r.bots ?? 0, tickMs: r.tickMs ?? 0 };
  });
}

async function setBehavior(c: ToolContext, a: RunArgs): Promise<void> {
  const agents = requireAgents(c, a.kind);
  const set = { group: a.group, action: "set", type: a.behavior!.type, params: a.behavior!.params ?? {} };
  merge(await broadcast(c, agents, "bot.behavior", set, 30_000), { sum: ["bots"] });
}

async function removeGroup(c: ToolContext, a: RunArgs): Promise<void> {
  await broadcast(c, requireAgents(c, a.kind), "bot.remove", { group: a.group }, 120_000);
}

/** A third of the step lets the server settle; the rest is profiled while server.info is sampled every 2 s. */
async function measure(c: ToolContext, serverId: string, bots: number, spawnMs: number, stepMs: number): Promise<StepResult> {
  const settle = Math.floor(stepMs / 3);
  await sleep(settle);
  const profileMs = Math.max(1000, Math.min(stepMs - settle - 1000, 15_000));
  const profiling = c.agents.request(serverId, "profile.run", { durationMs: profileMs }, profileMs + 20_000) as Promise<Profile>;
  const infos: Info[] = [];
  const until = Date.now() + profileMs;
  while (Date.now() < until) {
    infos.push((await c.agents.request(serverId, "server.info", {}, 10_000)) as Info);
    await sleep(2000);
  }
  const prof = await profiling;
  const rest = stepMs - settle - profileMs;
  if (rest > 0) await sleep(rest);
  const last = infos.at(-1) ?? {};
  const mspts = infos.map((i) => i.mspt ?? 0);
  return {
    bots, spawnMs,
    tps: last.tps?.[0] ?? 0,
    msptAvg: round(prof.ticks?.msptAvg ?? avg(mspts)),
    msptP95: round(prof.ticks?.p95 ?? Math.max(...mspts, 0)),
    msptMax: round(prof.ticks?.max ?? Math.max(...mspts, 0)),
    slowTicks: prof.ticks?.over50ms ?? 0,
    heapMb: Math.max(...infos.map((i) => i.memory?.usedMb ?? 0), 0),
    chunks: (last.worlds ?? []).reduce((s, w) => s + (w.loadedChunks ?? 0), 0),
    topOwners: (prof.owners ?? []).slice(0, 5).map((o) => ({ owner: o.owner, percent: o.percent })),
  };
}

function avg(xs: number[]): number {
  return xs.length === 0 ? 0 : xs.reduce((s, x) => s + x, 0) / xs.length;
}

function round(v: number): number {
  return Math.round(v * 100) / 100;
}
