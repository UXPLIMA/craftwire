import { writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { readHttpToken } from "../config.js";
import { CraftwireError } from "../errors.js";
import { readServeInfo } from "../http/serve-command.js";
import { runningHub } from "../hub.js";
import { load, summarize } from "../tools/scenario-tools.js";
import { formatReport, formatSummary, toJUnit } from "./report.js";
import { runScenario, type ScenarioReport, type ToolCaller } from "./runner.js";

export const TEST_USAGE = `Usage: craftwire test [files or folders…] [--server <dir>] [--junit <file>] [--json] [--wait <seconds>] [--hub <url>]
  Runs *.cwtest.json scenarios (default: every one under the current folder).
  --hub <url>      run them on a hub started with craftwire serve (token: CRAFTWIRE_TOKEN, else ~/.craftwire/http.json);
                   a local craftwire serve is used on its own
  --server <dir>   start this Paper server for the run and stop it afterwards
  --junit <file>   also write JUnit XML (for CI)
  --json           print the full JSON report instead of the summary
  --wait <seconds> how long to wait for a running server to connect (default 60)
Exit code: 0 all passed, 1 a scenario failed, 2 the run could not start.`;

export interface TestOptions {
  paths: string[];
  hub?: string;
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
    else if (a === "--hub") o.hub = value();
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

export type HubChoice = { kind: "http"; url: string; token: string } | { kind: "local" };

/**
 * Which hub runs the scenarios: the one --hub names, else a `craftwire serve` running on this machine, else a hub of
 * this command's own. Another hub (an AI session over stdio) holds the games, so the run cannot use them.
 */
export async function chooseHub(home: string, hub: string | undefined, envToken: string | undefined): Promise<HubChoice> {
  let url = hub;
  if (url === undefined && (await runningHub(home)) !== undefined) {
    url = readServeInfo(home)?.url;
    if (url === undefined) {
      throw new CraftwireError("HUB_RUNNING", "Another Craftwire hub is running (an AI session) and holds the connected server and clients.",
        "Ask the AI to run them with scenario_run {files: [...]}, or run the hub as `craftwire serve` (then this command uses it).");
    }
  }
  if (url === undefined) return { kind: "local" };
  const token = envToken ?? readHttpToken(home);
  if (token === undefined) throw new CraftwireError("NO_TOKEN", "No token for the hub", "Set CRAFTWIRE_TOKEN to the token craftwire serve printed.");
  return { kind: "http", url, token };
}

/** Runs the scenarios through `call` (a hub in this process, or one reached over HTTP); returns the exit code. */
export async function runTests(o: TestOptions, env: { call: ToolCaller; cwd: string; out: (s: string) => void }): Promise<number> {
  const scenarios = load(undefined, o.paths, env.cwd);
  if (o.serverDir !== undefined) {
    env.out(`starting the server in ${o.serverDir}…`);
    const r = await env.call("server_process", { action: "start", serverDir: resolve(env.cwd, o.serverDir) });
    if (!r.ok) throw new CraftwireError("SERVER_START_FAILED", `The server did not start: ${JSON.stringify(r.data)}`);
  } else if (!(await serverConnects(env.call, o.waitMs, env.out))) {
    throw new CraftwireError("NO_SERVER", `No Paper server with the Craftwire plugin connected within ${o.waitMs / 1000} s`,
      "Start one, or pass --server <dir> to let the test run start it.");
  }
  const reports: ScenarioReport[] = [];
  try {
    for (const s of scenarios) {
      const r = await runScenario(s, env.call);
      reports.push(r);
      if (!o.json) env.out(formatReport(r));
    }
  } finally {
    // A hub elsewhere keeps running, so the server this run started is stopped here.
    if (o.serverDir !== undefined) await env.call("server_process", { action: "stop", serverDir: resolve(env.cwd, o.serverDir) });
  }
  env.out(o.json ? JSON.stringify(summarize(reports), null, 2) : formatSummary(reports));
  if (o.junit !== undefined) writeFileSync(resolve(env.cwd, o.junit), toJUnit(reports));
  return reports.every((r) => r.passed) ? 0 : 1;
}

async function serverConnects(call: ToolCaller, waitMs: number, out: (s: string) => void): Promise<boolean> {
  const connected = async () => {
    const r = await call("list_instances", {});
    return r.ok && ((r.data as { instances?: { kind?: string }[] }).instances ?? []).some((i) => i.kind === "server");
  };
  if (await connected()) return true;
  out("waiting for a Paper server with the Craftwire plugin to connect…");
  const deadline = Date.now() + waitMs;
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 250));
    if (await connected()) return true;
  }
  return false;
}
