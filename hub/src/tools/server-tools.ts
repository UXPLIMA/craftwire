import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve as resolvePath } from "node:path";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";
import { withColors } from "./colors-arg.js";
import { defineTool, forward, ok, targetArgs, type ToolContext } from "./registry.js";

const pos = z.object({ x: z.number().int(), y: z.number().int(), z: z.number().int() });
const world = z.string().optional().describe("Default: the main world.");

const call = (method: string, timeoutMs?: number) => async (args: Record<string, unknown>, c: ToolContext) =>
  ok(await forward(c, "server", method, args, timeoutMs));

export function registerServerTools(server: McpServer, ctx: ToolContext): void {
  defineTool(server, ctx, "server_command",
    "Run a console command (or asPlayer) and return its feedback, including plugin messages within collectMs. Use the minecraft: prefix when a plugin overrides a vanilla command.",
    {
      ...targetArgs,
      command: z.string().min(1).max(32_000),
      asPlayer: z.string().optional(),
      collectMs: z.number().int().min(0).max(5000).default(250),
    }, call("server.command", 15_000));

  defineTool(server, ctx, "server_eval",
    "Run JavaScript on the server with the Bukkit/Paper API: server, player(name), plugin(name), loc(x,y,z), Java.type, print. Returns the last value and printed output; globals persist until reset. Folia: pass at or asPlayer to touch blocks, entities or players. Not for building: use build.",
    {
      ...targetArgs,
      code: z.string().min(1),
      timeoutMs: z.number().int().min(100).max(60_000).default(5000),
      reset: z.boolean().default(false),
      at: z.object({ world, x: z.number(), z: z.number() }).optional().describe("Folia: run on the region of this position."),
      asPlayer: z.string().optional().describe("Folia: run on this player's thread."),
    },
    async (args, c) => ok(await forward(c, "server", "server.eval", args, args.timeoutMs + 5000)));

  defineTool(server, ctx, "world_query",
    "Read the world: block {x,y,z}; region {min,max}: palette + runs (max 32768), encoding 'indices', or 'layers' (a letter per block, max 65536, for build's layers()); entities {min,max,type?}; players; find_block {block,min,max}.",
    {
      ...targetArgs,
      action: z.enum(["block", "region", "entities", "players", "find_block"]),
      world,
      x: z.number().int().optional(), y: z.number().int().optional(), z: z.number().int().optional(),
      min: pos.optional(), max: pos.optional(),
      type: z.string().optional(),
      block: z.string().optional(),
      limit: z.number().int().min(1).max(1000).default(100),
      encoding: z.enum(["runs", "indices", "layers"]).optional(),
    }, call("world.query", 30_000));

  defineTool(server, ctx, "world_render",
    "Draw a region of a Paper world, no client needed: top (map), slice {y} (floor plan), side {facing}, iso {corner}/iso4 (3D, size px). Up to 512x512 blocks; scale px/block; grid N labels coordinates; savePath writes the PNG.",
    {
      ...targetArgs,
      world,
      x1: z.number().int(), z1: z.number().int(), x2: z.number().int(), z2: z.number().int(),
      view: z.enum(["top", "slice", "side", "iso", "iso4"]).default("top"),
      corner: z.enum(["ne", "nw", "se", "sw"]).optional(),
      size: z.number().int().min(256).max(2048).optional(),
      y: z.number().int().optional().describe("slice: the height."),
      facing: z.enum(["north", "south", "east", "west"]).optional().describe("side: the direction you look."),
      y1: z.number().int().optional(), y2: z.number().int().optional(),
      scale: z.number().int().min(1).max(8).optional(),
      grid: z.number().int().min(0).max(512).default(0),
      players: z.boolean().default(true),
      generate: z.boolean().default(false),
      savePath: z.string().optional(),
    },
    async (args, c): Promise<CallToolResult> => {
      const { savePath, ...rest } = args;
      const params = await withColors(c, rest, rest.view);
      const r = (await forward(c, "server", "world.render", params, 60_000)) as { mime: string; data: string } & Record<string, unknown>;
      const { data, mime, ...meta } = r;
      if (savePath !== undefined) {
        const file = resolvePath(savePath);
        mkdirSync(dirname(file), { recursive: true });
        writeFileSync(file, Buffer.from(data, "base64"));
        meta.savedPath = file;
      }
      return { content: [{ type: "image", data, mimeType: mime }, { type: "text", text: JSON.stringify(meta) }] };
    });

  defineTool(server, ctx, "world_edit",
    "Change blocks: set_blocks [{x,y,z,block}] (max 10000), fill {min,max,block,replace?}, snapshot/restore {id}, save_schematic/paste_schematic {name,at}. Over 32768 blocks a snapshot is taken first. For more than a few hundred blocks use build.",
    {
      ...targetArgs,
      action: z.enum(["set_blocks", "fill", "snapshot", "restore", "save_schematic", "paste_schematic"]),
      world,
      blocks: z.array(pos.extend({ block: z.string() })).max(10_000).optional(),
      min: pos.optional(), max: pos.optional(),
      block: z.string().optional(),
      replace: z.string().optional(),
      id: z.string().optional(),
      name: z.string().regex(/^[A-Za-z0-9_-]{1,64}$/).optional(),
      at: pos.optional(),
      rotation: z.enum(["none", "clockwise_90", "clockwise_180", "counterclockwise_90"]).default("none"),
      mirror: z.enum(["none", "left_right", "front_back"]).default("none"),
      includeEntities: z.boolean().default(false),
      physics: z.boolean().default(false),
    }, call("world.edit", 120_000));

  defineTool(server, ctx, "events",
    "Bukkit events the server fired (cancelled = final outcome): summary {since?}, query {type?,player?,since?,cancelledOnly?}, listeners {type} (who listens, priority, ignoreCancelled), watch {types} (record busy events too).",
    {
      ...targetArgs,
      action: z.enum(["summary", "query", "listeners", "watch"]),
      type: z.string().optional().describe("Event class, simple or full name."),
      types: z.array(z.string()).max(50).optional(),
      player: z.string().optional(),
      since: z.number().optional(),
      cancelledOnly: z.boolean().optional(),
      limit: z.number().int().min(1).max(500).default(50),
    }, call("events"));

  defineTool(server, ctx, "server_info",
    "Server health: TPS, MSPT, memory, versions, players, worlds, plugins; on Folia per-region TPS.",
    { ...targetArgs }, call("server.info"));

  defineTool(server, ctx, "plugin_manage",
    "list plugins; info {name}; enable/disable {name} for quick checks (not on Folia: use plugin_deploy).",
    { ...targetArgs, action: z.enum(["list", "info", "enable", "disable"]), name: z.string().optional() },
    call("plugin.manage"));
}
