/** Teammate tokens end to end: what each role may list and call over HTTP and in the panel, auditing, revoking. */
import { mkdtempSync, readFileSync } from "node:fs";
import { request } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { afterEach, describe, expect, it } from "vitest";
import { personServer } from "../src/http/serve-command.js";
import { startHttpServer, type HttpHub } from "../src/http/serve.js";
import { createPanel } from "../src/panel/panel.js";
import { TeamTokens } from "../src/team/tokens.js";
import { startHub } from "./helpers/hub.js";

const OWNER_TOKEN = "o".repeat(64);
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

/** `craftwire serve --panel` as serve-command wires it, with a fresh token store. */
async function serve() {
  hub = await startHub();
  const ctx = hub.ctx;
  const team = new TeamTokens(mkdtempSync(join(tmpdir(), "cw-team-")));
  const authenticate = (t: string) => team.identify(t, OWNER_TOKEN);
  http = await startHttpServer({
    host: "127.0.0.1", port: 0, authenticate, makeServer: (p, s) => personServer(ctx, undefined, p, s),
    panel: createPanel(ctx, authenticate, false),
  });
  return { team, http, port: Number(new URL(http.url).port), audit: ctx.audit };
}

async function connect(url: string, token: string): Promise<{ client: Client; transport: StreamableHTTPClientTransport }> {
  const client = new Client({ name: "teammate", version: "1" });
  const transport = new StreamableHTTPClientTransport(new URL(url), { requestInit: { headers: { authorization: `Bearer ${token}` } } });
  await client.connect(transport);
  clients.push(client);
  return { client, transport };
}

const names = async (c: Client) => (await c.listTools()).tools.map((t) => t.name);

function raw(port: number, method: string, path: string, headers: Record<string, string>, body?: string) {
  return new Promise<{ status: number; headers: Record<string, string | string[] | undefined>; body: string }>((resolve, reject) => {
    const req = request({ host: "127.0.0.1", port, path, method, headers }, (res) => {
      let data = "";
      res.on("data", (c) => { data += c; });
      res.on("end", () => resolve({ status: res.statusCode ?? 0, headers: res.headers, body: data }));
    });
    req.on("error", reject);
    req.end(body);
  });
}

/** Signs in to the panel like its page does; returns the cookie to send back. */
async function signIn(port: number, token: string): Promise<string> {
  const r = await raw(port, "POST", "/panel/login", { origin: `http://127.0.0.1:${port}`, host: `127.0.0.1:${port}`, "content-type": "application/json" },
    JSON.stringify({ token }));
  expect(r.status).toBe(204);
  return String(r.headers["set-cookie"]).split(";")[0]!;
}

const api = (port: number, cookie: string, name: string) => raw(port, "GET", `/panel/api/${name}`, { cookie, host: `127.0.0.1:${port}` });

describe("teammate tokens", () => {
  it("stores only a hash of each token", () => {
    const home = mkdtempSync(join(tmpdir(), "cw-team-"));
    const team = new TeamTokens(home);
    const token = team.add("alice", "dev");
    expect(token).toMatch(/^cw_[0-9a-f]{48}$/);
    expect(readFileSync(join(home, "auth", "tokens.json"), "utf8")).not.toContain(token.slice(3));
    expect(team.identify(token, OWNER_TOKEN)?.name).toBe("alice");
    expect(team.identify(OWNER_TOKEN, OWNER_TOKEN)?.role).toBe("owner");
    expect(team.identify("cw_" + "0".repeat(48), OWNER_TOKEN)).toBeUndefined();
  });

  it("adding a name again replaces its token, and removing it revokes it", () => {
    const team = new TeamTokens(mkdtempSync(join(tmpdir(), "cw-team-")));
    const first = team.add("alice", "dev");
    const second = team.add("alice", "read-only");
    expect(team.identify(first, OWNER_TOKEN)).toBeUndefined();
    expect(team.identify(second, OWNER_TOKEN)?.role).toBe("read-only");
    expect(team.list()).toHaveLength(1);
    expect(team.remove("alice")).toBe(true);
    expect(team.identify(second, OWNER_TOKEN)).toBeUndefined();
  });

  it("refuses names that cannot be told apart and groups that do not exist", () => {
    const team = new TeamTokens(mkdtempSync(join(tmpdir(), "cw-team-")));
    expect(() => team.add("owner", "dev")).toThrow(/owner/);
    expect(() => team.add("a b", "dev")).toThrow();
    expect(() => team.add("alice", "dev", ["nope"])).toThrow(/nope/);
  });
});

describe("roles over MCP", () => {
  it("each role is offered only its tools", async () => {
    const { team, http } = await serve();
    const reader = await names((await connect(http.url, team.add("rita", "read-only"))).client);
    expect(reader).toContain("list_instances");
    expect(reader).toContain("server_process");   // status only
    expect(reader).not.toContain("server_command");
    expect(reader).not.toContain("bot_spawn");
    const botter = await names((await connect(http.url, team.add("bea", "bots"))).client);
    expect(botter).toContain("bot_spawn");
    expect(botter).toContain("wait_for");
    expect(botter).not.toContain("server_command");
    expect(botter).not.toContain("screenshot");
    const limited = await names((await connect(http.url, team.add("dan", "dev", ["build"]))).client);
    expect(limited.sort()).toEqual(["build", "build_manage"]);
    expect(await names((await connect(http.url, OWNER_TOKEN)).client)).toContain("server_command");
  });

  it("a read-only teammate is refused an action that would change something", async () => {
    const { team, http } = await serve();
    const { client } = await connect(http.url, team.add("rita", "read-only"));
    const r = (await client.callTool({ name: "server_process", arguments: { action: "start", serverDir: "." } })) as CallToolResult;
    expect(r.isError).toBe(true);
    expect((r.content[0] as { text: string }).text).toContain("READ_ONLY");
  });

  it("audits every call under the person who made it", async () => {
    const { team, http, audit } = await serve();
    await (await connect(http.url, team.add("alice", "dev"))).client.callTool({ name: "list_instances", arguments: {} });
    await (await connect(http.url, OWNER_TOKEN)).client.callTool({ name: "list_instances", arguments: {} });
    expect(audit.sessions().map((s) => s.user).sort()).toEqual(["alice", "owner"]);
  });

  it("a session cannot be taken over with someone else's token", async () => {
    const { team, http, port } = await serve();
    const { transport } = await connect(http.url, team.add("alice", "dev"));
    const bob = team.add("bob", "dev");
    const r = await raw(port, "POST", "/mcp", {
      authorization: `Bearer ${bob}`, "mcp-session-id": transport.sessionId!, "content-type": "application/json",
      accept: "application/json, text/event-stream", host: `127.0.0.1:${port}`,
    }, JSON.stringify({ jsonrpc: "2.0", id: 9, method: "tools/list" }));
    expect(r.status).toBe(403);
  });

  it("a removed token stops working on its next request", async () => {
    const { team, http } = await serve();
    const { client } = await connect(http.url, team.add("alice", "dev"));
    team.remove("alice");
    await expect(client.listTools()).rejects.toThrow();
  });
});

describe("the panel", () => {
  it("signs in with an HttpOnly, same-site cookie and only from its own page", async () => {
    const { team, port } = await serve();
    const token = team.add("alice", "dev");
    const headers = { origin: `http://127.0.0.1:${port}`, host: `127.0.0.1:${port}`, "content-type": "application/json" };
    expect((await raw(port, "POST", "/panel/login", headers, JSON.stringify({ token: "wrong" }))).status).toBe(401);
    expect((await raw(port, "POST", "/panel/login", { ...headers, origin: "http://evil.example" }, JSON.stringify({ token }))).status).toBe(403);
    const ok = await raw(port, "POST", "/panel/login", headers, JSON.stringify({ token }));
    expect(String(ok.headers["set-cookie"])).toMatch(/HttpOnly; SameSite=Strict; Path=\/panel/);
    expect(String(ok.headers["set-cookie"])).not.toContain(token);
    expect((await api(port, "", "me")).status).toBe(401);
  });

  it("gives each role only the endpoints its tools allow", async () => {
    const { team, port } = await serve();
    const reader = await signIn(port, team.add("rita", "read-only"));
    expect((await api(port, reader, "instances")).status).toBe(200);
    // the replay shows everyone's arguments: dev and owner only
    expect((await api(port, reader, "sessions")).status).toBe(403);
    expect((await api(port, reader, "scenario")).status).toBe(403);
    const botter = await signIn(port, team.add("bea", "bots"));
    expect((await api(port, botter, "bots")).status).toBe(200);
    expect((await api(port, botter, "screenshot")).status).toBe(403);
    const dev = await signIn(port, team.add("dan", "dev"));
    expect((await api(port, dev, "sessions")).status).toBe(200);
  });

  it("revoking a token ends its panel sign-in at once", async () => {
    const { team, port } = await serve();
    const cookie = await signIn(port, team.add("alice", "dev"));
    expect(JSON.parse((await api(port, cookie, "me")).body)).toEqual({ name: "alice", role: "dev" });
    team.remove("alice");
    expect((await api(port, cookie, "me")).status).toBe(401);
  });

  it("slows down guessing: ten sign-ins a minute", async () => {
    const { port } = await serve();
    const headers = { origin: `http://127.0.0.1:${port}`, host: `127.0.0.1:${port}`, "content-type": "application/json" };
    const statuses: number[] = [];
    for (let i = 0; i < 12; i++) statuses.push((await raw(port, "POST", "/panel/login", headers, JSON.stringify({ token: `guess${i}` }))).status);
    expect(statuses.slice(0, 10).every((s) => s === 401)).toBe(true);
    expect(statuses.slice(10)).toEqual([429, 429]);
  });
});
