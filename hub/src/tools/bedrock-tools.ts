import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { BedrockBots } from "../bedrock/bedrock-bots.js";
import { CraftwireError } from "../errors.js";
import { defineTool, ok, type ToolContext } from "./registry.js";

const NAME = /^[A-Za-z0-9_]{3,16}$/;
const ACTIONS = [
  "spawn", "list", "remove", "state", "form_close", "chat", "command", "messages", "titles", "forms", "form_answer",
  "scoreboard", "bossbars", "players", "packs", "commands",
] as const;

function bots(ctx: ToolContext): BedrockBots {
  ctx.bedrockBots ??= new BedrockBots();
  return ctx.bedrockBots;
}

export function registerBedrockTools(server: McpServer, ctx: ToolContext): void {
  defineTool(server, ctx, "bedrock_bot",
    "Bedrock bot via Geyser (no login check). spawn {bot,text:host:port}; chat|command {text}; form_answer {values: [button]|[bool]|fields} for the newest form.",
    {
      bot: z.string().optional(),
      action: z.enum(ACTIONS),
      text: z.string().optional(),
      since: z.number().optional(),
      values: z.array(z.union([z.string(), z.number(), z.boolean(), z.null()])).optional(),
    },
    async (args, c) => {
      if (args.action === "spawn") {
        if (!NAME.test(args.bot ?? "")) throw new CraftwireError("bad_name", "A Bedrock bot needs a name of 3 to 16 letters, digits or _", "Pass bot.");
        const [host, port] = (args.text ?? "127.0.0.1:19132").split(":");
        return ok(await bots(c).spawn({ name: args.bot ?? "", host: host || "127.0.0.1", port: Number(port) || 19132, timeoutMs: 30_000 }));
      }
      if (args.action === "list") return ok({ bots: bots(c).list() });
      if (args.action === "remove") return ok({ removed: args.bot ? bots(c).remove(args.bot) : bots(c).removeAll() });
      const values = args.values ?? [];
      const answer = values.length === 1 && typeof values[0] !== "string" && values[0] !== null ? values[0] : args.values;
      return ok(bots(c).action(args.bot ?? "", args.action === "form_close" ? "form_answer" : args.action, {
        ...args,
        command: args.text,
        close: args.action === "form_close",
        ...(typeof answer === "number" ? { button: answer } : typeof answer === "boolean" ? { choice: answer } : { values: answer }),
      }));
    });
}
