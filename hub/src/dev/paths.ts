import { resolve } from "node:path";

/** Absolute path used as a map key: resolved (no trailing separator, native slashes), case-folded on Windows. */
export function pathKey(p: string): string {
  const r = resolve(p);
  return process.platform === "win32" ? r.toLowerCase() : r;
}

export const samePath = (a: string, b: string): boolean => pathKey(a) === pathKey(b);
