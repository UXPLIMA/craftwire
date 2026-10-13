import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { connectFakeAgent, type FakeAgent } from "./helpers/fakeAgent.js";
import { json, startHub } from "./helpers/hub.js";

let hub: Awaited<ReturnType<typeof startHub>>;
let agent: FakeAgent;
const seen: Record<string, unknown>[] = [];
const dir = mkdtempSync(join(tmpdir(), "cw-bt-"));
writeFileSync(join(dir, "t.build.js"), "set(0,0,0,'stone')");

beforeAll(async () => {
  hub = await startHub();
  agent = await connectFakeAgent(hub.port, { token: hub.token, kind: "server", agentVersion: "0.10.0" });
  agent.onRequest("build.run", (p) => {
    seen.push(p);
    return { project: p.project, changed: 1, preview: p.preview === "iso4" ? { mime: "image/png", data: "iVBORw0KGgo=" } : undefined };
  });
  agent.onRequest("build.manage", (p) => ({ action: p.action }));
});
afterAll(async () => { await agent.close(); await hub.close(); });

describe("build tool", () => {
  it("sends the file's code and its absolute path", async () => {
    const r = await hub.call("build", { project: "t", file: join(dir, "t.build.js"), origin: { x: 0, y: 64, z: 0 } });
    expect(json(r).changed).toBe(1);
    expect(seen.at(-1)).toMatchObject({ project: "t", code: "set(0,0,0,'stone')", file: join(dir, "t.build.js") });
  });

  it("needs exactly one of file or code", async () => {
    const r = await hub.call("build", { project: "t" });
    expect(r.isError).toBe(true);
    expect(JSON.stringify(r.content)).toContain("file or code");
  });

  it("returns the preview as an image", async () => {
    const r = await hub.call("build", { project: "t", code: "set(0,0,0,'stone')", preview: "iso4" });
    expect(r.content[0]).toMatchObject({ type: "image", mimeType: "image/png" });
    expect(JSON.parse((r.content[1] as { text: string }).text).preview).toBeUndefined();
  });

  it("build_manage forwards the action", async () => {
    expect(json(await hub.call("build_manage", { action: "list" })).action).toBe("list");
  });
});
