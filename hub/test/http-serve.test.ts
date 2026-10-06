import { readFileSync } from "node:fs";
import { request } from "node:http";
import { request as httpsRequest } from "node:https";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { afterEach, describe, expect, it } from "vitest";
import { startHttpServer, type HttpHub, type HttpOptions } from "../src/http/serve.js";
import { createCraftwireServer } from "../src/server.js";
import { startHub } from "./helpers/hub.js";

const HTTP_TOKEN = "c".repeat(64);
let hub: Awaited<ReturnType<typeof startHub>> | undefined;
let http: HttpHub | undefined;
const clients: Client[] = [];
afterEach(async () => {
  for (const c of clients.splice(0)) await c.close().catch(() => undefined);
  await http?.close();
  await hub?.close();
  http = undefined;
  hub = undefined;
});

async function serve(o: Partial<HttpOptions> = {}): Promise<HttpHub> {
  hub = await startHub();
  const ctx = hub.ctx;
  http = await startHttpServer({ host: "127.0.0.1", port: 0, token: HTTP_TOKEN, makeServer: () => createCraftwireServer(ctx), ...o });
  return http;
}

async function connect(url: string, token = HTTP_TOKEN): Promise<Client> {
  const client = new Client({ name: "remote", version: "1" });
  await client.connect(new StreamableHTTPClientTransport(new URL(url), { requestInit: { headers: { authorization: `Bearer ${token}` } } }));
  clients.push(client);
  return client;
}

/** A raw request, so headers like Host and Origin can be set freely. */
function raw(port: number, headers: Record<string, string>, path = "/mcp", body = '{"jsonrpc":"2.0","id":1,"method":"ping"}'): Promise<{ status: number; headers: Record<string, unknown>; body: string }> {
  return new Promise((resolve, reject) => {
    const req = request({ host: "127.0.0.1", port, path, method: "POST", headers: { "content-type": "application/json", accept: "application/json, text/event-stream", ...headers } }, (res) => {
      let data = "";
      res.on("data", (c) => { data += c; });
      res.on("end", () => resolve({ status: res.statusCode ?? 0, headers: res.headers, body: data }));
    });
    req.on("error", reject);
    req.end(body);
  });
}

describe("craftwire serve (MCP over HTTP)", () => {
  it("serves the tools to several clients at once, each in its own session", async () => {
    const h = await serve();
    expect(h.url).toMatch(/^http:\/\/127\.0\.0\.1:\d+\/mcp$/);
    const a = await connect(h.url);
    const b = await connect(h.url);
    expect((await a.listTools()).tools.map((t) => t.name)).toContain("list_instances");
    const r = await b.callTool({ name: "list_instances", arguments: {} });
    expect(JSON.parse((r.content as { text: string }[])[0]!.text).instances).toEqual([]);
    expect(h.sessions()).toBe(2);
  });

  it("requires the bearer token, and never reads one from the URL", async () => {
    const h = await serve();
    expect((await raw(h.port, {})).status).toBe(401);
    expect((await raw(h.port, { authorization: `Bearer ${"d".repeat(64)}` })).status).toBe(401);
    const inUrl = await raw(h.port, {}, `/mcp?token=${HTTP_TOKEN}`);
    expect(inUrl.status).toBe(401);
    expect(inUrl.headers["www-authenticate"]).toMatch(/Bearer/);
    await expect(connect(h.url, "e".repeat(64))).rejects.toThrow();
  });

  it("rejects other Host names and requests from web pages (DNS rebinding)", async () => {
    const h = await serve();
    const auth = { authorization: `Bearer ${HTTP_TOKEN}` };
    expect((await raw(h.port, { ...auth, host: `evil.example:${h.port}` })).status).toBe(421);
    expect((await raw(h.port, { ...auth, origin: "https://evil.example" })).status).toBe(403);
    expect((await raw(h.port, { ...auth, host: `localhost:${h.port}` })).status).not.toBe(421);
  });

  it("allows listed origins", async () => {
    const h = await serve({ allowOrigins: ["https://app.example"] });
    expect((await raw(h.port, { authorization: `Bearer ${HTTP_TOKEN}`, origin: "https://app.example" })).status).not.toBe(403);
  });

  it("rate limits", async () => {
    const h = await serve({ rateLimitPerMinute: 2 });
    const auth = { authorization: `Bearer ${HTTP_TOKEN}` };
    await raw(h.port, auth);
    await raw(h.port, auth);
    const third = await raw(h.port, auth);
    expect(third.status).toBe(429);
    expect(Number(third.headers["retry-after"])).toBeGreaterThan(0);
  });

  it("refuses oversized requests", async () => {
    const h = await serve({ maxBodyBytes: 1000 });
    const big = JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize", params: { pad: "x".repeat(5000) } });
    expect((await raw(h.port, { authorization: `Bearer ${HTTP_TOKEN}` }, "/mcp", big)).status).toBe(413);
  });

  it("keeps a connected client's session, and expires one whose client left without closing it", async () => {
    const h = await serve({ sessionIdleMs: 500 });
    const stays = await connect(h.url);
    const leaves = await connect(h.url);
    expect(h.sessions()).toBe(2);
    await leaves.close();   // drops its connection without ending the session (no DELETE)
    await new Promise((r) => setTimeout(r, 2200));
    expect(h.sessions()).toBe(1);
    expect((await stays.listTools()).tools.length).toBeGreaterThan(0);
  });

  it("will not listen beyond this machine without --allow-remote", async () => {
    hub = await startHub();
    const ctx = hub.ctx;
    await expect(startHttpServer({ host: "0.0.0.0", port: 0, token: HTTP_TOKEN, makeServer: () => createCraftwireServer(ctx) })).rejects.toThrow(/--allow-remote/);
  });

  it("serves over TLS with a certificate", async () => {
    const dir = join(__dirname, "fixtures", "tls");
    const cert = readFileSync(join(dir, "cert.pem"));
    const h = await serve({ tls: { cert, key: readFileSync(join(dir, "key.pem")) } });
    expect(h.url).toMatch(/^https:/);
    const status = await new Promise<number>((resolve, reject) => {
      const req = httpsRequest({ host: "127.0.0.1", port: h.port, path: "/mcp", method: "POST", ca: cert, headers: { "content-type": "application/json" } }, (res) => {
        res.resume();
        resolve(res.statusCode ?? 0);
      });
      req.on("error", reject);
      req.end("{}");
    });
    expect(status).toBe(401);
  });

  it("other paths are not found", async () => {
    const h = await serve();
    expect((await raw(h.port, { authorization: `Bearer ${HTTP_TOKEN}` }, "/")).status).toBe(404);
  });
});
