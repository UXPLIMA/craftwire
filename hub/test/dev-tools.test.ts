import { afterEach, describe, expect, it } from "vitest";
import { json, startHub } from "./helpers/hub.js";
import { fakeLaunch, makeServerDir } from "./helpers/servers.js";

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
