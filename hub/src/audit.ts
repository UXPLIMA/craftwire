/**
 * The audit log: every tool call with who made it (the person behind the token, "local" over stdio), the MCP
 * session, when, the arguments and a preview of the result, one JSON line per call in <home>/logs/audit-<day>.jsonl.
 * The newest calls are also kept in memory for the web panel's session replay. Images are summarised, never stored.
 */
import { appendFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";
import { RingBuffer } from "./ringbuffer.js";

const MAX_ARGS_CHARS = 4000;
const MAX_RESULT_CHARS = 2000;
const RECENT = 5000;

export interface AuditEntry {
  time: string;
  tool: string;
  args: unknown;
  ok: boolean;
  ms: number;
  user?: string;
  session?: string;
  result?: unknown;
  error?: unknown;
}

export class AuditLog {
  private readonly recent = new RingBuffer<AuditEntry>(RECENT);

  constructor(
    private readonly dir: string,
    private readonly now: () => Date = () => new Date(),
  ) {}

  write(entry: Omit<AuditEntry, "time">): void {
    const time = this.now();
    const e: AuditEntry = {
      time: time.toISOString(), ...entry, args: clip(entry.args, MAX_ARGS_CHARS),
      ...(entry.result !== undefined ? { result: preview(entry.result) } : {}),
    };
    this.recent.push(e);
    try {
      mkdirSync(this.dir, { recursive: true });
      appendFileSync(join(this.dir, `audit-${e.time.slice(0, 10)}.jsonl`), `${JSON.stringify(e)}\n`);
    } catch {
      // auditing must never break a tool call
    }
  }

  /** The sessions seen since the hub started, newest first: who, when it started and ended, how many calls. */
  sessions(): { session: string; user: string; first: string; last: string; calls: number }[] {
    const out = new Map<string, { session: string; user: string; first: string; last: string; calls: number }>();
    for (const e of this.recent.toArray()) {
      const id = e.session ?? "local";
      const s = out.get(id) ?? { session: id, user: e.user ?? "local", first: e.time, last: e.time, calls: 0 };
      s.last = e.time;
      s.calls++;
      out.set(id, s);
    }
    return [...out.values()].sort((a, b) => b.last.localeCompare(a.last));
  }

  /** One session's calls in order, for step-by-step replay. */
  calls(session: string, limit = 1000): AuditEntry[] {
    return this.recent.toArray().filter((e) => (e.session ?? "local") === session).slice(-limit);
  }
}

function clip(value: unknown, max: number): unknown {
  const text = JSON.stringify(value) ?? "null";
  return text.length <= max ? value : { truncated: true, preview: text.slice(0, max) };
}

/** A tool result as the replay shows it: text parts (clipped), images as their type only. */
function preview(result: unknown): unknown {
  const content = (result as { content?: { type?: string; text?: string; mimeType?: string }[] } | undefined)?.content;
  if (!Array.isArray(content)) return clip(result, MAX_RESULT_CHARS);
  return content.map((c) => (c.type === "text" ? clip(c.text ?? "", MAX_RESULT_CHARS) : { [c.type ?? "part"]: c.mimeType ?? true }));
}
