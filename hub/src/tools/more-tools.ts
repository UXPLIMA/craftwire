/**
 * more_tools: turns on the extras (load_test, dupe_hunt, …) this session keeps switched off. Turned on, a tool joins
 * the tool list (the client gets a list-changed notification) and stays on for the session.
 */
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { CraftwireError } from "../errors.js";
import { defineTool, extrasOff, ok, type ToolContext } from "./registry.js";

export function registerMoreTools(server: McpServer, ctx: ToolContext): void {
  const off = extrasOff(server);
  if (off.size === 0) return;
  const names = [...off.keys()];
  defineTool(server, ctx, "more_tools",
    `Turn on tools kept off to save tokens: ${names.join(", ")}. enable: their names, or "all".`,
    { enable: z.union([z.literal("all"), z.array(z.string()).min(1)]) },
    async (args) => {
      const want = args.enable === "all" ? [...off.keys()] : args.enable;
      const unknown = want.filter((n) => !names.includes(n));
      if (unknown.length > 0) {
        throw new CraftwireError("INVALID_PARAMS", `Not an extra tool: ${unknown.join(", ")}`, `The extras: ${names.join(", ")}.`);
      }
      const enabled: string[] = [];
      for (const n of want) {
        const tool = off.get(n);
        if (tool === undefined) continue;
        tool.enable();
        off.delete(n);
        enabled.push(n);
      }
      return ok({ enabled, alreadyOn: want.filter((n) => !enabled.includes(n)), stillOff: [...off.keys()] });
    });
}
