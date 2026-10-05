import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import type { AgentEvent } from "../protocol.js";
import { CraftwireError } from "../errors.js";
import { defineTool, ok, type ToolContext } from "./registry.js";

const EVENT_TYPE = {
  chat_match: "chat",
  hud_match: "hud",
  screen_open: "screen",
  screen_closed: "screen",
  log_match: "log",
  player_join: "player",
} as const;
type Condition = keyof typeof EVENT_TYPE;

function matches(condition: Condition, pattern: RegExp | undefined, ev: AgentEvent): boolean {
  if (ev.type !== EVENT_TYPE[condition]) return false;
  const d = ev.data;
  if (condition === "screen_open" && d.open !== true) return false;
  if (condition === "screen_closed" && d.open !== false) return false;
  if (condition === "player_join" && d.action !== "join") return false;
  if (!pattern) return true;
  return pattern.test(String(d.text ?? d.title ?? d.message ?? d.name ?? ""));
}

export function registerHubTools(server: McpServer, ctx: ToolContext): void {
  defineTool(server, ctx, "list_instances",
    "List connected Minecraft clients and servers (id, name, versions). Call this first; other tools take `instance` from here.",
    {},
    async (_args, c) => {
      const instances = c.agents.instances();
      return ok(instances.length
        ? { instances }
        : { instances, hint: "Nothing is connected. Start Minecraft with the Craftwire Agent mod (and/or a Paper server with the Craftwire plugin); they connect to this hub automatically." });
    });

  defineTool(server, ctx, "get_request_status",
    "Status of a request sent with an operationId: unknown, running, done (with result) or failed (with error). Results are kept for 5 minutes.",
    { operationId: z.string() },
    async ({ operationId }, c) => ok(c.ops.status(operationId)));

  defineTool(server, ctx, "wait_for",
    "Block until an event happens or the timeout expires. Conditions: chat_match, hud_match (actionbar), screen_open, screen_closed, log_match, player_join. `pattern` is a case-insensitive regex tested against the event text/title. Only events arriving after the call count.",
    {
      condition: z.enum(["chat_match", "hud_match", "screen_open", "screen_closed", "log_match", "player_join"]),
      pattern: z.string().optional(),
      instance: z.string().optional().describe("Only accept events from this instance (id or name)."),
      timeoutMs: z.number().int().min(10).max(300_000).default(30_000),
    },
    async ({ condition, pattern, instance, timeoutMs }, c) => {
      let regex: RegExp | undefined;
      try {
        regex = pattern === undefined ? undefined : new RegExp(pattern, "i");
      } catch (e) {
        throw new CraftwireError("INVALID_PARAMS", `Invalid regex: ${(e as Error).message}`, "Escape special characters such as ( [ . * with a backslash.");
      }
      const only = instance
        ? c.agents.instances().find((i) => i.id === instance || i.name.toLowerCase() === instance.toLowerCase())?.id
        : undefined;
      if (instance && !only) throw new CraftwireError("NO_INSTANCE", `No instance matches "${instance}"`, "Call list_instances.");

      const hit = await new Promise<{ instanceId: string; ev: AgentEvent } | undefined>((resolve) => {
        const onEvent = (instanceId: string, ev: AgentEvent) => {
          if (only && instanceId !== only) return;
          if (!matches(condition, regex, ev)) return;
          cleanup();
          resolve({ instanceId, ev });
        };
        const timer = setTimeout(() => { cleanup(); resolve(undefined); }, timeoutMs);
        const cleanup = () => { clearTimeout(timer); c.agents.off("event", onEvent); };
        c.agents.on("event", onEvent);
      });
      if (!hit) throw new CraftwireError("TIMEOUT", `No ${condition} event within ${timeoutMs} ms`, "Check chat/hud_read/gui_read to see what actually happened, then retry or widen the pattern.");
      return ok({ matched: true, instance: hit.instanceId, event: hit.ev });
    });
}
