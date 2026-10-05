import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { AgentServer } from "../src/agents.js";
import { writeHubConfig } from "../src/config.js";
import { type Check, formatChecks, runDoctor } from "../src/doctor.js";
import { HUB_VERSION } from "../src/version.js";
import { connectFakeAgent } from "./helpers/fakeAgent.js";
import { makeZip } from "./helpers/zip.js";

const TOKEN = "e".repeat(64);
const java25 = async () => 25;
let agents: AgentServer | undefined;
afterEach(async () => {
  await agents?.close();
  agents = undefined;
});
const home = () => mkdtempSync(join(tmpdir(), "cw-doc-"));
const find = (checks: Check[], text: string) => checks.find((c) => c.label.includes(text));

describe("craftwire doctor", () => {
  it("reports the running hub, its agents, version drift and refused agents", async () => {
    const h = home();
    agents = new AgentServer({ token: TOKEN, port: 0 });
    const port = await agents.listen();
    writeHubConfig({ port, token: TOKEN }, h);
    await connectFakeAgent(port, { token: TOKEN, kind: "server", name: "srv", agentVersion: HUB_VERSION });
    await connectFakeAgent(port, { token: TOKEN, name: "Steve", agentVersion: "0.1.0" });
    await connectFakeAgent(port, { token: TOKEN, kind: "server", name: "old", agentVersion: "0.0.1", protocolVersion: 0 }).catch(() => undefined);
    const checks = await runDoctor({ home: h, javaMajor: java25 });
    expect(find(checks, `hub ${HUB_VERSION} is running`)?.status).toBe("ok");
    expect(find(checks, "server-1 (srv")?.status).toBe("ok");
    expect(find(checks, "client-1 (Steve")).toMatchObject({ status: "warn", fix: expect.stringContaining(`Agent mod to ${HUB_VERSION}`) });
    expect(find(checks, "was refused: PROTOCOL_MISMATCH")?.status).toBe("fail");
    expect(find(checks, "Java 25")?.status).toBe("ok");
  });

  it("warns when hub.json is missing or nothing answers", async () => {
    expect(find(await runDoctor({ home: home(), javaMajor: java25 }), "hub.json is missing")?.status).toBe("warn");
    const h = home();
    const port = await new Promise<number>((res) => {
      const s = createServer().listen(0, "127.0.0.1", () => {
        const p = (s.address() as { port: number }).port;
        s.close(() => res(p));
      });
    });
    writeHubConfig({ port, token: TOKEN }, h);
    expect(find(await runDoctor({ home: h, javaMajor: java25 }), "no hub answers")?.status).toBe("warn");
  });

  it("fails on an old Node and warns on an old Java", async () => {
    const checks = await runDoctor({ home: home(), nodeVersion: "18.19.0", javaMajor: async () => 21 });
    expect(find(checks, "Node 18.19.0")?.status).toBe("fail");
    expect(find(checks, "Java 21")?.status).toBe("warn");
  });

  it("checks a server folder without changing it", async () => {
    const dir = mkdtempSync(join(tmpdir(), "cw-doc-srv-"));
    mkdirSync(join(dir, "plugins"));
    writeFileSync(join(dir, "server.jar"), "");
    writeFileSync(join(dir, "eula.txt"), "eula=false\n");
    makeZip(join(dir, "plugins", "craftwire-paper-0.1.0.jar"), { "plugin.yml": "name: Craftwire\nversion: 0.1.0\n" });
    const checks = await runDoctor({ home: home(), serverDir: dir, javaMajor: java25 });
    expect(find(checks, "server jar")?.status).toBe("ok");
    expect(find(checks, "EULA not accepted")).toMatchObject({ status: "warn", fix: expect.stringContaining("aka.ms/MinecraftEULA") });
    expect(find(checks, "Craftwire plugin 0.1.0")?.status).toBe("warn");
  });

  it("formats checks with fixes under them", () => {
    expect(formatChecks([{ status: "ok", label: "a" }, { status: "warn", label: "b", fix: "do c" }])).toBe("[ok] a\n[warn] b\n       -> do c\n");
  });
});
