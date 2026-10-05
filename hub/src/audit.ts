import { appendFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";

const MAX_ARGS_CHARS = 4000;

export class AuditLog {
  constructor(
    private readonly dir: string,
    private readonly now: () => Date = () => new Date(),
  ) {}

  write(entry: { tool: string; args: unknown; ok: boolean; ms: number; error?: unknown }): void {
    try {
      mkdirSync(this.dir, { recursive: true });
      const time = this.now();
      const file = join(this.dir, `audit-${time.toISOString().slice(0, 10)}.jsonl`);
      appendFileSync(file, `${JSON.stringify({ time: time.toISOString(), ...entry, args: clip(entry.args) })}\n`);
    } catch {
      // auditing must never break a tool call
    }
  }
}

function clip(value: unknown): unknown {
  const text = JSON.stringify(value) ?? "null";
  return text.length <= MAX_ARGS_CHARS ? value : { truncated: true, preview: text.slice(0, MAX_ARGS_CHARS) };
}
