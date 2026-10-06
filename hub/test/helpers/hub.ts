import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { trackExceptions } from "../../src/exceptions.js";
import { ExtensionRegistry } from "../../src/extensions.js";
import { AgentServer } from "../../src/agents.js";
import { AuditLog } from "../../src/audit.js";
import { ClientManager, type PrepareClient } from "../../src/client/client-manager.js";
import { writeHubConfig } from "../../src/config.js";
import { ServerManager } from "../../src/dev/server-manager.js";
import { OperationTracker } from "../../src/operations.js";
import type { ToolPolicy } from "../../src/tool-policy.js";
import { createCraftwireServer } from "../../src/server.js";

export const TOKEN = "a".repeat(64);

export async function startHub(opts: { writeHubJson?: boolean; javaMajor?: number; agentWaitMs?: number; stopTimeoutMs?: number; prepareClient?: PrepareClient; clientRoot?: string; clientStopTimeoutMs?: number; policy?: ToolPolicy } = {}) {
  const home = mkdtempSync(join(tmpdir(), "cw-hub-"));
  const agents = new AgentServer({ token: TOKEN, port: 0, requestTimeoutMs: 2000 });
  const port = await agents.listen();
  if (opts.writeHubJson) writeHubConfig({ port, token: TOKEN }, home);
  const servers = new ServerManager({
    agents, home,
    ...(opts.javaMajor !== undefined ? { javaMajor: async () => opts.javaMajor! } : {}),
    agentWaitMs: opts.agentWaitMs ?? 2000,
    stopTimeoutMs: opts.stopTimeoutMs ?? 10_000,
  });
  const noClient: PrepareClient = async () => { throw new Error("no client in this test"); };
  const clients = new ClientManager({
    agents, home, servers, prepare: opts.prepareClient ?? noClient, ...(opts.clientRoot ? { root: opts.clientRoot } : {}),
    stopTimeoutMs: opts.clientStopTimeoutMs ?? 2000, quitTimeoutMs: 500, readyPollMs: 50,
  });
  const ctx = { agents, ops: new OperationTracker(), audit: new AuditLog(join(home, "logs")), servers, clients, exceptions: trackExceptions(agents), extensions: new ExtensionRegistry(agents), ...(opts.policy ? { policy: opts.policy } : {}) };
  const server = createCraftwireServer(ctx);
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  await server.connect(serverTransport);
  const client = new Client({ name: "test", version: "0.0.0" });
  await client.connect(clientTransport);
  // The tools have their own timeouts (timeoutMs); the SDK's 60 s default would give up on a slow world creation first.
  const call = (name: string, args: Record<string, unknown> = {}) =>
    client.callTool({ name, arguments: args }, undefined, { timeout: 15 * 60_000 }) as Promise<CallToolResult>;
  return {
    agents, servers, clients, ctx, port, token: TOKEN, home, client, call,
    close: async () => { await clients.shutdown(); await servers.shutdown(); await client.close(); await server.close(); await agents.close(); },
  };
}

export const json = (r: CallToolResult) => JSON.parse((r.content[0] as { text: string }).text);
