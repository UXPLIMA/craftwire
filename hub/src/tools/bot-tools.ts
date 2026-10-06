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
    "Spawn server-side fake players (bots) on a Paper server running the Craftwire plugin. They join like real players (join event, tab list) and plugins treat them as players. Names: `names`, or `count` × namePrefix1, namePrefix2, …. location defaults to the main world's spawn. Bots skip the login checks (whitelist, bans), and their player data, stats and advancements are deleted when they leave. Returns each bot's name and position.",
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
    "Drive a bot. chat {text}. command {command, collectMs} sends it like a client (PlayerCommandPreprocessEvent fires, so command blockers apply) and returns success (the command exists and no plugin cancelled it; cancelled/unknown say why) and the messages the bot received. messages {since?, limit?}. look {yaw,pitch} or {x,y,z}. move_to {x,y,z, tolerance?, sprint?, timeoutMs? (30000)} finds a path and walks it: around walls, up steps and slabs (jumps one block), down drops of up to maxFall (3), through doors and fence gates (opened with a real right-click; openDoors:false avoids them), up ladders and vines, swimming through water; never into lava, fire, magma or cactus. Searches loaded chunks only, within 256 blocks; searches again when blocked by a mob or player. Returns reached + reason (arrived; no_path with closest — the nearest point it can reach, partial:true walks there; blocked — a door that would not open; height — right x/z but the target y is not where the bot can stand; stuck; timeout; died) and path {nodes, length, complete}. path:false walks in a straight line instead. break_block {block:{x,y,z}, face?} digs it like a survival player (the real dig time for the held tool; instant in creative) and returns broken, the block, ticks, and cancelledBy (BlockBreakEvent / BlockDamageEvent) when a plugin refused; reason unbreakable/timeout/refused otherwise; out of reach is an error: move_to first. jump. sneak {on} and sprint {on} (PlayerToggleSneakEvent / PlayerToggleSprintEvent). drop {all?} drops the held item or stack (PlayerDropItemEvent; cancelled:true when a plugin refused). swap_hands (PlayerSwapHandItemsEvent). state: position, health, held item, inventory, open menu, moving, digging, sneaking, sprinting. give {item, count} (ids like diamond_sword, components allowed). select_hotbar {slot 0-8}. gui_read / gui_click {slot, click: left|right|shift} / gui_close for plugin menus (slots as in gui_read; gui_click returns the menu after the plugin reacted; with inventory:true and no menu open they act on the bot's own inventory like a player pressing E: 5-8 armour, 9-35 main, 36-44 hotbar, 45 offhand). use: right-click with the held item — {block:{x,y,z}, face} on a block, {entity: uuid} on an entity, or nothing for the air. attack {entity: uuid} or {type: 'zombie'} (nearest within reach). hud_read: what the bot's screen would show, rebuilt from the packets it received (works with plugins that send scoreboard packets directly) — same fields as the client hud_read. Actions go through the server's packet handlers, so plugins see the same events as from a real client.",
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
      path: z.boolean().optional().describe("move_to: find a path (default) or walk in a straight line (false)."),
      maxFall: z.number().int().min(0).max(10).optional(),
      openDoors: z.boolean().optional(),
      partial: z.boolean().optional().describe("move_to: when the target cannot be reached, walk to the closest point instead of not moving."),
      on: z.boolean().optional().describe("sneak / sprint: true to start (default), false to stop."),
      all: z.boolean().optional().describe("drop: the whole stack instead of one item."),
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
