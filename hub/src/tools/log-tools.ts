import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import type { AgentEvent } from "../protocol.js";
import { definedOnly } from "../dev/server-manager.js";
import { CraftwireError } from "../errors.js";
import { defineTool, ok, type ToolContext } from "./registry.js";

const LEVELS = ["TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL"] as const;
export const rank = (level: string) => {
  const i = LEVELS.indexOf(level as (typeof LEVELS)[number]);
  return i === -1 ? 2 : i;
};
// Lines the JVM prints one by one when a stack trace goes through System.err.
const CONTINUATION = /^(\s+at |\s*Caused by: |\s*Suppressed: |\s+\.\.\. \d+ more)/;

export interface LogLine {
  time: number;
  level: string;
  logger: string;
  message: string;
  thrown?: string;
  stack?: string[];
}

export function groupLogs(events: AgentEvent[]): LogLine[] {
  const lines: LogLine[] = [];
  for (const ev of events) {
    if (ev.type !== "log") continue;
    const d = ev.data;
    const message = String(d.message ?? "");
    const prev = lines[lines.length - 1];
    if (prev && CONTINUATION.test(message)) {
      (prev.stack ??= []).push(message.trim());
      continue;
    }
    const line: LogLine = { time: ev.time, level: String(d.level ?? "INFO"), logger: String(d.logger ?? ""), message };
    if (d.thrown) line.thrown = String(d.thrown);
    lines.push(line);
  }
  return lines;
}

export function registerLogTools(server: McpServer, ctx: ToolContext): void {
  defineTool(server, ctx, "logs",
    "Recent log lines of a server (default) or client, oldest first, stack traces folded in. Filter: level, contains, since.",
    {
      instance: z.string().optional(),
      level: z.enum(LEVELS).default("INFO"),
      contains: z.string().optional(),
      since: z.number().optional(),
      limit: z.number().int().min(1).max(1000).default(100),
    },
    async (args, c) => {
      const inst = c.agents.resolveAny(args.instance);
      const min = rank(args.level);
      const needle = args.contains?.toLowerCase();
      const lines = groupLogs(c.agents.events(inst.id))
        .filter((l) => rank(l.level) >= min)
        .filter((l) => args.since === undefined || l.time >= args.since)
        .filter((l) => !needle || [l.message, l.thrown ?? "", ...(l.stack ?? [])].join("\n").toLowerCase().includes(needle))
        .slice(-args.limit);
      return ok({ instance: inst.id, lines });
    });

  defineTool(server, ctx, "exceptions",
    "Exceptions the server and clients logged, grouped into bugs, newest first: type, message, count, origin (where to look), plugin; Folia thread-violation hints. id returns one bug's full stack; since filters.",
    {
      id: z.string().regex(/^[0-9a-f]{8}$/).optional().describe("A bug id: returns its full stack."),
      since: z.number().optional(),
      instance: z.string().optional(),
      limit: z.number().int().min(1).max(200).default(50),
    },
    async (args, c) => {
      if (args.id !== undefined) {
        const one = c.exceptions.get(args.id);
        if (!one) throw new CraftwireError("NOT_FOUND", `No exception with id ${args.id}`, "Call exceptions without id to list them.");
        return ok(one);
      }
      const all = c.exceptions.list(definedOnly({ since: args.since, instance: args.instance }));
      return ok({ total: all.length, exceptions: all.slice(0, args.limit) });
    });
}
