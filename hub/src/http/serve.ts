import { randomUUID } from "node:crypto";
import { createServer as createHttpServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { createServer as createHttpsServer } from "node:https";
import type { AddressInfo } from "node:net";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { tokensEqual } from "../config.js";
import { allowedHosts, bearer, hostAllowed, isLoopback, originAllowed, RateLimiter } from "./gate.js";

export interface HttpOptions {
  host: string;
  port: number;
  token: string;
  /** A fresh MCP server per session, all on the same hub. */
  makeServer: () => McpServer;
  allowRemote?: boolean;
  tls?: { cert: Buffer; key: Buffer };
  /** Extra Host names (e.g. a DNS name of this machine) and browser origins to accept. */
  allowHosts?: string[];
  allowOrigins?: string[];
  rateLimitPerMinute?: number;
  maxBodyBytes?: number;
  sessionIdleMs?: number;
  maxSessions?: number;
  now?: () => number;
}

interface Session {
  transport: StreamableHTTPServerTransport;
  server: McpServer;
  lastSeen: number;
  openStreams: number;
}

export interface HttpHub {
  url: string;
  port: number;
  sessions(): number;
  close(): Promise<void>;
}

const json = (res: ServerResponse, status: number, message: string, headers: Record<string, string> = {}) => {
  res.writeHead(status, { "content-type": "application/json", ...headers });
  res.end(JSON.stringify({ jsonrpc: "2.0", error: { code: -32000, message }, id: null }));
};

/**
 * MCP over Streamable HTTP at /mcp for `craftwire serve`. Every request needs the bearer token; Host and Origin are
 * checked against DNS rebinding; requests are rate limited; idle sessions expire.
 */
export async function startHttpServer(o: HttpOptions): Promise<HttpHub> {
  if (!isLoopback(o.host) && !o.allowRemote) {
    throw new Error(`Listening on ${o.host} lets other machines reach the hub. Pass --allow-remote to do that on purpose.`);
  }
  const now = o.now ?? Date.now;
  const limiter = new RateLimiter(o.rateLimitPerMinute ?? 120, now);
  const sessions = new Map<string, Session>();
  const idleMs = o.sessionIdleMs ?? 30 * 60_000;
  let hosts: Set<string> | undefined;

  const closeSession = async (id: string) => {
    const s = sessions.get(id);
    if (!s) return;
    sessions.delete(id);
    await s.transport.close().catch(() => undefined);
    await s.server.close().catch(() => undefined);
  };

  const handle = async (req: IncomingMessage, res: ServerResponse) => {
    const path = new URL(req.url ?? "/", "http://hub").pathname;
    if (path !== "/mcp") return json(res, 404, "Not found: the MCP endpoint is /mcp");
    if (!hostAllowed(req.headers.host, hosts)) return json(res, 421, "This Host is not served here");
    if (!originAllowed(req.headers.origin, o.allowOrigins ?? [])) return json(res, 403, "Requests from web pages are not accepted");
    const token = bearer(req.headers.authorization);
    if (token === undefined || !tokensEqual(token, o.token)) {
      return json(res, 401, "Missing or wrong bearer token", { "www-authenticate": 'Bearer realm="craftwire"' });
    }
    const wait = limiter.take();
    if (wait !== undefined) return json(res, 429, "Too many requests", { "retry-after": String(wait) });

    const id = req.headers["mcp-session-id"];
    if (typeof id === "string") {
      const s = sessions.get(id);
      if (!s) return json(res, 404, "Session not found or expired: start a new one");
      s.lastSeen = now();
      s.openStreams++;
      res.once("close", () => { s.openStreams--; s.lastSeen = now(); });
      return s.transport.handleRequest(req, res);
    }
    if (req.method !== "POST") return json(res, 400, "Start a session with an initialize request");
    if (sessions.size >= (o.maxSessions ?? 32)) return json(res, 503, "Too many sessions");

    const server = o.makeServer();
    const transport = new StreamableHTTPServerTransport({
      sessionIdGenerator: () => randomUUID(),
      onsessioninitialized: (sid) => { sessions.set(sid, { transport, server, lastSeen: now(), openStreams: 0 }); },
      onsessionclosed: (sid) => { void closeSession(sid); },
      maxRequestBodySize: o.maxBodyBytes ?? 4 * 1024 * 1024,
    });
    await server.connect(transport);
    await transport.handleRequest(req, res);
    // Not an initialize request: no session came of it.
    if (transport.sessionId === undefined) {
      await transport.close().catch(() => undefined);
      await server.close().catch(() => undefined);
    }
  };

  const listener = (req: IncomingMessage, res: ServerResponse) => {
    handle(req, res).catch((e: unknown) => {
      if (!res.headersSent) json(res, 500, e instanceof Error ? e.message : String(e));
      else res.end();
    });
  };
  const http: Server = o.tls ? createHttpsServer({ cert: o.tls.cert, key: o.tls.key }, listener) : createHttpServer(listener);
  await new Promise<void>((resolve, reject) => {
    http.once("error", reject);
    http.listen(o.port, o.host, () => { http.off("error", reject); resolve(); });
  });
  const port = (http.address() as AddressInfo).port;
  hosts = allowedHosts(o.host, port, o.allowHosts ?? []);

  const sweep = setInterval(() => {
    const cutoff = now() - idleMs;
    for (const [sid, s] of sessions) if (s.openStreams <= 0 && s.lastSeen < cutoff) void closeSession(sid);
  }, Math.min(60_000, Math.max(1000, idleMs / 4)));
  sweep.unref();

  const shown = o.host.includes(":") && !o.host.startsWith("[") ? `[${o.host}]` : o.host;
  return {
    url: `${o.tls ? "https" : "http"}://${shown}:${port}/mcp`,
    port,
    sessions: () => sessions.size,
    close: async () => {
      clearInterval(sweep);
      await Promise.all([...sessions.keys()].map(closeSession));
      http.closeAllConnections();
      await new Promise<void>((r) => http.close(() => r()));
    },
  };
}
