/**
 * The web panel's page and script, served by the hub itself (no external files, a strict content security policy).
 * Everything the page shows from the game (names, log lines, arguments) goes in through textContent, never as HTML.
 */

export const PANEL_CSP = "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; img-src 'self'; connect-src 'self'; "
  + "frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

export const PANEL_HTML = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Craftwire panel</title>
<style>
:root{--bg:#f6f7f9;--fg:#1d2230;--muted:#6b7280;--card:#fff;--line:#e3e6ec;--accent:#2f6fde;--bad:#c0392b;--good:#1e8e5a}
@media (prefers-color-scheme:dark){:root{--bg:#14171d;--fg:#e6e8ee;--muted:#9aa3b2;--card:#1c2028;--line:#2b313c;--accent:#6aa0ff}}
*{box-sizing:border-box}body{margin:0;font:14px/1.45 system-ui,sans-serif;background:var(--bg);color:var(--fg)}
header{display:flex;gap:16px;align-items:center;padding:10px 16px;border-bottom:1px solid var(--line);background:var(--card)}
header b{font-size:15px}nav button{background:none;border:0;color:var(--muted);padding:6px 8px;cursor:pointer;font:inherit}
nav button.on{color:var(--fg);border-bottom:2px solid var(--accent)}#who{margin-left:auto;color:var(--muted)}
main{padding:16px;max-width:1200px;margin:0 auto}
.card{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:12px;margin-bottom:12px}
table{width:100%;border-collapse:collapse}td,th{text-align:left;padding:4px 8px;border-bottom:1px solid var(--line);vertical-align:top}
th{color:var(--muted);font-weight:500}pre{white-space:pre-wrap;word-break:break-word;margin:0;font:12px/1.4 ui-monospace,monospace}
.err{color:var(--bad)}.ok{color:var(--good)}img.view{max-width:100%;border-radius:6px;border:1px solid var(--line)}
input,select,button.act{font:inherit;padding:6px 8px;border:1px solid var(--line);border-radius:6px;background:var(--card);color:var(--fg)}
.row{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin-bottom:8px}
.step{cursor:pointer}.step.sel{background:rgba(47,111,222,.12)}#login{max-width:420px;margin:80px auto}
</style></head><body>
<header><b>Craftwire</b><nav id="tabs"></nav><span id="who"></span></header>
<main id="main"></main>
<script src="/panel/app.js"></script>
</body></html>`;

export const PANEL_JS = `"use strict";
function $(tag, attrs, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (k === "on") for (const [ev, f] of Object.entries(v)) e.addEventListener(ev, f);
    else e.setAttribute(k, v);
  }
  for (const k of kids) e.append(k instanceof Node ? k : document.createTextNode(String(k ?? "")));
  return e;
}
const main = document.getElementById("main");
const enc = encodeURIComponent;
async function api(path) {
  const r = await fetch("/panel/api/" + path, { credentials: "same-origin" });
  if (r.status === 401) { login(); throw new Error("signed out"); }
  const j = await r.json();
  if (!r.ok) throw new Error(j.error || r.statusText);
  return j;
}
const TABS = { instances: showInstances, bots: showBots, logs: showLogs, client: showClient, replay: showReplay };
let timer;
function every(ms, f) { timer = setInterval(() => f().catch(() => {}), ms); }
function tab(name) {
  clearInterval(timer);
  for (const b of document.querySelectorAll("nav button")) b.classList.toggle("on", b.dataset.t === name);
  main.replaceChildren();
  TABS[name]().catch((e) => main.append($("p", { class: "err" }, e.message)));
}
function login(msg) {
  document.getElementById("tabs").replaceChildren();
  main.replaceChildren();
  const t = $("input", { type: "password", placeholder: "token", style: "width:100%" });
  const submit = async (ev) => {
    ev.preventDefault();
    const body = JSON.stringify({ token: t.value });
    const r = await fetch("/panel/login", { method: "POST", headers: { "content-type": "application/json" }, body });
    if (r.ok) start(); else login("That token is not accepted.");
  };
  main.append($("form", { id: "login", class: "card", on: { submit } },
    $("p", {}, "Paste your Craftwire token: craftwire serve prints the owner's, craftwire token add makes one per person."), t,
    $("p", {}, $("button", { class: "act", type: "submit" }, "Sign in")), msg ? $("p", { class: "err" }, msg) : ""));
  t.focus();
}
async function start() {
  const me = await api("me");
  document.getElementById("who").textContent = me.name + " (" + me.role + ")";
  const nav = document.getElementById("tabs");
  nav.replaceChildren(...Object.keys(TABS).map((n) => $("button", { "data-t": n, on: { click: () => tab(n) } }, n)));
  tab("instances");
}
function table(cols, rows) {
  return $("table", {}, $("tr", {}, ...cols.map((c) => $("th", {}, c))), ...rows.map((r) => $("tr", {}, ...r.map((v) => $("td", {}, v)))));
}
async function showInstances() {
  const card = $("div", { class: "card" });
  main.append(card);
  const draw = async () => {
    const list = await api("instances");
    const rows = list.map((i) => [i.name, i.kind, i.mcVersion, i.agentVersion, i.upSeconds + " s"]);
    card.replaceChildren(table(["name", "kind", "Minecraft", "agent", "up"], rows));
  };
  await draw();
  every(5000, draw);
}
const at = (b, k) => (b.pos && typeof b.pos[k] === "number" ? b.pos[k] : b[k]);
async function showBots() {
  const card = $("div", { class: "card" });
  const mapCard = $("div", { class: "card" });
  main.append(card, mapCard);
  const draw = async () => {
    const r = await api("bots");
    const bots = r.bots || [];
    const rows = bots.map((b) => [b.name, b.kind, b.group ?? "", b.world ?? "", ...["x", "y", "z"].map((k) => Math.round(at(b, k) ?? 0))]);
    card.replaceChildren($("p", {}, (r.count ?? bots.length) + " bots, " + (r.joining ?? 0) + " joining"),
      table(["name", "kind", "group", "world", "x", "y", "z"], rows));
    const xs = bots.map((b) => at(b, "x")).filter((v) => typeof v === "number");
    const zs = bots.map((b) => at(b, "z")).filter((v) => typeof v === "number");
    if (xs.length === 0) { mapCard.replaceChildren($("p", {}, "No bot positions to map.")); return; }
    const q = new URLSearchParams({ x1: Math.floor(Math.min(...xs)) - 24, z1: Math.floor(Math.min(...zs)) - 24,
      x2: Math.ceil(Math.max(...xs)) + 24, z2: Math.ceil(Math.max(...zs)) + 24, t: Date.now() });
    mapCard.replaceChildren($("img", { class: "view", alt: "map", src: "/panel/api/map?" + q }));
  };
  await draw();
  every(10000, draw);
}
async function showLogs() {
  const list = await api("instances");
  const sel = $("select", {}, ...list.map((i) => $("option", { value: i.id }, i.name + " (" + i.kind + ")")));
  const out = $("pre");
  main.append($("div", { class: "row" }, sel), $("div", { class: "card" }, out));
  const line = (l) => new Date(l.time).toLocaleTimeString() + " " + l.level + " " + l.message
    + (l.stack ? "\\n  " + l.stack.join("\\n  ") : "");
  const draw = async () => {
    if (!sel.value) return;
    const r = await api("logs?limit=300&instance=" + enc(sel.value));
    out.replaceChildren(...r.lines.map((l) => $("div", { class: l.level === "ERROR" ? "err" : "" }, line(l))));
  };
  sel.addEventListener("change", () => draw());
  await draw();
  every(3000, draw);
}
async function showClient() {
  const list = (await api("instances")).filter((i) => i.kind === "client");
  if (list.length === 0) { main.append($("p", {}, "No game client is connected.")); return; }
  const sel = $("select", {}, ...list.map((i) => $("option", { value: i.id }, i.name)));
  const img = $("img", { class: "view", alt: "client view" });
  const still = () => { img.src = "/panel/api/screenshot?instance=" + enc(sel.value) + "&t=" + Date.now(); };
  const live = () => { img.src = "/panel/api/live?fps=2&instance=" + enc(sel.value); };
  main.append($("div", { class: "row" }, sel, $("button", { class: "act", on: { click: still } }, "screenshot"),
    $("button", { class: "act", on: { click: live } }, "live")), $("div", { class: "card" }, img));
  still();
}
async function showReplay() {
  const sessions = await api("sessions");
  if (sessions.length === 0) { main.append($("p", {}, "No tool calls yet.")); return; }
  const label = (s) => s.user + " - " + s.calls + " calls - " + new Date(s.last).toLocaleString();
  const sel = $("select", {}, ...sessions.map((s) => $("option", { value: s.session }, label(s))));
  const steps = $("div", { class: "card" });
  const detail = $("pre");
  main.append($("div", { class: "row" }, sel), steps, $("div", { class: "card" }, detail));
  const step = (c, n) => {
    const row = $("div", { class: "step" }, (n + 1) + ". " + new Date(c.time).toLocaleTimeString() + "  " + c.tool + "  ",
      $("span", { class: c.ok ? "ok" : "err" }, c.ok ? "ok" : "failed"), "  " + c.ms + " ms" + (c.user ? "  " + c.user : ""));
    row.addEventListener("click", () => {
      for (const s of steps.children) s.classList.remove("sel");
      row.classList.add("sel");
      detail.textContent = JSON.stringify({ args: c.args, result: c.result, error: c.error }, null, 2);
    });
    return row;
  };
  const draw = async () => { steps.replaceChildren(...(await api("session?id=" + enc(sel.value))).map(step)); };
  sel.addEventListener("change", () => draw());
  await draw();
}
start().catch(() => login());
`;
