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
    "Block until something happens or the timeout expires; only what happens after the call counts. " +
    "Events from any instance: chat_match, hud_match (actionbar), screen_open, screen_closed, log_match, player_join (`pattern` is a case-insensitive regex on the event text). " +
    "Checked on the Paper server every tick: block {x,y,z,world?, is|isNot: id or state like oak_door[open=true]; properties left out match anything}, " +
    "player_near {player, x,y,z, radius=1.5} (bots too), inventory {player, item, count=1 (at least) | atMost}, " +
    "event {type: Bukkit event (also a plugin's own), player?, pattern?: regex on the event's JSON e.g. \"cancelled\":true}, " +
    "message {player: bot, pattern} (chat, system line or action bar the bot receives), expr {js: server_eval script that must become truthy}. " +
    "Returns which condition held, the value that met it and elapsedMs; a server condition's TIMEOUT carries the last value seen.",
    {
      condition: z.enum(["chat_match", "hud_match", "screen_open", "screen_closed", "log_match", "player_join", ...SERVER_CONDITIONS]),
      pattern: z.string().optional(),
      instance: z.string().optional().describe("Only accept events from this instance (id or name); for server conditions, the server to check."),
      timeoutMs: z.number().int().min(10).max(300_000).default(30_000),
      x: z.number().optional(), y: z.number().optional(), z: z.number().optional(),
      world: z.string().optional(),
      is: z.string().optional(), isNot: z.string().optional(),
      player: z.string().optional(),
      radius: z.number().positive().optional(),
      item: z.string().optional(),
      count: z.number().int().min(1).optional(), atMost: z.number().int().min(0).optional(),
      type: z.string().optional(),
      js: z.string().optional(),
    },
    async (args, c) => {
      const { condition, pattern, instance, timeoutMs } = args;
      let regex: RegExp | undefined;
      try {
        regex = pattern === undefined ? undefined : new RegExp(pattern, "i");
      } catch (e) {
        throw new CraftwireError("INVALID_PARAMS", `Invalid regex: ${(e as Error).message}`, "Escape special characters such as ( [ . * with a backslash.");
      }
      if (isServerCondition(condition)) return ok(await waitOnServer(c, condition, args));

      const only = instance
        ? c.agents.instances().find((i) => i.id === instance || i.name.toLowerCase() === instance.toLowerCase())?.id
        : undefined;
      if (instance && !only) throw new CraftwireError("NO_INSTANCE", `No instance matches "${instance}"`, "Call list_instances.");

      const started = Date.now();
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
      return ok({ matched: true, instance: hit.instanceId, condition, elapsedMs: Date.now() - started, event: hit.ev });
    });
}

const SERVER_CONDITIONS = ["block", "player_near", "inventory", "event", "message", "expr"] as const;
type ServerCondition = (typeof SERVER_CONDITIONS)[number];
const isServerCondition = (c: string): c is ServerCondition => (SERVER_CONDITIONS as readonly string[]).includes(c);

/** The fields each server condition needs; checked here so a mistake fails at once instead of after the timeout. */
const REQUIRED: Record<ServerCondition, string[]> = {
  block: ["x", "y", "z"],
  player_near: ["player", "x", "y", "z"],
  inventory: ["player", "item"],
  event: ["type"],
  message: ["player", "pattern"],
  expr: ["js"],
};

async function waitOnServer(c: ToolContext, condition: ServerCondition, args: Record<string, unknown>): Promise<unknown> {
  const missing = REQUIRED[condition].filter((k) => args[k] === undefined);
  if (missing.length > 0) {
    throw new CraftwireError("INVALID_PARAMS", `${condition} needs ${REQUIRED[condition].join(", ")}; missing ${missing.join(", ")}`, "See the wait_for description for each condition's fields.");
  }
  if (condition === "block" && (args.is === undefined) === (args.isNot === undefined)) {
    throw new CraftwireError("INVALID_PARAMS", "block needs exactly one of is or isNot", "is: \"stone\" waits until the block is stone; isNot: \"stone\" until it is anything else.");
  }
  const { instance, ...params } = args as { instance?: string } & Record<string, unknown>;
  const inst = c.agents.resolve("server", instance);
  const timeoutMs = params.timeoutMs as number;
  const r = await c.agents.request(inst.id, "wait", params, timeoutMs + 10_000) as { matched: boolean; last?: unknown; elapsedMs?: number };
  if (!r.matched) {
    throw new CraftwireError("TIMEOUT", `${condition} did not hold within ${timeoutMs} ms`, "details.last is what was seen last; check the coordinates, names or pattern, or wait longer.",
      { last: r.last ?? null, elapsedMs: r.elapsedMs });
  }
  return { instance: inst.id, ...r };
}
