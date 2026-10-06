/** A step of a path: a key, an index (negative counts from the end) or every element. */
type Segment = { key: string } | { index: number } | { all: true };

export interface Selection {
  /** Whether the path led anywhere (for `[*]`: the array was reached, even if empty). */
  found: boolean;
  values: unknown[];
}

const TOKEN = /\.?([A-Za-z_$][\w$-]*)|\[(-?\d+)\]|\[\*\]|\["((?:[^"\\]|\\.)*)"\]/y;

/** Parses `a.b[0].c`, `items[-1]`, `lines[*].text`, `["odd key"]`. */
export function parsePath(path: string): Segment[] {
  const out: Segment[] = [];
  TOKEN.lastIndex = 0;
  while (TOKEN.lastIndex < path.length) {
    const at = TOKEN.lastIndex;
    const m = TOKEN.exec(path);
    if (!m || (at === 0 && path[0] === ".")) throw new Error(`Invalid path "${path}" at character ${at + 1}`);
    if (m[1] !== undefined) out.push({ key: m[1] });
    else if (m[2] !== undefined) out.push({ index: Number(m[2]) });
    else if (m[3] !== undefined) out.push({ key: JSON.parse(`"${m[3]}"`) as string });
    else out.push({ all: true });
  }
  return out;
}

/** Every value the path leads to in `root`. */
export function select(root: unknown, path: string): Selection {
  let current: unknown[] = [root];
  let found = true;
  for (const seg of parsePath(path)) {
    const next: unknown[] = [];
    for (const v of current) {
      if ("all" in seg) {
        if (Array.isArray(v)) next.push(...v);
        else found = false;
      } else if ("index" in seg) {
        if (!Array.isArray(v)) continue;
        const i = seg.index < 0 ? v.length + seg.index : seg.index;
        if (i >= 0 && i < v.length) next.push(v[i]);
      } else if (v !== null && typeof v === "object" && Object.hasOwn(v, seg.key)) {
        next.push((v as Record<string, unknown>)[seg.key]);
      }
    }
    if ("all" in seg) {
      if (!found) return { found: false, values: [] };
    } else if (next.length === 0) {
      return { found: false, values: [] };
    }
    current = next;
  }
  return { found, values: current };
}
