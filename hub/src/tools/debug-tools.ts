import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { defineTool, ok, type ToolContext } from "./registry.js";

const instanceArg = {
  instance: z.string().optional().describe("Instance id or name. Defaults to the only server, else the only instance."),
  operationId: z.string().optional().describe("Idempotency key. Retrying with the same id returns the first result instead of executing again."),
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
    "Find what uses a server's tick or a client's frame time, and whose code it is. Samples the game thread (Paper: Server thread; client: Render thread) every intervalMs for durationMs, then returns: busyPercent (how much of the time the thread was working rather than waiting for the next tick/frame); owners — who the time belongs to: a plugin or mod by name (its own code and what it called, e.g. a listener's world lookups), or minecraft / server (Paper, CraftBukkit) / fabric-loader / craftwire / java / library; entryPoints — where the server or game called into each plugin or mod (event: the event a listener handles, task: a scheduler task, calledFrom: the caller), so you know which listener or task to look at; hotMethods — the methods the thread was executing (self time). On a server also ticks: count, msptAvg, p50, p95, max, over50ms and the slowest ticks with their owners; on a client fps {avg, min}. Mixin handlers count for the mod that injected them. Run it while the slow thing happens (start the scenario or bot action first, or ask the user to reproduce it), then trace a hot method for exact call counts and times.",
    {
      ...instanceArg,
      durationMs: z.number().int().min(1000).max(60_000).default(10_000).describe("How long to sample."),
      intervalMs: z.number().int().min(5).max(50).optional().describe("Sampling interval (default 10)."),
      top: z.number().int().min(1).max(50).optional().describe("Entries per list (default 15)."),
    },
    async (args, c) => ok(await forwardAny(c, "profile.run", args, args.durationMs + 15_000)));

  defineTool(server, ctx, "trace",
    "Time every call of specific methods on a server or client for durationMs, without changing any code: the JVM instruments the named methods (Flight Recorder method tracing) only while tracing. method: com.example.Shop::buy (one method), com.example.Shop (every method of the class), @com.example.Timed (methods with that annotation); nested classes as Outer$Inner; several separated by ';'. Returns methods (invocations, avgMs, maxMs, totalMs per method), slowest (the slowest calls with thread and stack, each frame with its owner), callers (who called them, with counts) and calls. minMs keeps only calls at least that slow. A trace stops early after 100000 calls (truncated: true). Make the method run while tracing (a command, a bot action). Typical use: profile → a hot method or a plugin's entry point → trace it.",
    {
      ...instanceArg,
      method: z.string().min(1).max(1000),
      durationMs: z.number().int().min(1000).max(60_000).default(10_000),
      minMs: z.number().min(0).max(60_000).optional().describe("Only report calls at least this slow (counts still include every call)."),
      stackDepth: z.number().int().min(1).max(32).optional().describe("Frames per slow call (default 8)."),
      limit: z.number().int().min(1).max(200).optional().describe("Slowest calls to return (default 50)."),
    },
    async (args, c) => ok(await forwardAny(c, "trace.run", args, args.durationMs + 15_000)));
}
