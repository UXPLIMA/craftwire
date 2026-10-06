import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import type { ToolCaller } from "./runner.js";

/** Long enough for any single tool (plugin_deploy builds, wait_for up to 5 minutes). */
const CALL_TIMEOUT_MS = 30 * 60_000;

/**
 * Calls Craftwire tools in this process through a private MCP server built on the same hub state, so a step
 * goes through exactly the validation, defaults and audit log an AI client's call would.
 */
export async function inProcessCaller(server: McpServer): Promise<{ call: ToolCaller; close: () => Promise<void> }> {
  const [clientSide, serverSide] = InMemoryTransport.createLinkedPair();
  await server.connect(serverSide);
  const client = new Client({ name: "craftwire-scenario", version: "1" });
  await client.connect(clientSide);
  return { call: callerFor(client), close: async () => { await client.close(); await server.close(); } };
}

/** Calls the tools of a hub started with `craftwire serve`, over HTTP. */
export async function httpCaller(url: string, token: string): Promise<{ call: ToolCaller; close: () => Promise<void> }> {
  const client = new Client({ name: "craftwire-test", version: "1" });
  await client.connect(new StreamableHTTPClientTransport(new URL(url), { requestInit: { headers: { authorization: `Bearer ${token}` } } }));
  return { call: callerFor(client), close: () => client.close() };
}

function callerFor(client: Client): ToolCaller {
  return async (tool, args) => {
    let r: CallToolResult;
    try {
      r = (await client.callTool({ name: tool, arguments: args }, undefined, { timeout: CALL_TIMEOUT_MS })) as CallToolResult;
    } catch (e) {
      return { ok: false, data: { code: "TOOL_CALL_FAILED", message: e instanceof Error ? e.message : String(e) } };
    }
    const text = r.content.find((c) => c.type === "text")?.text ?? "";
    let data: unknown = text;
    try {
      data = JSON.parse(text);
    } catch {
      // Not JSON (e.g. the SDK's own "Tool x not found"): keep it as a message.
      if (r.isError) data = { code: "TOOL_ERROR", message: text };
    }
    return { ok: !r.isError, data };
  };
}
