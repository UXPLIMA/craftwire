/** profile_diff: what changed between two profile results, biggest changes first. */

type Obj = Record<string, unknown>;

interface Change {
  name: string;
  before: number;
  after: number;
  change: number;
}

function num(o: unknown, ...path: string[]): number {
  let cur: unknown = o;
  for (const k of path) cur = cur !== null && typeof cur === "object" ? (cur as Obj)[k] : undefined;
  return typeof cur === "number" ? cur : 0;
}

/** Percent of time per name (owner or method) in a profile list. */
function shares(list: unknown, key: string): Map<string, number> {
  const out = new Map<string, number>();
  if (!Array.isArray(list)) return out;
  for (const item of list as Obj[]) {
    const name = String(item[key] ?? "");
    out.set(name, (out.get(name) ?? 0) + num(item, "percent"));
  }
  return out;
}

function changes(before: Map<string, number>, after: Map<string, number>, limit: number): Change[] {
  const names = new Set([...before.keys(), ...after.keys()]);
  return [...names]
    .map((name) => {
      const b = before.get(name) ?? 0;
      const a = after.get(name) ?? 0;
      return { name, before: b, after: a, change: Math.round((a - b) * 10) / 10 };
    })
    .filter((c) => c.change !== 0)
    .sort((x, y) => Math.abs(y.change) - Math.abs(x.change))
    .slice(0, limit);
}

function metric(before: Obj, after: Obj, ...path: string[]): { before: number; after: number; changePercent: number } {
  const b = num(before, ...path);
  const a = num(after, ...path);
  return { before: b, after: a, changePercent: b === 0 ? 0 : Math.round(((a - b) / b) * 1000) / 10 };
}

export function profileDiff(before: Obj, after: Obj): Obj {
  const mspt = metric(before, after, "ticks", "msptAvg");
  const verdict = mspt.before === 0 && mspt.after === 0
    ? "no tick data (profile a server, not a client)"
    : mspt.changePercent <= -5 ? "faster" : mspt.changePercent >= 5 ? "slower" : "about the same";
  return {
    verdict,
    msptAvg: mspt,
    msptP95: metric(before, after, "ticks", "p95"),
    msptMax: metric(before, after, "ticks", "max"),
    slowTicks: metric(before, after, "ticks", "over50ms"),
    busyPercent: metric(before, after, "busyPercent"),
    owners: changes(shares(before.owners, "owner"), shares(after.owners, "owner"), 10),
    hotMethods: changes(shares(before.hotMethods, "method"), shares(after.hotMethods, "method"), 10),
  };
}
