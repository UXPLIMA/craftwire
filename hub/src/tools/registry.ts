import type { McpServer, RegisteredTool } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";
import type { AgentKind, AgentServer } from "../agents.js";
import type { AuditLog } from "../audit.js";
import type { BedrockClient } from "../bedrock-client/bedrock-client.js";
import type { RunnerManager } from "../botrunner/runner-manager.js";
import type { ClientManager } from "../client/client-manager.js";
import type { ServerManager } from "../dev/server-manager.js";
import { CraftwireError, toToolError } from "../errors.js";
import type { ExceptionTracker } from "../exceptions.js";
import type { ExtensionRegistry } from "../extensions.js";
import type { OperationTracker } from "../operations.js";
import { EXTRAS, type ToolPolicy } from "../tool-policy.js";

export interface ToolContext {
  agents: AgentServer;
  ops: OperationTracker;
  audit: AuditLog;
  servers: ServerManager;
  clients: ClientManager;
  /** Bot runners started on this machine (botrunner_process); absent where the hub must not start processes. */
  runners?: RunnerManager;
  /** The user's own Minecraft for Windows (bedrock_client); absent where the hub must not drive this machine. */
  bedrockClient?: BedrockClient;
  exceptions: ExceptionTracker;
  extensions: ExtensionRegistry;
  /** Limits the tools offered and the calls allowed (craftwire serve --read-only / --tools, or the person's role). */
  policy?: ToolPolicy;
  /** Who this MCP session belongs to (the person behind the token) and its id, for the audit log. */
  user?: string;
  session?: string;
  /** build files: over HTTP (craftwire serve) they are read only from --build-dir. */
  build?: { dir?: string; http: boolean };
  /** CRAFTWIRE_HOME, for caches such as the block colour tables; unset in tests that must not download. */
  home?: string;
  log?: (msg: string) => void;
}

export const targetArgs = {
  // Explained once in the server instructions, not in every tool.
  instance: z.string().optional(),
  operationId: z.string().optional(),
};

export function ok(data: unknown): CallToolResult {
  return { content: [{ type: "text", text: JSON.stringify(data) }] };
}

export function defineTool<S extends z.ZodRawShape>(
  server: McpServer,
  ctx: ToolContext,
  name: string,
  description: string,
  shape: S,
  run: (args: z.infer<z.ZodObject<S>>, ctx: ToolContext) => Promise<CallToolResult>,
): RegisteredTool | undefined {
  return registerAudited(server, ctx, name, description, shape, run as (args: unknown, ctx: ToolContext) => Promise<CallToolResult>);
}

/**
 * Like defineTool, with fields that are checked but not listed: rarely used options (a sign's lines, a beacon's
 * effects, a window size) cost every session tool-definition tokens, so they are documented in craftwire://docs/tools
 * instead. A wrong value still fails with INVALID_PARAMS naming the field.
 */
export function defineToolWithUnlisted<S extends z.ZodRawShape, U extends z.ZodRawShape>(
  server: McpServer,
  ctx: ToolContext,
  name: string,
  description: string,
  listed: S,
  unlisted: U,
  run: (args: z.infer<z.ZodObject<S>> & z.infer<z.ZodObject<U>>, ctx: ToolContext) => Promise<CallToolResult>,
): RegisteredTool | undefined {
  const hidden = z.object(unlisted);
  return defineToolWithSchema(server, ctx, name, description, z.looseObject(listed), async (args, c) => {
    const r = hidden.safeParse(args);
    if (!r.success) {
      const what = r.error.issues.map((i) => `${i.path.join(".")}: ${i.message}`).join("; ");
      throw new CraftwireError("INVALID_PARAMS", `${name}: ${what}`, "The fields are described in craftwire://docs/tools.");
    }
    return run({ ...args, ...r.data } as never, c);
  });
}

/** Like defineTool, for a schema built at runtime (e.g. from a plugin's JSON Schema); it may keep unknown keys. */
export function defineToolWithSchema(
  server: McpServer,
  ctx: ToolContext,
  name: string,
  description: string,
  schema: z.ZodObject,
  run: (args: Record<string, unknown>, ctx: ToolContext) => Promise<CallToolResult>,
): RegisteredTool | undefined {
  return registerAudited(server, ctx, name, description, schema, run as (args: unknown, ctx: ToolContext) => Promise<CallToolResult>);
}

const switchedOff = new WeakMap<McpServer, Map<string, RegisteredTool>>();

/** The extras this server registered switched off, for more_tools to turn on. */
export function extrasOff(server: McpServer): Map<string, RegisteredTool> {
  let m = switchedOff.get(server);
  if (m === undefined) switchedOff.set(server, (m = new Map()));
  return m;
}

function registerAudited(
  server: McpServer,
  ctx: ToolContext,
  name: string,
  description: string,
  inputSchema: z.ZodRawShape | z.ZodObject,
  run: (args: unknown, ctx: ToolContext) => Promise<CallToolResult>,
): RegisteredTool | undefined {
  if (ctx.policy && !ctx.policy.lists(name)) return undefined;
  const handler = async (args: unknown): Promise<CallToolResult> => {
    const started = Date.now();
    try {
      const refused = ctx.policy?.refusal(name, (args ?? {}) as Record<string, unknown>);
      if (refused !== undefined) throw new CraftwireError("READ_ONLY", refused, "Ask the person running the hub; it was started with --read-only.");
      const result = await run(args, ctx);
      ctx.audit.write({ tool: name, args, ok: !result.isError, ms: Date.now() - started, ...who(ctx), result });
      return result;
    } catch (e) {
      const error = e instanceof CraftwireError ? e.toJSON() : String(e);
      ctx.audit.write({ tool: name, args, ok: false, ms: Date.now() - started, ...who(ctx), error });
      return toToolError(e);
    }
  };
  // The SDK's generic overloads do not infer through our wrapper; the shape is still validated by the SDK.
  const tool = server.registerTool(name, { description, inputSchema: inputSchema as never }, handler as never);
  if (EXTRAS.has(name) && !(ctx.policy?.showsExtras() ?? false)) {
    tool.disable();
    extrasOff(server).set(name, tool);
  }
  return tool;
}

function who(ctx: ToolContext): { user?: string; session?: string } {
  return { ...(ctx.user !== undefined ? { user: ctx.user } : {}), ...(ctx.session !== undefined ? { session: ctx.session } : {}) };
}

/** Routes a tool call to one agent of `kind`; `instance` picks it, `operationId` makes retries idempotent. */
export async function forward(c: ToolContext, kind: AgentKind, method: string, args: Record<string, unknown>, timeoutMs?: number): Promise<unknown> {
  const { instance, operationId, ...params } = args as { instance?: string; operationId?: string } & Record<string, unknown>;
  const inst = c.agents.resolve(kind, instance);
  const payload = operationId ? { ...params, operationId } : params;
  return c.ops.run(operationId, () => c.agents.request(inst.id, method, payload, timeoutMs));
}
