export interface BuildError {
  file: string;
  line: number;
  column?: number;
  message: string;
}

const JAVAC = /^(.+?\.java):(\d+): error: (.+)$/;
const KOTLIN_URL = /^e: (file:\/\/\S+?\.kts?):(\d+):(\d+) (.+)$/;
const KOTLIN_OLD = /^e: (.+?\.kts?): \((\d+), (\d+)\): (.+)$/;
const MAVEN = /^\[ERROR\] (.+?\.(?:java|kt)):\[(\d+),(\d+)\] (.+)$/;
// javac prints these under "cannot find symbol" and similar errors.
const DETAIL = /^\s+(symbol|location):\s*(.+)$/;

/** Compiler errors from Gradle (javac, kotlinc) or Maven output, deduplicated, at most `max`. */
export function parseBuildErrors(lines: string[], max = 50): BuildError[] {
  const clean = lines.map((l) => l.replace(/\r$/, ""));
  const out: BuildError[] = [];
  const seen = new Set<string>();
  for (let i = 0; i < clean.length && out.length < max; i++) {
    const err = matchLine(clean[i]!);
    if (!err) continue;
    if (JAVAC.test(clean[i]!)) {
      for (let j = i + 1; j < Math.min(clean.length, i + 5) && !matchLine(clean[j]!); j++) {
        const d = DETAIL.exec(clean[j]!);
        if (d) err.message += `; ${d[1]}: ${d[2]!.trim()}`;
      }
    }
    const key = `${err.file}:${err.line}:${err.message}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(err);
  }
  return out;
}

function matchLine(line: string): BuildError | undefined {
  let m = JAVAC.exec(line);
  if (m) return { file: m[1]!, line: Number(m[2]), message: m[3]! };
  m = KOTLIN_URL.exec(line);
  if (m) return { file: fileUrlToPath(m[1]!), line: Number(m[2]), column: Number(m[3]), message: m[4]! };
  m = KOTLIN_OLD.exec(line);
  if (m) return { file: m[1]!, line: Number(m[2]), column: Number(m[3]), message: m[4]! };
  m = MAVEN.exec(line);
  if (m) return { file: m[1]!, line: Number(m[2]), column: Number(m[3]), message: m[4]! };
  return undefined;
}

// url.fileURLToPath rejects drive-less paths on Windows, so build output from either OS is decoded by hand.
function fileUrlToPath(url: string): string {
  const p = decodeURIComponent(url.slice("file://".length));
  return /^\/[A-Za-z]:\//.test(p) ? p.slice(1) : p;
}
