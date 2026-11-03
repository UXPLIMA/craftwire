/** Merges the answers of several bot-running agents into one result of the same shape. */
import { CraftwireError } from "../../errors.js";
import type { Answer } from "./route.js";

export interface MergeRule {
  /** Numbers added up. */
  sum?: string[];
  /** Numbers kept at their maximum. */
  max?: string[];
  /** Arrays concatenated, at most `limit` items. */
  concat?: string[];
  /** Strings joined with ", " (compact name ranges). */
  join?: string[];
  /** Objects of counts merged by key (byType). */
  counts?: string[];
  limit?: number;
}

type Obj = Record<string, unknown>;

/** One result from all answers; failed agents are listed under `errors`, and when all failed the first error is thrown. */
export function merge(answers: Answer[], rule: MergeRule): Obj {
  const ok = answers.filter((a) => a.error === undefined);
  if (ok.length === 0) {
    if (answers.length === 0) throw new CraftwireError("NO_INSTANCE", "No agent runs bots", "Call list_instances.");
    throw answers[0]!.error;
  }
  if (answers.length === 1) return ok[0]!.result as Obj;
  const out: Obj = {};
  const limit = rule.limit ?? 100;
  for (const a of ok) {
    const r = a.result as Obj;
    for (const k of rule.sum ?? []) out[k] = (Number(out[k] ?? 0)) + Number(r[k] ?? 0);
    for (const k of rule.max ?? []) out[k] = Math.max(Number(out[k] ?? 0), Number(r[k] ?? 0));
    for (const k of rule.concat ?? []) {
      const list = (out[k] as unknown[] | undefined) ?? [];
      for (const item of (r[k] as unknown[] | undefined) ?? []) if (list.length < limit) list.push(item);
      if (list.length > 0) out[k] = list;
    }
    for (const k of rule.join ?? []) {
      const s = r[k];
      if (typeof s === "string" && s.length > 0) out[k] = out[k] === undefined ? s : `${String(out[k])}, ${s}`;
    }
    for (const k of rule.counts ?? []) {
      const into = (out[k] as Record<string, number> | undefined) ?? {};
      for (const [key, n] of Object.entries((r[k] as Record<string, number> | undefined) ?? {})) into[key] = (into[key] ?? 0) + Number(n);
      out[k] = into;
    }
  }
  out.byInstance = ok.map((a) => ({ instance: a.instance.id, ...pick(a.result as Obj, rule.sum ?? []) }));
  const failed = answers.filter((a) => a.error !== undefined);
  if (failed.length > 0) out.errors = failed.map((a) => ({ instance: a.instance.id, error: describe(a.error) }));
  return out;
}

function pick(r: Obj, keys: string[]): Obj {
  const o: Obj = {};
  for (const k of keys) if (r[k] !== undefined) o[k] = r[k];
  return o;
}

function describe(e: unknown): unknown {
  return e instanceof CraftwireError ? e.toJSON() : String(e);
}
