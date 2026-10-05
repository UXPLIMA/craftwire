#!/usr/bin/env node
import { join } from "node:path";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { AgentServer } from "./agents.js";
import { AuditLog } from "./audit.js";
import { craftwireHome, loadOrCreateToken, writeHubConfig } from "./config.js";
import { OperationTracker } from "./operations.js";
import { createCraftwireServer } from "./server.js";
import { HUB_VERSION } from "./version.js";

const log = (msg: string) => process.stderr.write(`[craftwire] ${msg}\n`);

async function main(): Promise<void> {
  const home = craftwireHome();
  const token = loadOrCreateToken(home);
  const agents = new AgentServer({ token, port: Number(process.env.CRAFTWIRE_PORT ?? 47821) });
  const port = await agents.listen();
  writeHubConfig({ port, token }, home);
  agents.on("connected", (i) => log(`${i.id} connected (${i.name}, Minecraft ${i.mcVersion}, agent ${i.agentVersion})`));
  agents.on("disconnected", (i) => log(`${i.id} disconnected`));

  const server = createCraftwireServer({ agents, ops: new OperationTracker(), audit: new AuditLog(join(home, "logs")) });
  const transport = new StdioServerTransport();
  let stopping = false;
  const shutdown = () => {
    if (stopping) return;
    stopping = true;
    void agents.close().finally(() => process.exit(0));
  };
  transport.onclose = shutdown;
  // StdioServerTransport does not report stdin EOF; without this the hub outlives Claude Code and keeps its port.
  process.stdin.once("end", shutdown);
  process.stdin.once("close", shutdown);
  await server.connect(transport);
  log(`hub ${HUB_VERSION} listening on 127.0.0.1:${port} (state: ${home})`);
}

main().catch((e: unknown) => {
  log(`fatal: ${e instanceof Error ? e.stack ?? e.message : String(e)}`);
  process.exit(1);
});
