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
    "Recent log lines from a Paper server (the default when one is connected) or a client, oldest first. Stack-trace lines printed separately are folded into the line above (`stack`). Filter by minimum `level`, `contains` (case-insensitive, also searches stacks) and `since` (epoch ms). Up to 1000 lines logged before the agent connected are replayed.",
    {
      instance: z.string().optional().describe("Instance id or name. Defaults to the only server, else the only instance."),
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
    "Exceptions the connected server and clients logged, grouped into distinct bugs (same root cause and own stack frames), newest first: type, message, count, firstSeen/lastSeen, origin (first frame outside Minecraft/Paper/Fabric — where to look), plugin when Paper names one, instances. Counted since the hub started, beyond the log buffer. Pass id for one bug's full stack trace and the distinct messages it had. Use after a test step to check nothing broke (since: the step's start time).",
    {
      id: z.string().regex(/^[0-9a-f]{8}$/).optional().describe("One bug's id from the list: returns its full stack."),
      since: z.number().optional().describe("Only bugs seen at or after this time (epoch ms)."),
      instance: z.string().optional().describe("Only bugs logged by this instance name."),
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
