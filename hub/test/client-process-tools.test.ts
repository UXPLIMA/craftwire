import { mkdirSync } from "node:fs";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import type { PrepareClient } from "../src/client/client-manager.js";
import { waitUntil } from "../src/dev/server-manager.js";
import { connectFakeAgent } from "./helpers/fakeAgent.js";
import { json, startHub, TOKEN } from "./helpers/hub.js";

let hub: Awaited<ReturnType<typeof startHub>> | undefined;
afterEach(async () => {
  await hub?.close();
  hub = undefined;
});

describe("client_process", () => {
  it("starts a client, reports its status and stops it", async () => {
    let gameDir = "";
    const prepare: PrepareClient = async (p) => {
      gameDir = join(p.root, "instances", p.username);
      mkdirSync(gameDir, { recursive: true });
      return { command: process.execPath, args: ["-e", "setInterval(() => {}, 1000)"], gameDir, downloadedBytes: 7 };
    };
    hub = await startHub({ prepareClient: prepare, clientStopTimeoutMs: 1000 });
    const started = hub.call("client_process", { action: "start", username: "Bob", server: "127.0.0.1:25599" });
    await waitUntil(() => gameDir !== "" && hub!.clients.status().clients[0]?.state === "starting", 5000, 20);
    const agent = await connectFakeAgent(hub.port, { token: TOKEN, kind: "client", gameDir });
    agent.onRequest("player.state", () => ({ position: {} }));
    agent.onRequest("client.quit", () => ({ quitting: true }));
    expect(json(await started)).toMatchObject({ username: "Bob", state: "running", instance: agent.instanceId, downloadedBytes: 7 });
    expect(json(await hub.call("client_process", { action: "status" })).clients[0]).toMatchObject({ username: "Bob", state: "running" });
    expect(json(await hub.call("client_process", { action: "stop" }))).toMatchObject({ username: "Bob", stopped: true });
    await agent.close();
  });

  it("validates the username", async () => {
    hub = await startHub();
    const r = await hub.call("client_process", { action: "start", username: "no spaces" });
    expect(r.isError).toBe(true);
  });
});
