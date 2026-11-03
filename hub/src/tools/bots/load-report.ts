/** The shareable HTML page of a load test: MSPT and TPS against the number of bots, and a table of every step. */
import { mkdirSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import type { StepResult } from "./load-test.js";

export interface LoadReport {
  server: string;
  kind: string;
  behavior?: string;
  steps: StepResult[];
}

const W = 640;
const H = 260;
const PAD = 40;

function esc(s: string): string {
  return s.replace(/[&<>"]/g, (ch) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;" })[ch]!);
}

/** A polyline of one series over the step bot counts. */
function line(steps: StepResult[], value: (s: StepResult) => number, top: number, color: string): string {
  const maxBots = Math.max(...steps.map((s) => s.bots), 1);
  const pts = steps.map((s) => {
    const x = PAD + ((W - 2 * PAD) * s.bots) / maxBots;
    const y = H - PAD - ((H - 2 * PAD) * Math.min(value(s), top)) / top;
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  });
  return `<polyline fill="none" stroke="${color}" stroke-width="2" points="${pts.join(" ")}"/>`
    + pts.map((p) => `<circle r="3" fill="${color}" cx="${p.split(",")[0]}" cy="${p.split(",")[1]}"/>`).join("");
}

export function renderLoadReport(r: LoadReport): string {
  const topMspt = Math.max(50, ...r.steps.map((s) => s.msptP95));
  const rows = r.steps.map((s) => `<tr><td>${s.bots}</td><td>${s.tps}</td><td>${s.msptAvg}</td><td>${s.msptP95}</td><td>${s.msptMax}</td>`
    + `<td>${s.slowTicks}</td><td>${s.heapMb}</td><td>${s.chunks}</td><td>${s.spawnMs}</td>`
    + `<td>${s.topOwners.map((o) => `${esc(o.owner)} ${o.percent}%`).join("<br>")}</td></tr>`).join("");
  const limit = H - PAD - ((H - 2 * PAD) * 50) / topMspt;
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Load test ${esc(r.server)}</title>
<style>:root{--bg:#fff;--fg:#1b1b1b;--muted:#666;--line:#ddd}
@media (prefers-color-scheme:dark){:root{--bg:#141414;--fg:#eee;--muted:#999;--line:#333}}
body{background:var(--bg);color:var(--fg);font:14px/1.5 system-ui,sans-serif;margin:0 auto;max-width:960px;padding:16px}
table{border-collapse:collapse;width:100%}td,th{border-bottom:1px solid var(--line);padding:4px 8px;text-align:right;vertical-align:top}
td:last-child,th:last-child{text-align:left}svg{max-width:100%;height:auto}.muted{color:var(--muted)}</style></head><body>
<h1>Load test: ${esc(r.server)}</h1>
<p class="muted">${esc(r.kind)} bots${r.behavior ? `, behavior ${esc(r.behavior)}` : ""}; ${new Date().toISOString()}</p>
<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="MSPT by bot count">
<line x1="${PAD}" y1="${limit.toFixed(1)}" x2="${W - PAD}" y2="${limit.toFixed(1)}" stroke="#c33" stroke-dasharray="4 4"/>
<text x="${W - PAD}" y="${(limit - 4).toFixed(1)}" text-anchor="end" fill="#c33" font-size="11">50 ms (20 TPS)</text>
${line(r.steps, (s) => s.msptAvg, topMspt, "#2b7bd8")}${line(r.steps, (s) => s.msptP95, topMspt, "#e08a1e")}
<text x="${PAD}" y="${PAD - 10}" fill="currentColor" font-size="11">MSPT avg (blue) and p95 (orange), up to ${topMspt.toFixed(0)} ms</text>
<text x="${W - PAD}" y="${H - 10}" text-anchor="end" fill="currentColor" font-size="11">bots</text></svg>
<table><thead><tr><th>bots</th><th>TPS</th><th>MSPT avg</th><th>p95</th><th>max</th><th>&gt;50 ms</th><th>heap MB</th><th>chunks</th>
<th>spawn ms</th><th>top owners</th></tr></thead><tbody>${rows}</tbody></table></body></html>`;
}

/** Writes the report under <home>/reports and returns its path. */
export function writeLoadReport(home: string | undefined, r: LoadReport): string {
  const dir = join(home ?? join(homedir(), ".craftwire"), "reports");
  mkdirSync(dir, { recursive: true });
  const file = join(dir, `load-${new Date().toISOString().replace(/[:.]/g, "-")}.html`);
  writeFileSync(file, renderLoadReport(r));
  return file;
}
