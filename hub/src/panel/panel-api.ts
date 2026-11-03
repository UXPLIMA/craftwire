/**
 * The web panel's data: instances and their health, bots of every kind, logs, a client's latest screenshot and a
 * live MJPEG view, a top-down world map, and the audit log's sessions for step-by-step replay. Each endpoint is
 * allowed only if the person's role offers the tool it stands for (the replay needs a dev or owner role, since it
 * shows everyone's arguments).
 */
import type { IncomingMessage, ServerResponse } from "node:http";
import { recordScenario } from "../scenario/record.js";
import type { ToolContext } from "../tools/registry.js";
import { groupLogs } from "../tools/log-tools.js";
import { botAgents, broadcast } from "../tools/bots/route.js";
import { merge } from "../tools/bots/merge.js";
import type { Person } from "../team/tokens.js";

const LIVE_FPS_MAX = 5;
const LIVE_STREAMS_MAX = 4;

type Handler = (ctx: ToolContext, q: URLSearchParams, res: ServerResponse, req: IncomingMessage) => Promise<void>;

/** Endpoint, the tool whose permission it needs, and its handler. */
const ROUTES: Record<string, { tool: string; run: Handler }> = {
  instances: { tool: "list_instances", run: async (c, _q, res) => sendJson(res, instances(c)) },
  bots: { tool: "bot_list", run: async (c, _q, res) => sendJson(res, await bots(c)) },
  logs: { tool: "logs", run: async (c, q, res) => sendJson(res, logs(c, q)) },
  screenshot: { tool: "screenshot", run: screenshot },
  live: { tool: "screenshot", run: live },
  map: { tool: "world_render", run: map },
  sessions: { tool: "replay", run: async (c, _q, res) => sendJson(res, c.audit.sessions()) },
  session: { tool: "replay", run: async (c, q, res) => sendJson(res, c.audit.calls(q.get("id") ?? "local")) },
  scenario: { tool: "replay", run: scenario },
};

let liveStreams = 0;

export async function apiCall(ctx: ToolContext, person: Person, name: string, req: IncomingMessage, res: ServerResponse): Promise<void> {
  const route = ROUTES[name];
  if (route === undefined) return sendJson(res, { error: "unknown panel endpoint" }, 404);
  if (!allowed(person, route.tool)) return sendJson(res, { error: `your role (${person.role}) does not include ${route.tool}` }, 403);
  const q = new URL(req.url ?? "/", "http://hub").searchParams;
  try {
    await route.run(ctx, q, res, req);
  } catch (e) {
    if (!res.headersSent) sendJson(res, { error: e instanceof Error ? e.message : String(e) }, 502);
    else res.end();
  }
}

function allowed(p: Person, tool: string): boolean {
  if (tool === "replay") return p.role === "owner" || p.role === "dev";
  return p.policy?.lists(tool) ?? true;
}

export function sendJson(res: ServerResponse, body: unknown, status = 200): void {
  res.writeHead(status, { "content-type": "application/json", "cache-control": "no-store" });
  res.end(JSON.stringify(body));
}

/** A session as a .cwtest.json download: the replay tab's "save as scenario". */
async function scenario(c: ToolContext, q: URLSearchParams, res: ServerResponse): Promise<void> {
  const id = q.get("id") ?? "local";
  const num = (k: string) => (q.get(k) ? Number(q.get(k)) : undefined);
  const from = num("from");
  const to = num("to");
  const r = recordScenario(c.audit.calls(id), { name: `session ${id}`, ...(from ? { from } : {}), ...(to ? { to } : {}) });
  const file = `session-${id.replace(/[^A-Za-z0-9_-]/g, "").slice(0, 40) || "local"}.cwtest.json`;
  res.writeHead(200, { "content-type": "application/json", "cache-control": "no-store", "content-disposition": `attachment; filename="${file}"` });
  res.end(JSON.stringify(r.scenario, null, 2));
}

function instances(c: ToolContext): unknown {
  const t = Date.now();
  return c.agents.instances().map((i) => ({ ...i, upSeconds: Math.round((t - i.connectedAt) / 1000) }));
}

async function bots(c: ToolContext): Promise<unknown> {
  const agents = botAgents(c);
  if (agents.length === 0) return { count: 0, bots: [] };
  return merge(await broadcast(c, agents, "bot.list", { limit: 1000 }, 20_000), { sum: ["count", "joining"], concat: ["bots"], limit: 1000 });
}

function logs(c: ToolContext, q: URLSearchParams): unknown {
  const inst = c.agents.resolveAny(q.get("instance") ?? undefined);
  const limit = Math.min(1000, Math.max(1, Number(q.get("limit") ?? 200) || 200));
  return { instance: inst.id, lines: groupLogs(c.agents.events(inst.id)).slice(-limit) };
}

async function shot(c: ToolContext, instance: string | null, maxSize: number): Promise<Buffer> {
  const inst = c.agents.resolve("client", instance ?? undefined);
  const r = (await c.agents.request(inst.id, "screenshot", { maxSize, format: "jpeg", hud: true, chat: true }, 20_000)) as { data: string };
  return Buffer.from(r.data, "base64");
}

async function screenshot(c: ToolContext, q: URLSearchParams, res: ServerResponse): Promise<void> {
  const jpeg = await shot(c, q.get("instance"), clampSize(q.get("size")));
  res.writeHead(200, { "content-type": "image/jpeg", "cache-control": "no-store" });
  res.end(jpeg);
}

/** A live view as MJPEG (one JPEG part after another), which an <img> shows without any script. */
async function live(c: ToolContext, q: URLSearchParams, res: ServerResponse, req: IncomingMessage): Promise<void> {
  if (liveStreams >= LIVE_STREAMS_MAX) return sendJson(res, { error: "too many live views open" }, 503);
  const fps = Math.min(LIVE_FPS_MAX, Math.max(1, Number(q.get("fps") ?? 2) || 2));
  const size = clampSize(q.get("size"));
  let open = true;
  req.once("close", () => { open = false; });
  liveStreams++;
  try {
    res.writeHead(200, { "content-type": "multipart/x-mixed-replace; boundary=frame", "cache-control": "no-store" });
    while (open) {
      const started = Date.now();
      const jpeg = await shot(c, q.get("instance"), size);
      if (!open) break;
      res.write(`--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.length}\r\n\r\n`);
      res.write(jpeg);
      res.write("\r\n");
      await new Promise((r) => setTimeout(r, Math.max(0, 1000 / fps - (Date.now() - started))));
    }
  } finally {
    liveStreams--;
    res.end();
  }
}

/** world.render top view of x1..x2, z1..z2 (at most 512 blocks a side), with players drawn by the server. */
async function map(c: ToolContext, q: URLSearchParams, res: ServerResponse): Promise<void> {
  const inst = c.agents.resolve("server", q.get("instance") ?? undefined);
  const n = (k: string) => Math.trunc(Number(q.get(k) ?? 0) || 0);
  const x1 = n("x1");
  const z1 = n("z1");
  const x2 = Math.min(n("x2"), x1 + 511);
  const z2 = Math.min(n("z2"), z1 + 511);
  const params = { x1, z1, x2, z2, view: "top", scale: 2, grid: 0, players: true, ...(q.get("world") ? { world: q.get("world") } : {}) };
  const r = (await c.agents.request(inst.id, "world.render", params, 60_000)) as { mime: string; data: string };
  res.writeHead(200, { "content-type": r.mime, "cache-control": "no-store" });
  res.end(Buffer.from(r.data, "base64"));
}

function clampSize(v: string | null): number {
  return Math.min(1600, Math.max(256, Number(v ?? 960) || 960));
}
