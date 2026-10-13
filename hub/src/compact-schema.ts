import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { Tool } from "@modelcontextprotocol/sdk/types.js";

/**
 * Validation-only keywords: the hub still checks every call against the full zod schema (an out-of-range value is
 * refused with the bound in the message), so listing them would only cost tokens in every session.
 */
const VALIDATION_ONLY = new Set(["$schema", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "minLength", "maxLength", "minItems", "maxItems", "pattern"]);

/** The JSON Schema a client sees: types, enums, required, defaults and descriptions; no validation-only keywords. */
export function compactSchema(node: unknown): unknown {
  if (Array.isArray(node)) return node.map(compactSchema);
  if (node === null || typeof node !== "object") return node;
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(node)) {
    if (VALIDATION_ONLY.has(k) && typeof v !== "object") continue;
    out[k] = compactSchema(v);
  }
  return out;
}

type Handler = (request: unknown, extra: unknown) => Promise<unknown>;

/**
 * Rewrites the SDK's tools/list answer through compactSchema. The SDK has no hook for this, so it wraps the handler
 * in the protocol's handler map; tool-budget.test.ts fails if that ever stops working.
 */
export function compactToolList(server: McpServer): void {
  const handlers = (server.server as unknown as { _requestHandlers: Map<string, Handler> })._requestHandlers;
  const original = handlers.get("tools/list");
  if (original === undefined) return;
  handlers.set("tools/list", async (request, extra) => {
    const r = (await original(request, extra)) as { tools: Tool[] };
    return { ...r, tools: r.tools.map((t) => ({ ...t, inputSchema: compactSchema(t.inputSchema) as Tool["inputSchema"] })) };
  });
}
