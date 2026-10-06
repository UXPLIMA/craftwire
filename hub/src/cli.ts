#!/usr/bin/env node
import { existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { ConfigError, craftwireHome } from "./config.js";
import { formatChecks, runDoctor } from "./doctor.js";
import { startHub } from "./hub.js";
import { chooseHub, parseTestArgs, runTests, TEST_USAGE } from "./scenario/test-command.js";
import { httpCaller, inProcessCaller } from "./scenario/caller.js";
import { parseServeArgs, serve, SERVE_USAGE } from "./http/serve-command.js";
import { CraftwireError } from "./errors.js";
import { createCraftwireServer } from "./server.js";
import { setupCommand, setupEnvFromProcess } from "./setup.js";
import { HUB_VERSION } from "./version.js";

const log = (msg: string) => process.stderr.write(`[craftwire] ${msg}\n`);

async function main(): Promise<void> {
  const home = craftwireHome();
  const hub = await startHub(home, log);
  const server = createCraftwireServer(hub.ctx);
  const transport = new StdioServerTransport();
  let stopping = false;
  const shutdown = () => {
    if (stopping) return;
    stopping = true;
    void hub.close().finally(() => process.exit(0));
  };
  transport.onclose = shutdown;
  // StdioServerTransport does not report stdin EOF; without this the hub outlives Claude Code and keeps its port.
  process.stdin.once("end", shutdown);
  process.stdin.once("close", shutdown);
  process.once("SIGINT", shutdown);
  process.once("SIGTERM", shutdown);
  await server.connect(transport);
  log(`hub ${HUB_VERSION} listening on 127.0.0.1:${hub.port} (state: ${home})`);
}

async function test(args: string[]): Promise<void> {
  if (args.includes("--help") || args.includes("-h")) {
    process.stdout.write(`${TEST_USAGE}\n`);
    return;
  }
  const fail = (e: unknown) => {
    const msg = e instanceof CraftwireError ? `${e.message}${e.hint ? `\n${e.hint}` : ""}` : e instanceof Error ? e.message : String(e);
    process.stderr.write(`craftwire test: ${msg}\n`);
  };
  let opts;
  try {
    opts = parseTestArgs(args);
  } catch (e) {
    fail(e);
    process.stderr.write(`${TEST_USAGE}\n`);
    process.exit(2);
  }
  const home = craftwireHome();
  const out = (s: string) => process.stdout.write(`${s}\n`);
  let choice;
  try {
    choice = await chooseHub(home, opts.hub, process.env.CRAFTWIRE_TOKEN);
  } catch (e) {
    fail(e);
    process.exit(2);
  }
  let code = 2;
  if (choice.kind === "http") {
    try {
      const caller = await httpCaller(choice.url, choice.token);
      try {
        code = await runTests(opts, { call: caller.call, cwd: process.cwd(), out });
      } finally {
        await caller.close();
      }
    } catch (e) {
      fail(e);
    }
    process.exit(code);
  }
  const hub = await startHub(home, () => {});
  try {
    const caller = await inProcessCaller(createCraftwireServer(hub.ctx));
    try {
      code = await runTests(opts, { call: caller.call, cwd: process.cwd(), out });
    } finally {
      await caller.close();
    }
  } catch (e) {
    fail(e);
  } finally {
    await hub.close();
  }
  process.exit(code);
}

async function serveCommand(args: string[]): Promise<void> {
  if (args.includes("--help") || args.includes("-h")) {
    process.stdout.write(`${SERVE_USAGE}\n`);
    return;
  }
  let opts;
  try {
    opts = parseServeArgs(args);
  } catch (e) {
    process.stderr.write(`craftwire serve: ${(e as Error).message}\n${SERVE_USAGE}\n`);
    process.exit(2);
  }
  try {
    await serve(opts, craftwireHome(), log);
  } catch (e) {
    process.stderr.write(`craftwire serve: ${e instanceof Error ? e.message : String(e)}\n`);
    process.exit(1);
  }
  process.exit(0);
}

async function doctor(args: string[]): Promise<void> {
  const at = args.indexOf("--server");
  const serverDir = at >= 0 ? args[at + 1] : undefined;
  const checks = await runDoctor({ home: craftwireHome(), ...(serverDir ? { serverDir: resolve(serverDir) } : {}) });
  process.stdout.write(`craftwire doctor ${HUB_VERSION}\n${formatChecks(checks)}`);
  process.exit(checks.some((c) => c.status === "fail") ? 1 : 0);
}

/** The skills: packaged next to dist/ by prepack, or the plugin's own folder in a repo checkout. */
function skillsDir(): string | undefined {
  const here = dirname(fileURLToPath(import.meta.url));
  return [join(here, "..", "skills"), join(here, "..", "..", "claude-plugin", "skills")].find((d) => existsSync(d));
}

function setup(args: string[]): void {
  process.stdout.write(setupCommand(args, setupEnvFromProcess(skillsDir())));
}

const argv = process.argv.slice(2);
const fatal = (e: unknown) => {
  log(`fatal: ${e instanceof ConfigError ? e.message : e instanceof Error ? e.stack ?? e.message : String(e)}`);
  process.exit(1);
};
if (argv[0] === "doctor") doctor(argv.slice(1)).catch(fatal);
else if (argv[0] === "test") test(argv.slice(1)).catch(fatal);
else if (argv[0] === "serve") serveCommand(argv.slice(1)).catch(fatal);
else if (argv[0] === "setup") {
  try {
    setup(argv.slice(1));
  } catch (e) {
    process.stderr.write(`craftwire setup: ${e instanceof Error ? e.message : String(e)}\n`);
    process.exit(1);
  }
} else if (argv[0] === "--version") process.stdout.write(`${HUB_VERSION}\n`);
else main().catch(fatal);
