import { blockColorsFor } from "../blocks/colors.js";
import type { ToolContext } from "./registry.js";

/** Adds the block colour table to a render or build call when it draws an iso view (the agent has no textures). */
export async function withColors(c: ToolContext, args: Record<string, unknown>, view: unknown): Promise<Record<string, unknown>> {
  if ((view !== "iso" && view !== "iso4") || c.home === undefined) return args;
  let mc: string;
  try {
    mc = c.agents.resolve("server", args.instance as string | undefined).mcVersion;
  } catch {
    return args;   // the forward reports the missing or ambiguous instance
  }
  const colors = await blockColorsFor(c.home, mc, c.log);
  return colors ? { ...args, colors } : args;
}
