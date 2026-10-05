import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";

export class CraftwireError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly hint?: string,
  ) {
    super(message);
    this.name = "CraftwireError";
  }

  toJSON(): { code: string; message: string; hint?: string } {
    return this.hint === undefined
      ? { code: this.code, message: this.message }
      : { code: this.code, message: this.message, hint: this.hint };
  }
}

export function toToolError(err: unknown): CallToolResult {
  const payload =
    err instanceof CraftwireError
      ? err.toJSON()
      : { code: "INTERNAL", message: err instanceof Error ? err.message : String(err) };
  return { isError: true, content: [{ type: "text", text: JSON.stringify(payload) }] };
}
