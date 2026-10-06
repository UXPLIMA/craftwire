import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { parseTestArgs, runTests } from "../src/scenario/test-command.js";
import { inProcessCaller } from "../src/scenario/caller.js";
import { createCraftwireServer } from "../src/server.js";
import { connectFakeAgent } from "./helpers/fakeAgent.js";
import { startHub, TOKEN } from "./helpers/hub.js";

let hub: Awaited<ReturnType<typeof startHub>> | undefined;
afterEach(async () => { await hub?.close(); hub = undefined; });

function scenarioDir(files: Record<string, unknown>): string {
  const dir = mkdtempSync(join(tmpdir(), "cw-test-"));
  for (const [name, body] of Object.entries(files)) writeFileSync(join(dir, name), JSON.stringify(body));
  return dir;
}

describe("craftwire test", () => {
  it("parses its options", () => {
    expect(parseTestArgs(["tests", "--server", "srv", "--junit", "out.xml", "--wait", "5", "--json", "--hub", "http://127.0.0.1:7777/mcp"]))
      .toEqual({ paths: ["tests"], serverDir: "srv", junit: "out.xml", json: true, waitMs: 5000, hub: "http://127.0.0.1:7777/mcp" });
    expect(() => parseTestArgs(["--junit"])).toThrow(/needs a value/);
    expect(() => parseTestArgs(["--fast"])).toThrow(/Unknown option/);
  });

  it("runs the files against the connected server, prints each result and writes JUnit", async () => {
    hub = await startHub();
    const agent = await connectFakeAgent(hub.port, { token: TOKEN, kind: "server", name: "server" });
    agent.onRequest("server.command", (p) => ({ output: [`ran ${p.command}`] }));
    const dir = scenarioDir({
      "a.cwtest.json": { name: "passes", steps: [{ command: "list", expect: { path: "output[0]", equals: "ran list" } }] },
      "b.cwtest.json": { name: "fails", steps: [{ command: "list", expect: { path: "output[0]", equals: "nope" } }] },
    });
    const out: string[] = [];
    const caller = await inProcessCaller(createCraftwireServer(hub.ctx));
    const code = await runTests(parseTestArgs(["--junit", "junit.xml"]), { call: caller.call, cwd: dir, out: (s) => out.push(s) });
    await caller.close();
    expect(code).toBe(1);
    expect(out[0]).toMatch(/^✓ passes/);
    expect(out[1]).toMatch(/^✗ fails[\s\S]*expected: \{"equals":"nope"\}/);
    expect(out.at(-1)).toBe("2 scenario(s): 1 passed, 1 failed");
    expect(readFileSync(join(dir, "junit.xml"), "utf8")).toContain('tests="2" failures="1"');
  });

  it("gives up when no server connects", async () => {
    hub = await startHub();
    const dir = scenarioDir({ "a.cwtest.json": { steps: [{ command: "list" }] } });
    const caller = await inProcessCaller(createCraftwireServer(hub.ctx));
    await expect(runTests(parseTestArgs(["--wait", "0.3"]), { call: caller.call, cwd: dir, out: () => {} }))
      .rejects.toMatchObject({ code: "NO_SERVER" });
    await caller.close();
  });
});
