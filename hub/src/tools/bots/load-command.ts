/**
 * `craftwire load-test`: runs load_test on a hub (one started with `craftwire serve`, or this command's own) and
 * writes the HTML report, a Markdown summary for a pull request and the raw JSON here, so CI keeps them as
 * artifacts. --max-mspt makes it a gate: the exit code is 1 when a step's p95 MSPT goes over it.
 */
import { writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { CraftwireError } from "../../errors.js";
import type { ToolCaller } from "../../scenario/runner.js";
import { renderLoadReport } from "./load-report.js";
import type { StepResult } from "./load-test.js";

export const LOAD_USAGE = `Usage: craftwire load-test --ramp 10,50,100 [options]
  Spawns bots in steps on the connected Paper server and measures TPS, MSPT, heap and who uses the tick.
  --ramp <n,n,...>      bot counts per step (required)
  --step-seconds <n>    seconds per step (default 30)
  --kind <kind>         plugin (default), java or bedrock (needs a bot runner and --address)
  --address <host:port> the server java and bedrock bots join
  --behavior <type>     what the bots do: idle, wander, follow, patrol, message_loop, menu_click
  --hub <url>           a hub started with craftwire serve (token: CRAFTWIRE_TOKEN)
  --out <file>          HTML report (default load-report.html)
  --summary <file>      Markdown summary (for a pull request comment)
  --json <file>         the full result as JSON
  --max-mspt <ms>       fail (exit 1) when a step's p95 MSPT is above this
Exit code: 0 done (and under --max-mspt), 1 over --max-mspt, 2 the run could not start.`;

export interface LoadOptions {
  ramp: number[];
  stepSeconds: number;
  kind: string;
  address?: string;
  behavior?: string;
  hub?: string;
  out: string;
  summary?: string;
  json?: string;
  maxMspt?: number;
}

export function parseLoadArgs(argv: string[]): LoadOptions {
  const o: LoadOptions = { ramp: [], stepSeconds: 30, kind: "plugin", out: "load-report.html" };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]!;
    const value = () => {
      const v = argv[++i];
      if (v === undefined || v.startsWith("--")) throw new CraftwireError("USAGE", `${a} needs a value`);
      return v;
    };
    switch (a) {
      case "--ramp": o.ramp = value().split(",").map((n) => Number(n.trim())); break;
      case "--step-seconds": o.stepSeconds = Number(value()); break;
      case "--kind": o.kind = value(); break;
      case "--address": o.address = value(); break;
      case "--behavior": o.behavior = value(); break;
      case "--hub": o.hub = value(); break;
      case "--out": o.out = value(); break;
      case "--summary": o.summary = value(); break;
      case "--json": o.json = value(); break;
      case "--max-mspt": o.maxMspt = Number(value()); break;
      default: throw new CraftwireError("USAGE", `Unknown option ${a}`);
    }
  }
  if (o.ramp.length === 0 || o.ramp.some((n) => !Number.isInteger(n) || n < 1)) {
    throw new CraftwireError("USAGE", "--ramp takes counts like 10,50,100");
  }
  return o;
}

export async function runLoad(o: LoadOptions, call: ToolCaller, cwd: string, out: (s: string) => void): Promise<number> {
  const args: Record<string, unknown> = { ramp: o.ramp, stepSeconds: o.stepSeconds, kind: o.kind };
  if (o.address !== undefined) args.address = o.address;
  if (o.behavior !== undefined) args.behavior = { type: o.behavior };
  out(`load test: ${o.ramp.join(" → ")} ${o.kind} bots, ${o.stepSeconds} s a step…`);
  const r = await call("load_test", args);
  if (!r.ok) throw new CraftwireError("LOAD_TEST_FAILED", `load_test failed: ${JSON.stringify(r.data)}`);
  const data = r.data as { server: string; kind: string; steps: StepResult[] };
  const report = { server: data.server, kind: data.kind, ...(o.behavior ? { behavior: o.behavior } : {}), steps: data.steps };
  writeFileSync(resolve(cwd, o.out), renderLoadReport(report));
  if (o.json !== undefined) writeFileSync(resolve(cwd, o.json), JSON.stringify(data, null, 2));
  if (o.summary !== undefined) writeFileSync(resolve(cwd, o.summary), markdown(data.steps, o.maxMspt));
  for (const s of data.steps) out(`  ${s.bots} bots: TPS ${s.tps}, MSPT ${s.msptAvg} avg / ${s.msptP95} p95, ${s.slowTicks} slow ticks`);
  out(`report: ${resolve(cwd, o.out)}`);
  const over = o.maxMspt !== undefined ? data.steps.filter((s) => s.msptP95 > o.maxMspt!) : [];
  if (over.length > 0) out(`over --max-mspt ${o.maxMspt}: ${over.map((s) => `${s.bots} bots (${s.msptP95} ms)`).join(", ")}`);
  return over.length > 0 ? 1 : 0;
}

function markdown(steps: StepResult[], maxMspt: number | undefined): string {
  const lines = [
    "### Craftwire load test",
    "",
    "| bots | TPS | MSPT avg | MSPT p95 | slow ticks | heap MB | top owner |",
    "|---|---|---|---|---|---|---|",
  ];
  for (const s of steps) {
    const flag = maxMspt !== undefined && s.msptP95 > maxMspt ? " **over**" : "";
    const top = s.topOwners[0] ? `${s.topOwners[0].owner} ${s.topOwners[0].percent}%` : "";
    lines.push(`| ${s.bots} | ${s.tps} | ${s.msptAvg} | ${s.msptP95}${flag} | ${s.slowTicks} | ${s.heapMb} | ${top.replace(/\|/g, "/")} |`);
  }
  return `${lines.join("\n")}\n`;
}
