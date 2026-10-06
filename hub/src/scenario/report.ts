import type { ScenarioReport } from "./runner.js";

const short = (v: unknown, max = 300) => {
  const s = typeof v === "string" ? v : JSON.stringify(v);
  return s.length <= max ? s : `${s.slice(0, max)}…`;
};
const seconds = (ms: number) => `${(ms / 1000).toFixed(1)} s`;

/** A scenario's result for a terminal: one line when it passed, the failure and its context when not. */
export function formatReport(r: ScenarioReport): string {
  const lines = [`${r.passed ? "✓" : "✗"} ${r.name} (${seconds(r.durationMs)})`];
  const f = r.failure;
  if (f) {
    lines.push(`    ${f.phase === "steps" ? "" : `${f.phase} `}step ${f.index + 1} "${f.step}": ${f.message}`);
    if (f.expected !== undefined) lines.push(`      expected: ${short(f.expected)}`);
    if (f.actual !== undefined) lines.push(`      actual:   ${short(f.actual)}`);
    for (const [k, v] of Object.entries(f.context ?? {})) {
      if (v === undefined || (Array.isArray(v) && v.length === 0)) continue;
      lines.push(`      ${k}: ${short(v, 500)}`);
    }
  }
  for (const e of r.cleanupErrors ?? []) lines.push(`    cleanup: ${e}`);
  if (r.newExceptions > 0) lines.push(`    ${r.newExceptions} new exception group(s) were logged; see the exceptions tool`);
  return lines.join("\n");
}

export function formatSummary(reports: ScenarioReport[]): string {
  const failed = reports.filter((r) => !r.passed).length;
  return `${reports.length} scenario(s): ${reports.length - failed} passed, ${failed} failed`;
}

const xml = (s: string) => s.replace(/[<>&"']/g, (c) => ({ "<": "&lt;", ">": "&gt;", "&": "&amp;", '"': "&quot;", "'": "&apos;" })[c]!)
  // XML 1.0 has no escape for most control characters: drop them.
  .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, "");

/** JUnit XML, the format CI systems read: one testcase per scenario. */
export function toJUnit(reports: ScenarioReport[], suite = "craftwire"): string {
  const failures = reports.filter((r) => !r.passed).length;
  const total = reports.reduce((t, r) => t + r.durationMs, 0);
  const cases = reports.map((r) => {
    const head = `  <testcase classname="${xml(suite)}" name="${xml(r.name)}" time="${(r.durationMs / 1000).toFixed(3)}"`;
    if (r.passed) return `${head}/>`;
    const message = r.failure ? `${r.failure.step}: ${r.failure.message}` : `cleanup failed`;
    return `${head}>\n    <failure message="${xml(message)}">${xml(formatReport(r))}</failure>\n  </testcase>`;
  });
  return `<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="${xml(suite)}" tests="${reports.length}" failures="${failures}" time="${(total / 1000).toFixed(3)}">\n${cases.join("\n")}\n</testsuite>\n`;
}
