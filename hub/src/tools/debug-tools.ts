import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { defineTool, forward, ok, targetArgs, type ToolContext } from "./registry.js";

const instanceArg = {
  instance: z.string().optional(),
  operationId: z.string().optional(),
};

/** Sends a request to any instance (server or client) and puts the instance id first in the result. */
async function forwardAny(c: ToolContext, method: string, args: Record<string, unknown>, timeoutMs: number): Promise<unknown> {
  const { instance, operationId, ...params } = args as { instance?: string; operationId?: string } & Record<string, unknown>;
  const inst = c.agents.resolveAny(instance);
  const result = await c.ops.run(operationId, () => c.agents.request(inst.id, method, params, timeoutMs));
  return { instance: inst.id, ...(result as object) };
}

export function registerDebugTools(server: McpServer, ctx: ToolContext): void {
  defineTool(server, ctx, "profile",
    "Sample a server tick or client frame for durationMs and show who uses the time: owners (plugin or mod), entryPoints (listener, task), hotMethods; server tick stats or client fps. Run it while the slow thing happens, then trace.",
    {
      ...instanceArg,
      durationMs: z.number().int().min(1000).max(60_000).default(10_000),
      intervalMs: z.number().int().min(5).max(50).optional(),
      top: z.number().int().min(1).max(50).optional(),
    },
    async (args, c) => ok(await forwardAny(c, "profile.run", args, args.durationMs + 15_000)));

  defineTool(server, ctx, "trace",
    "Time every call of methods for durationMs without changing code: method = Class::method, Class or @Annotation (';' for several). Returns counts, avg/max ms, slowest calls with stacks, callers.",
    {
      ...instanceArg,
      method: z.string().min(1).max(1000),
      durationMs: z.number().int().min(1000).max(60_000).default(10_000),
      minMs: z.number().min(0).max(60_000).optional(),
      stackDepth: z.number().int().min(1).max(32).optional(),
      limit: z.number().int().min(1).max(200).optional(),
    },
    async (args, c) => ok(await forwardAny(c, "trace.run", args, args.durationMs + 15_000)));

  defineTool(server, ctx, "client_eval",
    "Run JavaScript on a client's render thread: mc, player, level, screen(), print, Java.type. The first call downloads GraalJS (~60 MB). Keep scripts short: the game does not render meanwhile.",
    {
      ...targetArgs,
      code: z.string().min(1).max(100_000),
      timeoutMs: z.number().int().min(100).max(60_000).default(5000),
      reset: z.boolean().default(false),
    },
    // The first call downloads GraalJS: allow for that on top of the script's own time.
    async (args, c) => ok(await forward(c, "client", "client.eval", args, args.timeoutMs + 300_000)));
}
