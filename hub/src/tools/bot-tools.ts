import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { defineTool, forward, ok, targetArgs, type ToolContext } from "./registry.js";

const NAME = /^[A-Za-z0-9_]{3,16}$/;
const ACTIONS = [
  "chat", "command", "messages", "look", "move_to", "state", "give", "select_hotbar",
  "gui_read", "gui_click", "gui_close", "use", "attack", "hud_read",
  "break_block", "jump", "sneak", "sprint", "drop", "swap_hands",
] as const;

/** How long the hub waits for the agent: an action that waits on the game must not time out first. */
function actionTimeout(a: { action: string; timeoutMs?: number; collectMs?: number; settleMs?: number }): number {
  switch (a.action) {
    case "move_to":
    case "break_block": return (a.timeoutMs ?? 30_000) + 5000;
    case "command": return (a.collectMs ?? 300) + 10_000;
    case "gui_click": return (a.settleMs ?? 150) + 10_000;
    default: return 15_000;
  }
}

export function registerBotTools(server: McpServer, ctx: ToolContext): void {
  defineTool(server, ctx, "bot_spawn",
    "Spawn fake players (bots) on a Paper server; plugins treat them as real players. names, or count x namePrefix; location defaults to spawn. Bots skip whitelist and bans; their data is deleted when they leave.",
    {
      ...targetArgs,
      count: z.number().int().min(1).max(20).default(1),
      namePrefix: z.string().regex(/^[A-Za-z0-9_]{1,13}$/).default("Bot"),
      names: z.array(z.string().regex(NAME)).min(1).max(20).optional(),
      location: z.object({
        world: z.string().optional(), x: z.number(), y: z.number(), z: z.number(),
        yaw: z.number().optional(), pitch: z.number().optional(),
      }).optional(),
    },
    async (args, c) => ok(await forward(c, "server", "bot.spawn", args, 30_000)));

  defineTool(server, ctx, "bot_remove",
    "Remove a bot (name) or every bot (all:true).",
    { ...targetArgs, name: z.string().optional(), all: z.boolean().default(false) },
    async (args, c) => ok(await forward(c, "server", "bot.remove", args.all ? { ...args, name: undefined } : args)));

  defineTool(server, ctx, "bot_action",
    "Drive a bot like a real client (plugins see the same events). command returns success + messages; move_to pathfinds and returns reached + reason; gui_click {slot,click}; inventory:true acts on the bot's own inventory. Arguments per action: craftwire://docs/tools.",
    {
      ...targetArgs,
      bot: z.string().min(1),
      action: z.enum(ACTIONS),
      text: z.string().max(256).optional(),
      command: z.string().max(32_000).optional(),
      collectMs: z.number().int().min(0).max(5000).optional(),
      since: z.number().optional(),
      limit: z.number().int().min(1).max(200).optional(),
      x: z.number().optional(), y: z.number().optional(), z: z.number().optional(),
      yaw: z.number().optional(), pitch: z.number().optional(),
      tolerance: z.number().min(0.1).max(5).optional(),
      sprint: z.boolean().optional(),
      timeoutMs: z.number().int().min(500).max(120_000).optional(),
      path: z.boolean().optional(),
      maxFall: z.number().int().min(0).max(10).optional(),
      openDoors: z.boolean().optional(),
      partial: z.boolean().optional(),
      on: z.boolean().optional(),
      all: z.boolean().optional(),
      item: z.string().optional(),
      count: z.number().int().min(1).max(2304).optional(),
      slot: z.number().int().min(0).optional(),
      click: z.enum(["left", "right", "shift"]).optional(),
      inventory: z.boolean().optional(),
      settleMs: z.number().int().min(0).max(5000).optional(),
      hand: z.enum(["main", "off"]).optional(),
      block: z.object({ x: z.number().int(), y: z.number().int(), z: z.number().int() }).optional(),
      face: z.enum(["up", "down", "north", "south", "east", "west"]).optional(),
      entity: z.string().optional(),
      type: z.string().optional(),
    },
    async (args, c) => ok(await forward(c, "server", "bot.action", args, actionTimeout(args))));
}
