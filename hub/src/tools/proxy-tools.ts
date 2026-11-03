/**
 * The proxy tool: a Velocity proxy running the craftwire-velocity plugin. Listed only while one is connected, so
 * hubs without a proxy pay no tool-definition tokens for it.
 */
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { CraftwireError } from "../errors.js";
import { defineTool, forward, ok, targetArgs, type ToolContext } from "./registry.js";

const NEEDS: Record<string, string[]> = { send: ["player", "server"], kick: ["player"], command: ["command"] };

export function registerProxyTools(server: McpServer, ctx: ToolContext): void {
  const tool = defineTool(server, ctx, "proxy",
    "Velocity proxy: info (version, try order, online mode), servers (pinged: online, players, version, motd), players {server?} "
      + "(who is on which server), send {player, server}, kick {player, reason?}, command {command} (a proxy command, with its output).",
    {
      ...targetArgs,
      action: z.enum(["info", "servers", "players", "send", "kick", "command"]),
      player: z.string().optional(),
      server: z.string().optional(),
      reason: z.string().max(256).optional(),
      command: z.string().optional(),
      ping: z.boolean().optional(),
      limit: z.number().int().min(1).max(5000).optional(),
      collectMs: z.number().int().min(0).max(5000).optional(),
    },
    async (args, c) => {
      const { action, ...rest } = args;
      const missing = (NEEDS[action] ?? []).filter((k) => (rest as Record<string, unknown>)[k] === undefined);
      if (missing.length > 0) throw new CraftwireError("INVALID_PARAMS", `${action} needs ${missing.join(", ")}`, "See the proxy tool description.");
      const timeout = action === "send" || action === "command" ? 40_000 : 20_000;
      return ok(await forward(c, "proxy", `proxy.${action}`, rest, timeout));
    });
  if (tool === undefined) return;
  const refresh = () => (ctx.agents.instances().some((i) => i.kind === "proxy") ? tool.enable() : tool.disable());
  refresh();
  ctx.agents.on("connected", refresh);
  ctx.agents.on("disconnected", refresh);
}
