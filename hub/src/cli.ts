#!/usr/bin/env node
import { join, resolve } from "node:path";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { AgentServer } from "./agents.js";
import { AuditLog } from "./audit.js";
import { ConfigError, craftwireHome, hubPort, loadOrCreateToken, writeHubConfig } from "./config.js";
import { ServerManager } from "./dev/server-manager.js";
import { formatChecks, runDoctor } from "./doctor.js";
import { OperationTracker } from "./operations.js";
import { createCraftwireServer } from "./server.js";
import { HUB_VERSION } from "./version.js";

const log = (msg: string) => process.stderr.write(`[craftwire] ${msg}\n`);

async function main(): Promise<void> {
  const home = craftwireHome();
  const token = loadOrCreateToken(home);
  const agents = new AgentServer({ token, port: hubPort() });
  const port = await agents.listen();
  writeHubConfig({ port, token }, home);
  agents.on("connected", (i) => log(`${i.id} connected (${i.name}, Minecraft ${i.mcVersion}, agent ${i.agentVersion})`));
  agents.on("disconnected", (i) => log(`${i.id} disconnected`));

  const servers = new ServerManager({ agents, home });
  const server = createCraftwireServer({ agents, ops: new OperationTracker(), audit: new AuditLog(join(home, "logs")), servers });
  const transport = new StdioServerTransport();
  let stopping = false;
  const shutdown = () => {
    if (stopping) return;
    stopping = true;
    // Servers started by server_process get a graceful stop so their worlds are saved.
    void servers.shutdown().finally(() => agents.close()).finally(() => process.exit(0));
  };
  transport.onclose = shutdown;
  // StdioServerTransport does not report stdin EOF; without this the hub outlives Claude Code and keeps its port.
  process.stdin.once("end", shutdown);
  process.stdin.once("close", shutdown);
  process.once("SIGINT", shutdown);
  process.once("SIGTERM", shutdown);
  await server.connect(transport);
  log(`hub ${HUB_VERSION} listening on 127.0.0.1:${port} (state: ${home})`);
}

async function doctor(args: string[]): Promise<void> {
  const at = args.indexOf("--server");
  const serverDir = at >= 0 ? args[at + 1] : undefined;
  const checks = await runDoctor({ home: craftwireHome(), ...(serverDir ? { serverDir: resolve(serverDir) } : {}) });
  process.stdout.write(`craftwire doctor ${HUB_VERSION}\n${formatChecks(checks)}`);
  process.exit(checks.some((c) => c.status === "fail") ? 1 : 0);
}

const argv = process.argv.slice(2);
const fatal = (e: unknown) => {
  log(`fatal: ${e instanceof ConfigError ? e.message : e instanceof Error ? e.stack ?? e.message : String(e)}`);
  process.exit(1);
};
if (argv[0] === "doctor") doctor(argv.slice(1)).catch(fatal);
else if (argv[0] === "--version") process.stdout.write(`${HUB_VERSION}\n`);
else main().catch(fatal);
