/**
 * bedrock_client: the user's own Bedrock game (Minecraft for Windows, or mcpelauncher on Linux), driven from outside
 * (bedrock-client/). An extra, so it costs no tool-definition tokens until more_tools turns it on.
 */
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";
import { CraftwireError } from "../errors.js";
import { defineTool, ok, type ToolContext } from "./registry.js";

const NEEDS: Record<string, string[]> = { key: ["key"], type: ["text"], command: ["command"], click: ["x", "y"], scroll: ["x", "y", "amount"] };

export function registerBedrockClientTools(server: McpServer, ctx: ToolContext): void {
  defineTool(server, ctx, "bedrock_client",
    "Your own Bedrock game (Minecraft for Windows; on Linux mcpelauncher), seen and driven from outside: "
      + "start {address?} (opens the game, joins host[:port]), status, screenshot {maxWidth?} (the game window), "
      + "key {key, holdMs?} (a letter, digit, f1-f12, enter, escape, space, arrows...), "
      + "type {text, enter?}, command {command} (chat command), click {x, y, button?} and scroll {x, y, amount} in the last "
      + "screenshot's pixels (Linux only), stop. All of it works with the game in the background, without the user's mouse or "
      + "focus; on Windows menus take keys: arrows move the selection, enter presses, escape goes back. Reads nothing from "
      + "inside the game.",
    {
      action: z.enum(["start", "status", "screenshot", "key", "type", "command", "click", "scroll", "stop"]),
      address: z.string().optional(),
      maxWidth: z.number().int().min(256).max(3840).optional(),
      key: z.string().optional(),
      holdMs: z.number().int().min(0).max(10_000).optional(),
      text: z.string().max(512).optional(),
      enter: z.boolean().optional(),
      command: z.string().max(512).optional(),
      x: z.number().int().optional(),
      y: z.number().int().optional(),
      button: z.enum(["left", "right", "middle"]).optional(),
      amount: z.number().int().min(-50).max(50).optional(),
    },
    async (args, c): Promise<CallToolResult> => {
      const client = c.bedrockClient;
      if (client === undefined) {
        throw new CraftwireError("UNSUPPORTED", "bedrock_client is not available in this session",
          "It drives the game on the hub's own machine; the owner and the dev role have it.");
      }
      const missing = (NEEDS[args.action] ?? []).filter((k) => (args as Record<string, unknown>)[k] === undefined);
      if (missing.length > 0) throw new CraftwireError("INVALID_PARAMS", `${args.action} needs ${missing.join(", ")}`, "See the tool description.");
      switch (args.action) {
        case "start":
          return ok(await client.start(args.address));
        case "status":
          return ok(await client.status());
        case "screenshot": {
          const s = await client.screenshot(args.maxWidth);
          const meta = { width: s.width, height: s.height, method: s.method };
          return { content: [{ type: "image", data: s.data, mimeType: "image/png" }, { type: "text", text: JSON.stringify(meta) }] };
        }
        case "key":
          await client.key(args.key!, args.holdMs);
          return ok({ sent: args.key });
        case "type":
          await client.type(args.text!, args.enter ?? false);
          return ok({ typed: args.text!.length });
        case "command":
          await client.command(args.command!);
          return ok({ sent: args.command });
        case "click":
          await client.click(args.x!, args.y!, args.button ?? "left");
          return ok({ clicked: [args.x, args.y] });
        case "scroll":
          await client.scroll(args.x!, args.y!, args.amount!);
          return ok({ scrolled: args.amount });
        case "stop":
          return ok(await client.stop());
      }
    });
}
