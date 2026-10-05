import { CraftwireError } from "./errors.js";

type Status = "running" | "done" | "failed";

interface Entry {
  status: Status;
  result?: unknown;
  error?: unknown;
  updatedAt: number;
  promise?: Promise<unknown>;
}

export class OperationTracker {
  private readonly ops = new Map<string, Entry>();

  constructor(
    private readonly ttlMs = 300_000,
    private readonly now: () => number = Date.now,
  ) {}

  async run<T>(operationId: string | undefined, fn: () => Promise<T>): Promise<T> {
    if (!operationId) return fn();
    this.prune();
    const existing = this.ops.get(operationId);
    if (existing?.status === "done") return existing.result as T;
    if (existing?.status === "running" && existing.promise) return existing.promise as Promise<T>;

    const promise = fn();
    this.ops.set(operationId, { status: "running", updatedAt: this.now(), promise });
    try {
      const result = await promise;
      this.ops.set(operationId, { status: "done", result, updatedAt: this.now() });
      return result;
    } catch (e) {
      const error = e instanceof CraftwireError ? e.toJSON() : { code: "INTERNAL", message: e instanceof Error ? e.message : String(e) };
      this.ops.set(operationId, { status: "failed", error, updatedAt: this.now() });
      throw e;
    }
  }

  status(operationId: string): { status: "unknown" | Status; result?: unknown; error?: unknown; updatedAt?: number } {
    this.prune();
    const e = this.ops.get(operationId);
    if (!e) return { status: "unknown" };
    const { promise: _promise, ...rest } = e;
    return rest;
  }

  private prune(): void {
    const now = this.now();
    for (const [id, e] of this.ops) if (e.status !== "running" && now - e.updatedAt > this.ttlMs) this.ops.delete(id);
  }
}
