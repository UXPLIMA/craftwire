/**
 * The web panel at /panel on `craftwire serve`: the page, its script, sign-in and sign-out, and the read-only data
 * API under /panel/api. Sign-in posts must come from the panel's own origin; the API only reads.
 */
import type { IncomingMessage, ServerResponse } from "node:http";
import { RateLimiter } from "../http/gate.js";
import type { Person } from "../team/tokens.js";
import type { ToolContext } from "../tools/registry.js";
import { apiCall, sendJson } from "./panel-api.js";
import { PanelAuth } from "./panel-auth.js";
import { PANEL_CSP, PANEL_HTML, PANEL_JS } from "./panel-page.js";

const MAX_LOGIN_BYTES = 4096;

export function createPanel(ctx: ToolContext, authenticate: (token: string) => Person | undefined, secure: boolean,
  now: () => number = Date.now): (req: IncomingMessage, res: ServerResponse) => Promise<void> {
  const auth = new PanelAuth(authenticate, secure, now);
  const logins = new RateLimiter(10, now);
  return async (req, res) => {
    const path = new URL(req.url ?? "/", "http://hub").pathname;
    if (req.method === "GET" && (path === "/panel" || path === "/panel/")) return page(res, "text/html; charset=utf-8", PANEL_HTML);
    if (req.method === "GET" && path === "/panel/app.js") return page(res, "text/javascript; charset=utf-8", PANEL_JS);
    if (path === "/panel/login" || path === "/panel/logout") {
      if (req.method !== "POST") return sendJson(res, { error: "use POST" }, 405);
      if (!sameOrigin(req, secure)) return sendJson(res, { error: "sign in from the panel page" }, 403);
      if (path === "/panel/logout") {
        res.writeHead(204, { "set-cookie": auth.signOut(req) });
        return void res.end();
      }
      if (logins.take() !== undefined) return sendJson(res, { error: "too many sign-in attempts; wait a minute" }, 429);
      const cookie = auth.signIn(await tokenFrom(req));
      if (cookie === undefined) return sendJson(res, { error: "wrong token" }, 401);
      res.writeHead(204, { "set-cookie": cookie });
      return void res.end();
    }
    const person = auth.person(req);
    if (person === undefined) return sendJson(res, { error: "sign in first" }, 401);
    if (req.method !== "GET" || !path.startsWith("/panel/api/")) return sendJson(res, { error: "not found" }, 404);
    const name = path.slice("/panel/api/".length);
    if (name === "me") return sendJson(res, { name: person.name, role: person.role });
    return apiCall({ ...ctx, user: person.name }, person, name, req, res);
  };
}

function page(res: ServerResponse, type: string, body: string): void {
  res.writeHead(200, {
    "content-type": type, "content-security-policy": PANEL_CSP, "x-content-type-options": "nosniff",
    "referrer-policy": "no-referrer", "cache-control": "no-store",
  });
  res.end(body);
}

/** The Origin of a sign-in must be this hub as the browser reached it (scheme and Host). */
function sameOrigin(req: IncomingMessage, secure: boolean): boolean {
  const origin = req.headers.origin;
  const host = req.headers.host;
  return origin !== undefined && host !== undefined && origin.toLowerCase() === `${secure ? "https" : "http"}://${host}`.toLowerCase();
}

async function tokenFrom(req: IncomingMessage): Promise<string> {
  let size = 0;
  const parts: Buffer[] = [];
  for await (const chunk of req) {
    size += (chunk as Buffer).length;
    if (size > MAX_LOGIN_BYTES) return "";
    parts.push(chunk as Buffer);
  }
  try {
    const body = JSON.parse(Buffer.concat(parts).toString("utf8")) as { token?: unknown };
    return typeof body.token === "string" ? body.token : "";
  } catch {
    return "";
  }
}
