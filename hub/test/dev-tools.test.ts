import { spawn } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { type AddressInfo, createServer } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { waitUntil } from "../src/dev/server-manager.js";
import { json, startHub } from "./helpers/hub.js";
import { FAKE_PAPER, fakeLaunch, makeServerDir } from "./helpers/servers.js";
import { makeZip } from "./helpers/zip.js";

let hub: Awaited<ReturnType<typeof startHub>> | undefined;
afterEach(async () => {
  await hub?.close();
  hub = undefined;
});

describe("server_process", () => {
  it("starts a server with its agent, reports status and stops it", async () => {
    hub = await startHub({ writeHubJson: true, javaMajor: 25, agentWaitMs: 2000 });
    const dir = makeServerDir({ craftwire: true });
    const started = json(await hub.call("server_process", { action: "start", serverDir: dir, ...fakeLaunch("agent") }));
    expect(started).toMatchObject({ state: "running", agent: "server-1" });
    const inst = json(await hub.call("list_instances")).instances[0];
    expect(inst).toMatchObject({ id: "server-1", pid: started.pid });
    const status = json(await hub.call("server_process", { action: "status", tail: 5 }));
    expect(status.servers[0]).toMatchObject({ state: "running", agent: "server-1" });
    expect(status.servers[0].consoleTail.length).toBeLessThanOrEqual(5);
    expect(json(await hub.call("server_process", { action: "stop" }))).toMatchObject({ stopped: true, forced: false });
  });

  it("asks for serverDir when no server is known", async () => {
    hub = await startHub({ javaMajor: 25 });
    const r = await hub.call("server_process", { action: "start" });
    expect(r.isError).toBe(true);
    expect(json(r).code).toBe("INVALID_PARAMS");
  });

  it("passes the EULA refusal through with its hint", async () => {
    hub = await startHub({ javaMajor: 25 });
    const r = await hub.call("server_process", { action: "start", serverDir: makeServerDir({ eula: false }), ...fakeLaunch("ok") });
    expect(json(r)).toMatchObject({ code: "EULA_NOT_ACCEPTED", hint: expect.stringContaining("never accepts") });
  });
});

describe("plugin_deploy", () => {
  const NODE = `"${process.execPath}"`;
  const pluginJar = (dir: string, file: string, name: string) => {
    mkdirSync(dir, { recursive: true });
    return makeZip(join(dir, file), { "plugin.yml": `name: ${name}\nversion: 1.0\n` });
  };

  it("installs a ready jar, restarts the server and reports the plugin enabled", async () => {
    hub = await startHub({ writeHubJson: true, javaMajor: 25, agentWaitMs: 2000 });
    const dir = makeServerDir({ craftwire: true });
    await hub.call("server_process", { action: "start", serverDir: dir, ...fakeLaunch("agent") });
    const first = json(await hub.call("plugin_deploy", { jar: pluginJar(mkdtempSync(join(tmpdir(), "cw-j-")), "demo-1.0.jar", "Demo") }));
    expect(first).toMatchObject({ plugin: { name: "Demo" }, restarted: true, server: { state: "running", agent: "server-2" }, loaded: { enabled: true } });
    const second = json(await hub.call("plugin_deploy", { jar: pluginJar(mkdtempSync(join(tmpdir(), "cw-j-")), "demo-1.1.jar", "Demo") }));
    expect(second.install.replaced).toEqual([join(dir, "plugins", "demo-1.0.jar")]);
    expect(existsSync(join(dir, "plugins", ".craftwire-backup", "demo-1.0.jar"))).toBe(true);
  });

  it("builds a project, then finds and installs its jar", async () => {
    hub = await startHub({ writeHubJson: true, javaMajor: 25, agentWaitMs: 2000 });
    const dir = makeServerDir({ craftwire: true });
    await hub.call("server_process", { action: "start", serverDir: dir, ...fakeLaunch("agent") });
    const project = mkdtempSync(join(tmpdir(), "cw-proj-"));
    pluginJar(join(project, "build", "libs"), "built-1.0.jar", "Built");
    const r = json(await hub.call("plugin_deploy", { projectDir: project, buildCommand: `${NODE} -e "process.exit(0)"` }));
    expect(r).toMatchObject({ plugin: { name: "Built" }, build: { command: expect.stringContaining("process.exit(0)") }, loaded: { enabled: true } });
  });

  it("returns parsed compiler errors when the build fails", async () => {
    hub = await startHub({ javaMajor: 25 });
    const r = await hub.call("plugin_deploy", {
      projectDir: mkdtempSync(join(tmpdir(), "cw-proj-")), serverDir: makeServerDir(),
      buildCommand: `${NODE} -e "console.log('/p/src/Foo.java:7: error: boom'); process.exit(1)"`,
    });
    expect(json(r)).toMatchObject({ code: "BUILD_FAILED", details: { exitCode: 1, errors: [{ file: "/p/src/Foo.java", line: 7, message: "boom" }] } });
  });

  it("spots a server running without the Craftwire plugin by its port, before building", async () => {
    hub = await startHub({ writeHubJson: true, javaMajor: 25 });
    const dir = makeServerDir();
    const busy = createServer();
    await new Promise<void>((r) => busy.listen(0, "127.0.0.1", () => r()));
    const port = (busy.address() as AddressInfo).port;
    writeFileSync(join(dir, "server.properties"), `server-ip=127.0.0.1
server-port=${port}
`);
    try {
      const project = mkdtempSync(join(tmpdir(), "cw-proj-"));
      const r = json(await hub.call("plugin_deploy", { projectDir: project, serverDir: dir, buildCommand: `${NODE} -e "require('fs').writeFileSync('built.txt', '')"` }));
      expect(r.code).toBe("NOT_MANAGED");
      expect(r.message).toContain(String(port));
      expect(r.hint).toMatch(/console/);
      expect(existsSync(join(project, "built.txt"))).toBe(false);
      // Without a restart the jar is installed for the running server's next start, as for one the hub knows.
      pluginJar(join(dir, "plugins"), "demo-1.0.jar", "Demo");
      const staged = json(await hub.call("plugin_deploy", { jar: pluginJar(mkdtempSync(join(tmpdir(), "cw-j-")), "demo-1.1.jar", "Demo"), serverDir: dir, restart: false }));
      expect(staged.install).toMatchObject({ installed: join(dir, "plugins", "update", "demo-1.0.jar"), needsRestart: true });
    } finally {
      busy.close();
    }
  });

  it("refuses to restart a server it did not start before running the build", async () => {
    hub = await startHub({ writeHubJson: true, javaMajor: 25 });
    const dir = makeServerDir();
    const child = spawn(process.execPath, [FAKE_PAPER, "--mode=agent"], { cwd: dir, env: { ...process.env, CRAFTWIRE_HOME: hub.home }, stdio: "pipe" });
    try {
      await waitUntil(() => hub!.agents.instances().length === 1, 5000);
      const project = mkdtempSync(join(tmpdir(), "cw-proj-"));
      const r = await hub.call("plugin_deploy", { projectDir: project, buildCommand: `${NODE} -e "require('fs').writeFileSync('built.txt', '')"` });
      expect(json(r).code).toBe("NOT_MANAGED");
      expect(existsSync(join(project, "built.txt"))).toBe(false);
    } finally {
      child.kill();
    }
  });
});
