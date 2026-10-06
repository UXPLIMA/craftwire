import { EventEmitter } from "node:events";
import type { McpServer, RegisteredTool } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import type { AgentServer } from "./agents.js";
import { CraftwireError } from "./errors.js";
import type { AgentEvent } from "./protocol.js";
import { defineToolWithSchema, ok, type ToolContext } from "./tools/registry.js";

/** A tool a plugin or mod added through the Craftwire API, as its agent announced it. */
export interface ExtensionToolDef {
  name: string;
  namespace: string;
  description: string;
  inputSchema: Record<string, unknown>;
}

const NAME = /^[a-z0-9_]{1,64}$/;

/**
 * The extension tools each connected agent has. Agents send their full list (`tools` event) on connect and whenever
 * it changes; a disconnect drops the instance's tools. Emits "changed".
 */
export class ExtensionRegistry extends EventEmitter {
  private readonly byInstance = new Map<string, ExtensionToolDef[]>();

  constructor(agents: AgentServer) {
    super();
    agents.on("event", (id: string, ev: AgentEvent) => {
      if (ev.type !== "tools") return;
      const list = Array.isArray(ev.data.tools) ? (ev.data.tools as unknown[]).filter(isDef) : [];
      this.byInstance.set(id, list);
      this.emit("changed");
    });
    agents.on("disconnected", (i: { id: string }) => {
      if (this.byInstance.delete(i.id)) this.emit("changed");
    });
  }

  /** Every tool by name (the first announcement wins when instances disagree on a tool's shape). */
  tools(): Map<string, ExtensionToolDef> {
    const out = new Map<string, ExtensionToolDef>();
    for (const list of this.byInstance.values()) for (const t of list) if (!out.has(t.name)) out.set(t.name, t);
    return out;
  }

  instancesWith(name: string): string[] {
    return [...this.byInstance.entries()].filter(([, list]) => list.some((t) => t.name === name)).map(([id]) => id);
  }

  namesFor(instanceId: string): string[] {
    return (this.byInstance.get(instanceId) ?? []).map((t) => t.name);
  }
}

function isDef(v: unknown): v is ExtensionToolDef {
  const t = v as ExtensionToolDef;
  return typeof t === "object" && t !== null && typeof t.name === "string" && NAME.test(t.name) && typeof t.description === "string"
    && typeof t.namespace === "string" && typeof t.inputSchema === "object" && t.inputSchema !== null;
}

const INSTANCE = z.string().optional().describe("Instance id or name from list_instances; needed when several instances have this tool.");

/** The arguments of an extension tool: its own JSON Schema plus `instance`; anything else when it cannot be converted. */
function schemaOf(def: ExtensionToolDef): { schema: z.ZodObject; converted: boolean } {
  try {
    const schema = z.fromJSONSchema(def.inputSchema as Parameters<typeof z.fromJSONSchema>[0]);
    if (schema instanceof z.ZodObject) return { schema: schema.extend({ instance: INSTANCE }), converted: true };
  } catch {
    // Fall through: features the converter does not support.
  }
  return { schema: z.looseObject({ instance: INSTANCE }), converted: false };
}

/**
 * Keeps one MCP server's tool list in step with the extension tools: registers new ones, removes gone ones (the SDK
 * then tells the client the list changed). Built-in tools are never replaced.
 */
export function bindExtensionTools(server: McpServer, ctx: ToolContext): void {
  const registered = new Map<string, { tool: RegisteredTool; key: string }>();
  let pending: NodeJS.Timeout | undefined;

  const sync = () => {
    pending = undefined;
    const want = ctx.extensions.tools();
    for (const [name, r] of registered) {
      const def = want.get(name);
      if (!def || JSON.stringify(def) !== r.key) {
        r.tool.remove();
        registered.delete(name);
      }
    }
    for (const [name, def] of want) {
      if (registered.has(name)) continue;
      const tool = register(server, ctx, def);
      if (tool) registered.set(name, { tool, key: JSON.stringify(def) });
    }
  };
  // Agents announce tools one plugin at a time while starting: apply a burst of changes at once.
  const onChanged = () => { pending ??= setTimeout(sync, 25); };

  sync();
  ctx.extensions.on("changed", onChanged);
  const previous = server.server.onclose;
  server.server.onclose = () => {
    ctx.extensions.off("changed", onChanged);
    if (pending) clearTimeout(pending);
    previous?.();
  };
}

function register(server: McpServer, ctx: ToolContext, def: ExtensionToolDef): RegisteredTool | undefined {
  const { schema, converted } = schemaOf(def);
  const description = `${def.description}\n(Added by ${def.namespace} through the Craftwire API.)`
    + (converted ? "" : `\nArguments (JSON Schema): ${JSON.stringify(def.inputSchema)}`);
  try {
    return defineToolWithSchema(server, ctx, def.name, description, schema, async (args, c) => {
      const { instance, ...rest } = args as { instance?: string } & Record<string, unknown>;
      const id = pick(c, def.name, instance);
      return ok(await c.agents.request(id, "ext.call", { tool: def.name, args: rest }));
    });
  } catch {
    // The name is taken by a built-in tool (the SDK refuses duplicates): the built-in stays.
    return undefined;
  }
}

function pick(c: ToolContext, name: string, instance: string | undefined): string {
  const ids = c.extensions.instancesWith(name);
  const all = c.agents.instances();
  const label = (id: string) => `${id} (${all.find((i) => i.id === id)?.name ?? "?"})`;
  if (instance !== undefined) {
    const hit = all.find((i) => i.id === instance || i.name.toLowerCase() === instance.toLowerCase());
    if (!hit || !ids.includes(hit.id)) {
      throw new CraftwireError("NO_INSTANCE", `No instance "${instance}" has ${name}`, `Instances with it: ${ids.map(label).join(", ") || "none"}.`);
    }
    return hit.id;
  }
  if (ids.length === 0) throw new CraftwireError("EXTENSION_NOT_FOUND", `${name} is gone: the plugin or mod that added it was disabled`, "Call list_instances to see the tools each instance has.");
  if (ids.length > 1) throw new CraftwireError("AMBIGUOUS_INSTANCE", `${ids.length} instances have ${name}`, `Pass instance: one of ${ids.map(label).join(", ")}.`);
  return ids[0]!;
}
