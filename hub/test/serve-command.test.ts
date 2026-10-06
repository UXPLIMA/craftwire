import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { createServer, type Server } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { writeHubConfig } from "../src/config.js";
import { parseServeArgs, startServe, type Serving } from "../src/http/serve-command.js";
import { httpCaller } from "../src/scenario/caller.js";
import { chooseHub, parseTestArgs, runTests } from "../src/scenario/test-command.js";
import { connectFakeAgent } from "./helpers/fakeAgent.js";

const quiet = () => {};
let serving: Serving | undefined;
let other: Server | undefined;
beforeEach(() => { process.env.CRAFTWIRE_PORT = "0"; });
afterEach(async () => {
  delete process.env.CRAFTWIRE_PORT;
  await serving?.close();
  serving = undefined;
  other?.close();
  other = undefined;
});

const home = () => mkdtempSync(join(tmpdir(), "cw-serve-"));
const defaults = () => ({ ...parseServeArgs([]), port: 0 });

async function anotherHubIn(dir: string): Promise<void> {
  other = createServer();
  await new Promise<void>((r) => other!.listen(0, "127.0.0.1", r));
  writeHubConfig({ port: (other.address() as { port: number }).port, token: "f".repeat(64) }, dir);
}

describe("craftwire serve", () => {
  it("parses its options", () => {
    expect(parseServeArgs([])).toMatchObject({ port: 7777, host: "127.0.0.1", allowRemote: false, readOnly: false, rateLimit: 120, idleMinutes: 30 });
    const o = parseServeArgs(["--port", "9000", "--host", "0.0.0.0", "--allow-remote", "--read-only", "--tools", "hub,server", "--allow-origin", "https://a.example", "--allow-host", "hub.lan", "--rate-limit", "60", "--idle", "5", "--rotate-token"]);
    expect(o).toMatchObject({ port: 9000, host: "0.0.0.0", allowRemote: true, readOnly: true, allowOrigins: ["https://a.example"], allowHosts: ["hub.lan"], rateLimit: 60, idleMinutes: 5, rotateToken: true });
    expect([...o.tools!]).toEqual(["hub", "server"]);
    expect(() => parseServeArgs(["--tls-cert", "c.pem"])).toThrow(/go together/);
    expect(() => parseServeArgs(["--port", "70000"])).toThrow(/--port/);
    expect(() => parseServeArgs(["--open"])).toThrow(/Unknown option/);
  });

  it("serves the hub over HTTP; craftwire test on this machine finds it and runs scenarios through it", async () => {
    const dir = home();
    serving = await startServe(defaults(), dir, quiet);
    expect(JSON.parse(readFileSync(join(dir, "serve.json"), "utf8")).url).toBe(serving.url);
    const hubToken = JSON.parse(readFileSync(join(dir, "hub.json"), "utf8")).token;
    const agent = await connectFakeAgent(serving.agentPort, { token: hubToken, kind: "server", name: "srv" });
    agent.onRequest("server.command", (p) => ({ output: [`ran ${p.command}`] }));

    const choice = await chooseHub(dir, undefined, undefined);
    expect(choice).toEqual({ kind: "http", url: serving.url, token: serving.token });
    const scenarios = mkdtempSync(join(tmpdir(), "cw-scn-"));
    writeFileSync(join(scenarios, "a.cwtest.json"), JSON.stringify({ steps: [{ command: "list", expect: { path: "output[0]", equals: "ran list" } }] }));
    const caller = await httpCaller(serving.url, serving.token);
    const out: string[] = [];
    expect(await runTests(parseTestArgs([]), { call: caller.call, cwd: scenarios, out: (s) => out.push(s) })).toBe(0);
    await caller.close();
    expect(out.at(-1)).toBe("1 scenario(s): 1 passed, 0 failed");

    await serving.close();
    serving = undefined;
    expect(existsSync(join(dir, "serve.json"))).toBe(false);
  });

  it("keeps the token across restarts unless rotated", async () => {
    const dir = home();
    serving = await startServe(defaults(), dir, quiet);
    const first = serving.token;
    await serving.close();
    serving = await startServe(defaults(), dir, quiet);
    expect(serving.token).toBe(first);
    await serving.close();
    serving = await startServe({ ...defaults(), rotateToken: true }, dir, quiet);
    expect(serving.token).not.toBe(first);
  });

  it("a read-only server offers no tools that change things", async () => {
    const dir = home();
    serving = await startServe({ ...defaults(), readOnly: true }, dir, quiet);
    const caller = await httpCaller(serving.url, serving.token);
    const r = await caller.call("server_command", { command: "stop" });
    await caller.close();
    expect(r.ok).toBe(false);
  });

  it("refuses another address without --allow-remote before writing or starting anything", async () => {
    const dir = home();
    await expect(startServe({ ...defaults(), host: "0.0.0.0" }, dir, quiet)).rejects.toThrow(/--allow-remote/);
    expect(existsSync(join(dir, "http.json")) || existsSync(join(dir, "hub.json"))).toBe(false);
  });

  it("refuses to start while another hub holds the games", async () => {
    const dir = home();
    await anotherHubIn(dir);
    await expect(startServe(defaults(), dir, quiet)).rejects.toThrow(/Another Craftwire hub/);
  });
});

describe("chooseHub", () => {
  it("runs its own hub when none is running", async () => {
    expect(await chooseHub(home(), undefined, undefined)).toEqual({ kind: "local" });
  });

  it("refuses when an AI session's hub (not craftwire serve) holds the games", async () => {
    const dir = home();
    await anotherHubIn(dir);
    await expect(chooseHub(dir, undefined, undefined)).rejects.toMatchObject({ code: "HUB_RUNNING" });
  });

  it("ignores the note of a craftwire serve that was killed", async () => {
    const dir = home();
    await anotherHubIn(dir);
    writeFileSync(join(dir, "serve.json"), JSON.stringify({ url: "http://127.0.0.1:1/mcp", pid: 2 ** 22 + 12345 }));
    await expect(chooseHub(dir, undefined, undefined)).rejects.toMatchObject({ code: "HUB_RUNNING" });
  });

  it("uses --hub with the token from the environment, and needs one", async () => {
    expect(await chooseHub(home(), "https://hub.example/mcp", "a".repeat(64))).toEqual({ kind: "http", url: "https://hub.example/mcp", token: "a".repeat(64) });
    await expect(chooseHub(home(), "https://hub.example/mcp", undefined)).rejects.toMatchObject({ code: "NO_TOKEN" });
  });
});
