import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { findScenarioFiles } from "../src/scenario/files.js";
import { connectFakeAgent } from "./helpers/fakeAgent.js";
import { json, startHub, TOKEN } from "./helpers/hub.js";

let hub: Awaited<ReturnType<typeof startHub>> | undefined;
afterEach(async () => { await hub?.close(); hub = undefined; });

describe("scenario_run", () => {
  it("runs an inline scenario through the real tools", async () => {
    hub = await startHub();
    const agent = await connectFakeAgent(hub.port, { token: TOKEN, kind: "server", name: "server" });
    const commands: string[] = [];
    agent.onRequest("server.command", (p) => { commands.push(String(p.command)); return { output: [`ran ${p.command}`] }; });
    const res = json(await hub.call("scenario_run", {
      scenario: {
        name: "console",
        steps: [
          { command: "time set day", expect: { path: "output[0]", matches: "ran time" } },
          { command: "weather clear", expect: { path: "output[0]", equals: "something else" } },
        ],
      },
    }));
    expect(commands).toEqual(["time set day", "weather clear"]);
    expect(res).toMatchObject({ passed: 0, failed: 1, scenarios: [{ name: "console", passed: false, failure: { index: 1, expected: { equals: "something else" }, actual: "ran weather clear" } }] });
  });

  it("reports a malformed scenario as INVALID_SCENARIO", async () => {
    hub = await startHub();
    const res = json(await hub.call("scenario_run", { scenario: { steps: [{ fly: true }] } }));
    expect(res.code).toBe("INVALID_SCENARIO");
    expect(res.message).toMatch(/steps\[0\]/);
  });

  it("an unknown tool fails its step instead of the run", async () => {
    hub = await startHub();
    const res = json(await hub.call("scenario_run", { scenario: { steps: [{ tool: "teleport_everyone" }] } }));
    expect(res.scenarios[0].failure.message).toMatch(/teleport_everyone failed/);
  });
});

describe("findScenarioFiles", () => {
  it("finds *.cwtest.json under folders, skipping build output", () => {
    const root = mkdtempSync(join(tmpdir(), "cw-scn-"));
    mkdirSync(join(root, "tests", "shop"), { recursive: true });
    mkdirSync(join(root, "node_modules", "x"), { recursive: true });
    mkdirSync(join(root, "build"), { recursive: true });
    for (const f of ["tests/b.cwtest.json", "tests/shop/a.cwtest.json", "node_modules/x/c.cwtest.json", "build/d.cwtest.json", "tests/notes.json"]) {
      writeFileSync(join(root, f), "{}");
    }
    expect(findScenarioFiles([], root).map((f) => f.slice(root.length + 1).replaceAll("\\", "/"))).toEqual(["tests/b.cwtest.json", "tests/shop/a.cwtest.json"]);
    expect(() => findScenarioFiles(["missing"], root)).toThrow(/no such file/);
  });
});
