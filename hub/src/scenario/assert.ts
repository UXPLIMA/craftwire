import { select } from "./path.js";

/** A check on a value (a tool result): every given comparison must hold for the selected value. */
export interface Assertion {
  /** Where in the value to look (`a.b[0].c`, `[*]` for every element); the whole value when absent. */
  path?: string;
  equals?: unknown;
  /** Case-insensitive regex on the text (JSON for anything but a string). */
  matches?: string;
  /** Substring, array element or partial object. */
  contains?: unknown;
  exists?: boolean;
  lessThan?: number;
  greaterThan?: number;
  length?: number;
  /** With `[*]`: every element must pass, not just one. */
  every?: boolean;
}

export interface AssertionOutcome {
  pass: boolean;
  /** What was found at the path: one value, or the list when the path has `[*]`. */
  actual: unknown;
}

const CHECKS = ["equals", "matches", "contains", "exists", "lessThan", "greaterThan", "length"] as const;

export function checkAssertion(a: Assertion, root: unknown): AssertionOutcome {
  if (!CHECKS.some((k) => a[k] !== undefined)) {
    throw new Error(`An assertion needs one of ${CHECKS.join(", ")}`);
  }
  const sel = select(root, a.path ?? "");
  const many = (a.path ?? "").includes("[*]");
  const present = sel.found && sel.values.length > 0;
  const actual = !present ? "(missing)" : many ? sel.values : sel.values[0];
  if (a.exists !== undefined && present !== a.exists) return { pass: false, actual };
  if (!CHECKS.some((k) => k !== "exists" && a[k] !== undefined)) return { pass: true, actual };
  if (!present) return { pass: false, actual };
  const passes = (v: unknown) => valuePasses(a, v);
  return { pass: a.every ? sel.values.every(passes) : sel.values.some(passes), actual };
}

function valuePasses(a: Assertion, v: unknown): boolean {
  if (a.equals !== undefined && !deepEqual(v, a.equals)) return false;
  if (a.matches !== undefined && !new RegExp(a.matches, "i").test(text(v))) return false;
  if (a.contains !== undefined && !contains(v, a.contains)) return false;
  if (a.lessThan !== undefined && !(Number(v) < a.lessThan)) return false;
  if (a.greaterThan !== undefined && !(Number(v) > a.greaterThan)) return false;
  if (a.length !== undefined && !((Array.isArray(v) || typeof v === "string") && v.length === a.length)) return false;
  return true;
}

const text = (v: unknown) => (typeof v === "string" ? v : JSON.stringify(v));

export function deepEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (Array.isArray(a) || Array.isArray(b)) {
    return Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((x, i) => deepEqual(x, b[i]));
  }
  if (a === null || b === null || typeof a !== "object" || typeof b !== "object") return false;
  const ka = Object.keys(a);
  const kb = Object.keys(b);
  return ka.length === kb.length && ka.every((k) => Object.hasOwn(b, k) && deepEqual((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k]));
}

/** Substring, an array element that matches, or a partial object (every expected key matches). */
function contains(v: unknown, expected: unknown): boolean {
  if (typeof v === "string") return typeof expected === "string" && v.includes(expected);
  if (Array.isArray(v)) return v.some((x) => partial(x, expected));
  return partial(v, expected);
}

function partial(v: unknown, expected: unknown): boolean {
  if (expected === null || typeof expected !== "object" || Array.isArray(expected)) return deepEqual(v, expected);
  if (v === null || typeof v !== "object" || Array.isArray(v)) return false;
  return Object.entries(expected).every(([k, e]) => Object.hasOwn(v, k) && partial((v as Record<string, unknown>)[k], e));
}
