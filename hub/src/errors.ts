import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";

export class CraftwireError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly hint?: string,
    readonly details?: Record<string, unknown>,
  ) {
    super(message);
    this.name = "CraftwireError";
  }

  toJSON(): { code: string; message: string; hint?: string; details?: Record<string, unknown> } {
    const out: { code: string; message: string; hint?: string; details?: Record<string, unknown> } = { code: this.code, message: this.message };
    if (this.hint !== undefined) out.hint = this.hint;
    if (this.details !== undefined) out.details = this.details;
    return out;
  }
}

export function toToolError(err: unknown): CallToolResult {
  const payload =
    err instanceof CraftwireError
      ? err.toJSON()
      : { code: "INTERNAL", message: err instanceof Error ? err.message : String(err) };
  return { isError: true, content: [{ type: "text", text: JSON.stringify(payload) }] };
}
