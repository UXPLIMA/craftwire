import type { McpServer, RegisteredTool } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";
import type { AgentKind, AgentServer } from "../agents.js";
import type { AuditLog } from "../audit.js";
import type { ClientManager } from "../client/client-manager.js";
import type { ServerManager } from "../dev/server-manager.js";
import { CraftwireError, toToolError } from "../errors.js";
import type { ExceptionTracker } from "../exceptions.js";
import type { ExtensionRegistry } from "../extensions.js";
import type { OperationTracker } from "../operations.js";

export interface ToolContext {
  agents: AgentServer;
  ops: OperationTracker;
  audit: AuditLog;
  servers: ServerManager;
  clients: ClientManager;
  exceptions: ExceptionTracker;
  extensions: ExtensionRegistry;
}

export const targetArgs = {
  instance: z.string().optional().describe("Instance id or name from list_instances. Optional when exactly one matching instance is connected."),
  operationId: z.string().optional().describe("Idempotency key. Retrying with the same id returns the first result instead of executing again."),
};

export function ok(data: unknown): CallToolResult {
  return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
}

export function defineTool<S extends z.ZodRawShape>(
  server: McpServer,
  ctx: ToolContext,
  name: string,
  description: string,
  shape: S,
  run: (args: z.infer<z.ZodObject<S>>, ctx: ToolContext) => Promise<CallToolResult>,
): RegisteredTool {
  return registerAudited(server, ctx, name, description, shape, run as (args: unknown, ctx: ToolContext) => Promise<CallToolResult>);
}

/** Like defineTool, for a schema built at runtime (e.g. from a plugin's JSON Schema); it may keep unknown keys. */
export function defineToolWithSchema(
  server: McpServer,
  ctx: ToolContext,
  name: string,
  description: string,
  schema: z.ZodObject,
  run: (args: Record<string, unknown>, ctx: ToolContext) => Promise<CallToolResult>,
): RegisteredTool {
  return registerAudited(server, ctx, name, description, schema, run as (args: unknown, ctx: ToolContext) => Promise<CallToolResult>);
}

function registerAudited(
  server: McpServer,
  ctx: ToolContext,
  name: string,
  description: string,
  inputSchema: z.ZodRawShape | z.ZodObject,
  run: (args: unknown, ctx: ToolContext) => Promise<CallToolResult>,
): RegisteredTool {
  const handler = async (args: unknown): Promise<CallToolResult> => {
    const started = Date.now();
    try {
      const result = await run(args, ctx);
      ctx.audit.write({ tool: name, args, ok: !result.isError, ms: Date.now() - started });
      return result;
    } catch (e) {
      ctx.audit.write({ tool: name, args, ok: false, ms: Date.now() - started, error: e instanceof CraftwireError ? e.toJSON() : String(e) });
      return toToolError(e);
    }
  };
  // The SDK's generic overloads do not infer through our wrapper; the shape is still validated by the SDK.
  return server.registerTool(name, { description, inputSchema: inputSchema as never }, handler as never);
}

/** Routes a tool call to one agent of `kind`; `instance` picks it, `operationId` makes retries idempotent. */
export async function forward(c: ToolContext, kind: AgentKind, method: string, args: Record<string, unknown>, timeoutMs?: number): Promise<unknown> {
  const { instance, operationId, ...params } = args as { instance?: string; operationId?: string } & Record<string, unknown>;
  const inst = c.agents.resolve(kind, instance);
  const payload = operationId ? { ...params, operationId } : params;
  return c.ops.run(operationId, () => c.agents.request(inst.id, method, payload, timeoutMs));
}
