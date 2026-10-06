import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { defineTool, forward, ok, targetArgs, type ToolContext } from "./registry.js";

const pos = z.object({ x: z.number().int(), y: z.number().int(), z: z.number().int() });
const world = z.string().optional().describe("World name. Defaults to the main world.");

const call = (method: string, timeoutMs?: number) => async (args: Record<string, unknown>, c: ToolContext) =>
  ok(await forward(c, "server", method, args, timeoutMs));

export function registerServerTools(server: McpServer, ctx: ToolContext): void {
  defineTool(server, ctx, "server_command",
    "Run a server command as the console and return its feedback lines. The leading / is optional. Feedback a plugin sends up to collectMs after the command returns is included. asPlayer runs it as that online player instead (feedback then goes to the player's chat). Use the minecraft: prefix when a plugin overrides a vanilla command.",
    {
      ...targetArgs,
      command: z.string().min(1).max(32_000),
      asPlayer: z.string().optional(),
      collectMs: z.number().int().min(0).max(5000).default(250),
    }, call("server.command", 15_000));

  defineTool(server, ctx, "server_eval",
    "Run JavaScript (GraalJS) on the server thread with full Bukkit/Paper API access. Globals: server, player(name), plugin(name), loc(x,y,z,world?), Java.type('fully.qualified.Class'), print(...). The last expression is returned as JSON (Java objects as {class,toString}); print output comes back in `output`. Globals persist until reset:true, a timeout, or a hub reconnect; store them on globalThis (top-level let/const cannot be re-declared on the next run).",
    {
      ...targetArgs,
      code: z.string().min(1),
      timeoutMs: z.number().int().min(100).max(60_000).default(5000),
      reset: z.boolean().default(false),
      at: z.object({ world, x: z.number(), z: z.number() }).optional().describe("Run on the region that owns this position (Folia). Not needed on Paper."),
    },
    async (args, c) => ok(await forward(c, "server", "server.eval", args, args.timeoutMs + 5000)));

  defineTool(server, ctx, "world_query",
    "Read the world. block: one block at x,y,z. region: every block in min..max (inclusive, at most 32768) as palette + runs ([palette index, count] pairs in order x fastest, then z, then y) + counts; encoding:'indices' returns one palette index per block in `blocks` instead. entities: entities in min..max, optional type (e.g. 'zombie'). players: online players. find_block: positions of `block` (an id like 'chest' or a state like 'oak_stairs[facing=east]') in min..max, up to limit.",
    {
      ...targetArgs,
      action: z.enum(["block", "region", "entities", "players", "find_block"]),
      world,
      x: z.number().int().optional(), y: z.number().int().optional(), z: z.number().int().optional(),
      min: pos.optional(), max: pos.optional(),
      type: z.string().optional(),
      block: z.string().optional(),
      limit: z.number().int().min(1).max(1000).default(100),
      encoding: z.enum(["runs", "indices"]).optional(),
    }, call("world.query", 30_000));

  defineTool(server, ctx, "world_edit",
    "Change the world. set_blocks: blocks [{x,y,z,block}] (up to 10000). fill: min..max with block, only where `replace` matches if given. snapshot: save min..max and return an id; restore: put snapshot `id` back. save_schematic: save min..max as structure `name`; paste_schematic: place it with its origin at `at`, optionally rotated/mirrored around that point. Edits over 32768 blocks take a snapshot first and return its snapshotId. Block states use Minecraft syntax, e.g. 'oak_stairs[facing=east]'.",
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
    "What Bukkit events fired on the server, recorded at MONITOR priority so `cancelled` is the final outcome. summary {since?}: counts per type (and how many ended cancelled). query {type?, player?, since?, cancelledOnly?, limit?}: recent events with their values (getters: numbers, text, enums, blocks as type@x,y,z, items as type xN, players by name). listeners {type}: which plugins listen to an event, at what priority, and whether they skip cancelled events — check this when a listener seems not to run. watch {types}: also record busy events that are skipped by default (EntityMoveEvent, BlockPhysicsEvent, tick events…). A plugin's own events are recorded from their first call. Use since = the time a test step started.",
    {
      ...targetArgs,
      action: z.enum(["summary", "query", "listeners", "watch"]),
      type: z.string().optional().describe("Event type: simple (PlayerInteractEvent) or full class name. query: a substring of the simple name also matches."),
      types: z.array(z.string()).max(50).optional().describe("watch: event types to start recording."),
      player: z.string().optional().describe("query: only events about this player (bots included)."),
      since: z.number().optional().describe("Epoch ms; only events at or after it."),
      cancelledOnly: z.boolean().optional(),
      limit: z.number().int().min(1).max(500).default(50),
    }, call("events"));

  defineTool(server, ctx, "server_info",
    "Server health and metadata: TPS (1/5/15 min), MSPT, memory, versions, online players, worlds and plugins.",
    { ...targetArgs }, call("server.info"));

  defineTool(server, ctx, "plugin_manage",
    "list: installed plugins with version and state. info: details for `name` (version, authors, depends, commands). enable/disable: toggle `name` at runtime (not Craftwire itself). Toggling is for quick checks; restart the server for a clean state.",
    { ...targetArgs, action: z.enum(["list", "info", "enable", "disable"]), name: z.string().optional() },
    call("plugin.manage"));
}
