import { writeFileSync } from "node:fs";
import { resolve } from "node:path";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { CraftwireError } from "../errors.js";
import { load, summarize } from "../tools/scenario-tools.js";
import type { ToolContext } from "../tools/registry.js";
import { inProcessCaller } from "./caller.js";
import { formatReport, formatSummary, toJUnit } from "./report.js";
import { runScenario, type ScenarioReport } from "./runner.js";

export const TEST_USAGE = `Usage: craftwire test [files or folders…] [--server <dir>] [--junit <file>] [--json] [--wait <seconds>]
  Runs *.cwtest.json scenarios (default: every one under the current folder).
  --server <dir>   start this Paper server for the run and stop it afterwards
  --junit <file>   also write JUnit XML (for CI)
  --json           print the full JSON report instead of the summary
  --wait <seconds> how long to wait for a running server to connect (default 60)
Exit code: 0 all passed, 1 a scenario failed, 2 the run could not start.`;

export interface TestOptions {
  paths: string[];
  serverDir?: string;
  junit?: string;
  json: boolean;
  waitMs: number;
}

export function parseTestArgs(argv: string[]): TestOptions {
  const o: TestOptions = { paths: [], json: false, waitMs: 60_000 };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]!;
    const value = () => {
      const v = argv[++i];
      if (v === undefined || v.startsWith("--")) throw new CraftwireError("USAGE", `${a} needs a value`);
      return v;
    };
    if (a === "--server") o.serverDir = value();
    else if (a === "--junit") o.junit = value();
    else if (a === "--json") o.json = true;
    else if (a === "--wait") {
      const s = Number(value());
      if (!Number.isFinite(s) || s < 0) throw new CraftwireError("USAGE", "--wait takes seconds");
      o.waitMs = s * 1000;
    } else if (a.startsWith("--")) throw new CraftwireError("USAGE", `Unknown option ${a}`);
    else o.paths.push(a);
  }
  return o;
}

/** Runs the scenarios on a hub; returns the exit code. */
export async function runTests(o: TestOptions, env: { ctx: ToolContext; makeServer: () => McpServer; cwd: string; out: (s: string) => void }): Promise<number> {
  const scenarios = load(undefined, o.paths, env.cwd);
  const caller = await inProcessCaller(env.makeServer());
  try {
    if (o.serverDir !== undefined) {
      env.out(`starting the server in ${o.serverDir}…`);
      const r = await caller.call("server_process", { action: "start", serverDir: resolve(env.cwd, o.serverDir) });
      if (!r.ok) throw new CraftwireError("SERVER_START_FAILED", `The server did not start: ${JSON.stringify(r.data)}`);
    } else if (!(await serverConnects(env.ctx, o.waitMs, env.out))) {
      throw new CraftwireError("NO_SERVER", `No Paper server with the Craftwire plugin connected within ${o.waitMs / 1000} s`,
        "Start one, or pass --server <dir> to let the test run start it.");
    }
    const reports: ScenarioReport[] = [];
    for (const s of scenarios) {
      const r = await runScenario(s, caller.call);
      reports.push(r);
      if (!o.json) env.out(formatReport(r));
    }
    env.out(o.json ? JSON.stringify(summarize(reports), null, 2) : formatSummary(reports));
    if (o.junit !== undefined) writeFileSync(resolve(env.cwd, o.junit), toJUnit(reports));
    return reports.every((r) => r.passed) ? 0 : 1;
  } finally {
    await caller.close();
  }
}

async function serverConnects(ctx: ToolContext, waitMs: number, out: (s: string) => void): Promise<boolean> {
  const connected = () => ctx.agents.instances().some((i) => i.kind === "server");
  if (connected()) return true;
  out("waiting for a Paper server with the Craftwire plugin to connect…");
  const deadline = Date.now() + waitMs;
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 250));
    if (connected()) return true;
  }
  return false;
}
