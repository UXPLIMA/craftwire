/** bot_spawn, bot_remove, bot_list, bot_action, bot_behavior and bot_form: one tool set for plugin, java and bedrock bots. */
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import type { InstanceInfo } from "../../agents.js";
import { CraftwireError } from "../../errors.js";
import { defineTool, defineToolWithUnlisted, ok, targetArgs, type ToolContext } from "../registry.js";
import { type BotKind, directoryFor, expandNames } from "./directory.js";
import { registerDupeTools } from "./dupe-hunt.js";
import { registerLoadTools } from "./load-test.js";
import { registerLocaleTools } from "./locale-tour.js";
import { merge } from "./merge.js";
import { agentForBot, broadcast, requireAgents } from "./route.js";
import { registerRunnerTools } from "./runner-tools.js";
import { actionExtras, actionShape, behaviorShape, screenFields, selector, spawnExtras, spawnShape } from "./schemas.js";
import { spawnOn } from "./spawn.js";

const REMOVED = { sum: ["removed"], join: ["names"] };
const BEHAVIOR_MERGE = { sum: ["bots", "stopped"], counts: ["byType"], concat: ["details"], limit: 20 };

type Args = Record<string, unknown> & { instance?: string; operationId?: string; kind?: BotKind; bot?: string };

/** How long the hub waits for one bot's action: an action that waits on the game must not time out first. */
export function actionTimeout(a: { action: string; timeoutMs?: number; collectMs?: number; settleMs?: number }): number {
  switch (a.action) {
    case "move_to":
    case "break_block": return (a.timeoutMs ?? 30_000) + 5000;
    case "command": return (a.collectMs ?? 300) + 10_000;
    case "gui_click":
    case "move_item":
    case "transfer":
    case "equip": return (a.settleMs ?? 150) + 10_000;
    default: return 15_000;
  }
}

function params(args: Args): Record<string, unknown> {
  const { instance: _i, operationId: _o, kind: _k, runner: _r, ...rest } = args as Args & { runner?: string };
  return rest;
}

/** Runs a request on the agent of one bot, or on every agent for a selector (bots pattern, group). */
async function onBots(c: ToolContext, args: Args, method: string, timeoutMs: number, rule: Parameters<typeof merge>[1]): Promise<unknown> {
  const p = params(args);
  if (typeof args.bot === "string") {
    const inst = await agentForBot(c, args.bot, args.kind, args.instance);
    return c.ops.run(args.operationId, () => c.agents.request(inst.id, method, p, timeoutMs));
  }
  if (args.bots === undefined && args.group === undefined) {
    throw new CraftwireError("INVALID_PARAMS", "Name the bots: bot, bots (names or a pattern like \"Load*\") or group", "See bot_list.");
  }
  const agents = requireAgents(c, args.kind, args.instance);
  return c.ops.run(args.operationId, async () => merge(await broadcast(c, agents, method, p, timeoutMs), rule));
}

export function registerBotTools(server: McpServer, ctx: ToolContext): void {
  defineToolWithUnlisted(server, ctx, "bot_spawn",
    "Spawn bots that plugins treat as real players. kind: plugin (fake player in a Paper server, default), java or bedrock (protocol "
      + "clients from a bot runner; need address). Up to 1000 per call; group names them. Returns {spawned, names:\"Bot1..Bot50\"}. "
      + "More (perTick, spread, profile, settings, netsim, auth, …): craftwire://docs/tools.",
    { ...targetArgs, ...spawnShape }, spawnExtras,
    async (args, c) => ok(await spawnOn(c, args as Args & z.infer<z.ZodObject<typeof spawnShape & typeof spawnExtras>>)));

  defineTool(server, ctx, "bot_remove",
    "Remove bots: bot, bots (names or a pattern), group, or all:true. Returns the count (names:true lists them).",
    {
      ...targetArgs, ...selector, kind: actionShape.kind,
      name: z.string().optional(), all: z.boolean().default(false), names: z.boolean().optional(),
    },
    async (args, c) => {
      const a = args as Args & { all: boolean; name?: string };
      const dir = directoryFor(c.agents);
      const named = a.bot !== undefined || a.name !== undefined || a.bots !== undefined || a.group !== undefined;
      if (a.all || !named) {
        if (!a.all) throw new CraftwireError("INVALID_PARAMS", "Name the bots to remove, or pass all:true", "bot_list shows them.");
        const agents = requireAgents(c, a.kind, a.instance);
        const answers = await broadcast(c, agents, "bot.remove", { all: true, names: a.names ?? false }, 120_000);
        const r = merge(answers, REMOVED);
        agents.forEach((i) => dir.forgetInstance(i.id));
        return ok(r);
      }
      const r = (await onBots(c, { ...a, bot: a.bot ?? a.name }, "bot.remove", 120_000, REMOVED)) as Record<string, unknown>;
      if (typeof r.names === "string") dir.forget(expandNames(r.names));
      else if (typeof (a.bot ?? a.name) === "string") dir.forget([String(a.bot ?? a.name)]);
      return ok(r);
    });

  defineTool(server, ctx, "bot_list",
    "List bots of every kind (or by bot, bots pattern, group, kind): count, joining, and a summary per bot (name, kind, group, position).",
    { ...targetArgs, ...selector, kind: actionShape.kind, limit: z.number().int().min(1).max(1000).default(100) },
    async (args, c) => {
      const a = args as Args;
      const agents = requireAgents(c, a.kind, a.instance);
      const answers = await broadcast(c, agents, "bot.list", params(a), 20_000);
      return ok(merge(answers, { sum: ["count", "joining"], concat: ["bots"], limit: Number(a.limit) }));
    });

  defineToolWithUnlisted(server, ctx, "bot_action",
    "Drive bots like real clients (plugins see the same events): one bot, or many by bots (names, pattern) or group. Menus: gui_read, "
      + "gui_click {slot, click; key for number; slots, button for drag}, move_item, transfer, equip, special screens. Other fields per "
      + "action: craftwire://docs/tools.",
    { ...targetArgs, ...actionShape }, { ...actionExtras, ...screenFields },
    async (args, c) => ok(await onBots(c, args as Args, "bot.action", actionTimeout(args) + (args.bot === undefined ? 10_000 : 0),
      { sum: ["bots", "failed"], concat: ["results"], limit: 20 })));

  defineTool(server, ctx, "bot_behavior",
    "Give bots something to keep doing on their own, with no call per step: wander, follow, patrol, chat, command_loop, menu_click, idle, "
      + "or script (JavaScript: bots.onTick, bots.onMessage). action set {type, params} | stop | status.",
    { ...targetArgs, ...behaviorShape },
    async (args, c) => {
      if (args.action === "set" && args.type === undefined) throw new CraftwireError("INVALID_PARAMS", "set needs type", "e.g. type:'wander'.");
      return ok(await onBots(c, args as Args, "bot.behavior", 30_000, BEHAVIOR_MERGE));
    });

  registerFormTool(server, ctx);
  registerLoadTools(server, ctx);
  registerDupeTools(server, ctx);
  registerLocaleTools(server, ctx);
  registerRunnerTools(server, ctx);
}

/** Bedrock forms: listed only while a bot runner with Bedrock bots is connected. */
function registerFormTool(server: McpServer, ctx: ToolContext): void {
  const tool = defineTool(server, ctx, "bot_form",
    "Bedrock bots: read the open form (simple buttons, modal yes/no, custom inputs) or answer it {button} / {values}, or close it.",
    {
      ...targetArgs,
      bot: z.string().min(1),
      action: z.enum(["read", "answer", "close"]).default("read"),
      button: z.union([z.number().int().min(0), z.string()]).optional(),
      values: z.array(z.union([z.string(), z.number(), z.boolean()])).max(64).optional(),
    },
    async (args, c) => {
      const inst = await agentForBot(c, args.bot, "bedrock", args.instance);
      return ok(await c.ops.run(args.operationId, () => c.agents.request(inst.id, "bot.form", params(args as Args), 15_000)));
    });
  if (tool === undefined) return;
  const bedrock = () => ctx.agents.instances().some((i: InstanceInfo) => i.kind === "botrunner" && (i.botKinds ?? []).includes("bedrock"));
  const refresh = () => (bedrock() ? tool.enable() : tool.disable());
  refresh();
  ctx.agents.on("connected", refresh);
  ctx.agents.on("disconnected", refresh);
}
