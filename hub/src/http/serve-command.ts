import { existsSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { loadOrCreateHttpToken } from "../config.js";
import { runningHub, startHub } from "../hub.js";
import { createCraftwireServer } from "../server.js";
import { parseToolGroups, ToolPolicy } from "../tool-policy.js";
import { HUB_VERSION } from "../version.js";
import { isLoopback, isWildcard } from "./gate.js";
import { startHttpServer } from "./serve.js";

export const SERVE_USAGE = `Usage: craftwire serve [--port 7777] [--host 127.0.0.1] [options]
  Runs the hub as a long-lived server: MCP over HTTP at /mcp, for several AI clients at once
  (or one on another machine). Games still connect to it over the local WebSocket, as with any hub.
  --port <n>            HTTP port (default 7777)
  --host <address>      address to listen on (default 127.0.0.1, this machine only)
  --allow-remote        required to listen on any other address
  --tls-cert <file>     serve HTTPS with this certificate (PEM) …
  --tls-key <file>      … and its private key
  --read-only           offer only tools that change nothing
  --tools <groups>      offer only these groups: hub, client, server, dev, bots, extensions
  --allow-host <name>   another Host name this machine is reached by (repeatable)
  --allow-origin <url>  a web page origin to accept (repeatable; none by default)
  --rate-limit <n>      requests per minute (default 120)
  --idle <minutes>      end sessions whose client left after this long (default 30)
  --rotate-token        make a new token (clients must be set up again)`;

export interface ServeOptions {
  port: number;
  host: string;
  allowRemote: boolean;
  tlsCert?: string;
  tlsKey?: string;
  readOnly: boolean;
  tools?: Set<string>;
  allowHosts: string[];
  allowOrigins: string[];
  rateLimit: number;
  idleMinutes: number;
  rotateToken: boolean;
}

export function parseServeArgs(argv: string[]): ServeOptions {
  const o: ServeOptions = { port: 7777, host: "127.0.0.1", allowRemote: false, readOnly: false, allowHosts: [], allowOrigins: [], rateLimit: 120, idleMinutes: 30, rotateToken: false };
  const number = (flag: string, v: string, min: number, max: number) => {
    const n = Number(v);
    if (!Number.isInteger(n) || n < min || n > max) throw new Error(`${flag} must be a whole number from ${min} to ${max}`);
    return n;
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]!;
    const value = () => {
      const v = argv[++i];
      if (v === undefined || v.startsWith("--")) throw new Error(`${a} needs a value`);
      return v;
    };
    switch (a) {
      case "--port": o.port = number(a, value(), 1, 65535); break;
      case "--host": o.host = value(); break;
      case "--allow-remote": o.allowRemote = true; break;
      case "--tls-cert": o.tlsCert = value(); break;
      case "--tls-key": o.tlsKey = value(); break;
      case "--read-only": o.readOnly = true; break;
      case "--tools": o.tools = parseToolGroups(value()); break;
      case "--allow-host": o.allowHosts.push(value()); break;
      case "--allow-origin": o.allowOrigins.push(value()); break;
      case "--rate-limit": o.rateLimit = number(a, value(), 1, 100_000); break;
      case "--idle": o.idleMinutes = number(a, value(), 1, 24 * 60); break;
      case "--rotate-token": o.rotateToken = true; break;
      default: throw new Error(`Unknown option ${a}`);
    }
  }
  if ((o.tlsCert === undefined) !== (o.tlsKey === undefined)) throw new Error("--tls-cert and --tls-key go together");
  return o;
}

/** Where a running `craftwire serve` says it is, so `craftwire test` on this machine can use it. */
export interface ServeInfo {
  url: string;
  pid: number;
}

export function readServeInfo(home: string): ServeInfo | undefined {
  try {
    const o = JSON.parse(readFileSync(join(home, "serve.json"), "utf8")) as Partial<ServeInfo>;
    if (typeof o.url !== "string" || typeof o.pid !== "number" || !alive(o.pid)) return undefined;   // left behind by a killed server
    return { url: o.url, pid: o.pid };
  } catch {
    return undefined;
  }
}

function alive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch (e) {
    return (e as NodeJS.ErrnoException).code === "EPERM";
  }
}

export interface Serving {
  url: string;
  agentPort: number;
  token: string;
  close(): Promise<void>;
}

/** Starts the hub with MCP over HTTP; prints how to connect. */
export async function startServe(o: ServeOptions, home: string, log: (msg: string) => void): Promise<Serving> {
  // Checked before anything is written or started.
  if (!isLoopback(o.host) && !o.allowRemote) {
    throw new Error(`Listening on ${o.host} lets other machines reach the hub. Pass --allow-remote to do that on purpose.`);
  }
  const other = await runningHub(home);
  if (other !== undefined) {
    throw new Error(`Another Craftwire hub is running on port ${other} (an AI session using craftwire over stdio). `
      + "Close it first; then point that AI client at this server instead (the command is printed when it starts).");
  }
  const tls = o.tlsCert !== undefined && o.tlsKey !== undefined ? { cert: readFileSync(o.tlsCert), key: readFileSync(o.tlsKey) } : undefined;
  const { token, file, created } = loadOrCreateHttpToken(home, o.rotateToken);
  const hub = await startHub(home, log);
  const policy = o.readOnly || o.tools ? new ToolPolicy({ readOnly: o.readOnly, ...(o.tools ? { groups: o.tools } : {}) }) : undefined;
  const ctx = policy ? { ...hub.ctx, policy } : hub.ctx;
  let http;
  try {
    http = await startHttpServer({
      host: o.host, port: o.port, token, allowRemote: o.allowRemote, makeServer: () => createCraftwireServer(ctx),
      allowHosts: o.allowHosts, allowOrigins: o.allowOrigins, rateLimitPerMinute: o.rateLimit, sessionIdleMs: o.idleMinutes * 60_000,
      ...(tls ? { tls } : {}),
    });
  } catch (e) {
    await hub.close();
    throw e;
  }
  const info = join(home, "serve.json");
  const localUrl = http.url.replace(/\/\/(0\.0\.0\.0|\[::\])/, "//127.0.0.1");
  writeFileSync(info, JSON.stringify({ url: localUrl, pid: process.pid } satisfies ServeInfo));

  log(`hub ${HUB_VERSION} serving MCP at ${http.url} (games connect on 127.0.0.1:${hub.port})`);
  log(`token ${created ? (o.rotateToken ? "rotated" : "created") : "read"}: ${file}`);
  log(`Claude Code: claude mcp add --transport http craftwire ${http.url} --header "Authorization: Bearer ${token}"`);
  if (policy) log(`offering ${o.readOnly ? "read-only " : ""}tools${o.tools ? ` from ${[...o.tools].join(", ")}` : ""}`);
  if (!isLoopback(o.host)) {
    log(isWildcard(o.host) ? "listening on every network interface" : `listening on ${o.host}`);
    if (!tls) log("WARNING: without --tls-cert/--tls-key the token crosses the network in clear text. Use TLS, or an SSH tunnel to 127.0.0.1.");
  }
  let closing: Promise<void> | undefined;
  return {
    url: localUrl,
    agentPort: hub.port,
    token,
    close: () => (closing ??= http.close().finally(() => hub.close()).finally(() => {
      if (existsSync(info) && readServeInfo(home)?.pid === process.pid) rmSync(info, { force: true });
    })),
  };
}

/** Runs the hub with MCP over HTTP until it is stopped (Ctrl+C). */
export async function serve(o: ServeOptions, home: string, log: (msg: string) => void): Promise<void> {
  const s = await startServe(o, home, log);
  await new Promise<void>((resolve) => {
    const stop = () => {
      log("stopping");
      void s.close().finally(resolve);
    };
    process.once("SIGINT", stop);
    process.once("SIGTERM", stop);
  });
}
