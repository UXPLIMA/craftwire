import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { AgentServer } from "../../src/agents.js";
import { AuditLog } from "../../src/audit.js";
import { OperationTracker } from "../../src/operations.js";
import { createCraftwireServer } from "../../src/server.js";

export const TOKEN = "a".repeat(64);

export async function startHub() {
  const home = mkdtempSync(join(tmpdir(), "cw-hub-"));
  const agents = new AgentServer({ token: TOKEN, port: 0, requestTimeoutMs: 2000 });
  const port = await agents.listen();
  const server = createCraftwireServer({ agents, ops: new OperationTracker(), audit: new AuditLog(join(home, "logs")) });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  await server.connect(serverTransport);
  const client = new Client({ name: "test", version: "0.0.0" });
  await client.connect(clientTransport);
  const call = (name: string, args: Record<string, unknown> = {}) =>
    client.callTool({ name, arguments: args }) as Promise<CallToolResult>;
  return {
    agents, port, token: TOKEN, home, client, call,
    close: async () => { await client.close(); await server.close(); await agents.close(); },
  };
}

export const json = (r: CallToolResult) => JSON.parse((r.content[0] as { text: string }).text);
