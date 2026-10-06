import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { defineTool, forward, ok, targetArgs, type ToolContext } from "./registry.js";

const NAME = /^[A-Za-z0-9_]{3,16}$/;
const ACTIONS = [
  "chat", "command", "messages", "look", "move_to", "state", "give", "select_hotbar",
  "gui_read", "gui_click", "gui_close", "use", "attack",
] as const;

/** How long the hub waits for the agent: an action that waits on the game must not time out first. */
function actionTimeout(a: { action: string; timeoutMs?: number; collectMs?: number; settleMs?: number }): number {
  switch (a.action) {
    case "move_to": return (a.timeoutMs ?? 10_000) + 5000;
    case "command": return (a.collectMs ?? 300) + 10_000;
    case "gui_click": return (a.settleMs ?? 150) + 10_000;
    default: return 15_000;
  }
}

export function registerBotTools(server: McpServer, ctx: ToolContext): void {
  defineTool(server, ctx, "bot_spawn",
    "Spawn server-side fake players (bots) on a Paper server running the Craftwire plugin. They join like real players (join event, tab list) and plugins treat them as players. Names: `names`, or `count` × namePrefix1, namePrefix2, …. location defaults to the main world's spawn. Returns each bot's name and position.",
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
    "Remove a bot (`name`) or every bot (`all:true`). Bots leave like players logging out (quit event, data saved).",
    { ...targetArgs, name: z.string().optional(), all: z.boolean().default(false) },
    async (args, c) => ok(await forward(c, "server", "bot.remove", args.all ? { ...args, name: undefined } : args)));

  defineTool(server, ctx, "bot_action",
    "Drive a bot. chat {text}. command {command, collectMs} returns the messages the bot received. messages {since?, limit?}. look {yaw,pitch} or {x,y,z}. move_to {x,y,z, tolerance?, sprint?, timeoutMs?} walks in a straight line (jumps 1-block steps) and returns reached + reason (arrived/stuck/timeout/died). state: position, health, held item, inventory, open menu. give {item, count} (ids like diamond_sword, components allowed). select_hotbar {slot 0-8}. gui_read / gui_click {slot, click: left|right|shift} / gui_close for plugin menus (slots as in gui_read; gui_click returns the menu after the plugin reacted). use: right-click with the held item — {block:{x,y,z}, face} on a block, {entity: uuid} on an entity, or nothing for the air. attack {entity: uuid} or {type: 'zombie'} (nearest within reach). Actions go through the server's packet handlers, so plugins see the same events as from a real client.",
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
      item: z.string().optional(),
      count: z.number().int().min(1).max(2304).optional(),
      slot: z.number().int().min(0).optional(),
      click: z.enum(["left", "right", "shift"]).optional(),
      settleMs: z.number().int().min(0).max(5000).optional(),
      hand: z.enum(["main", "off"]).optional(),
      block: z.object({ x: z.number().int(), y: z.number().int(), z: z.number().int() }).optional(),
      face: z.enum(["up", "down", "north", "south", "east", "west"]).optional(),
      entity: z.string().optional(),
      type: z.string().optional(),
    },
    async (args, c) => ok(await forward(c, "server", "bot.action", args, actionTimeout(args))));
}
