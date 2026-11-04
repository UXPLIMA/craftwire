import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { BedrockBots } from "../bedrock/bedrock-bots.js";
import { defineTool, ok, type ToolContext } from "./registry.js";

const NAME = /^[A-Za-z0-9_]{3,16}$/;
const ACTIONS = [
  "list", "state", "chat", "command", "messages", "titles", "forms", "form_answer",
  "scoreboard", "bossbars", "players", "packs", "commands",
] as const;

function bots(ctx: ToolContext): BedrockBots {
  ctx.bedrockBots ??= new BedrockBots();
  return ctx.bedrockBots;
}

export function registerBedrockTools(server: McpServer, ctx: ToolContext): void {
  defineTool(server, ctx, "bedrock_bot_spawn",
    "Join a Geyser port as a headless Bedrock player (offline login, so the development Geyser must accept unverified Bedrock logins). It sees what a Bedrock client is sent: chat, titles, forms, scoreboard, boss bars, tab list, packs.",
    {
      name: z.string().regex(NAME),
      host: z.string().default("127.0.0.1"),
      port: z.number().int().min(1).max(65535).default(19132),
      version: z.string().regex(/^\d+\.\d+\.\d+$/).optional(),
      transport: z.enum(["raknet", "nethernet"]).optional(),
      timeoutMs: z.number().int().min(2000).max(120_000).default(30_000),
    },
    async (args, c) => ok(await bots(c).spawn(args)));

  defineTool(server, ctx, "bedrock_bot_remove",
    "Disconnect a Bedrock bot (name) or every one (all:true).",
    { name: z.string().optional(), all: z.boolean().default(false) },
    async (args, c) => ok(args.all ? { removed: bots(c).removeAll() } : { removed: args.name ? bots(c).remove(args.name) : false }));

  defineTool(server, ctx, "bedrock_bot_action",
    "Drive or read a Bedrock bot. list; state; chat {text}; command {command}; messages {since,limit}; titles; forms; form_answer {formId?, button (simple form index) | choice (modal true/false) | values (custom form array) | close:true}; scoreboard; bossbars; players; packs; commands {limit}. Text is what Geyser sent, Bedrock colour codes included.",
    {
      bot: z.string().optional(),
      action: z.enum(ACTIONS),
      text: z.string().max(512).optional(),
      command: z.string().max(4000).optional(),
      since: z.number().optional(),
      limit: z.number().int().min(1).max(500).optional(),
      formId: z.number().int().optional(),
      button: z.number().int().min(0).optional(),
      choice: z.boolean().optional(),
      values: z.array(z.union([z.string(), z.number(), z.boolean(), z.null()])).optional(),
      close: z.boolean().optional(),
    },
    async (args, c) => {
      if (args.action === "list") return ok({ bots: bots(c).list() });
      return ok(bots(c).action(args.bot ?? "", args.action, args));
    });
}
