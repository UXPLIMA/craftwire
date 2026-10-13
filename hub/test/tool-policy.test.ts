import { afterEach, describe, expect, it } from "vitest";
import { parseHubArgs, parseToolGroups, ToolPolicy } from "../src/tool-policy.js";
import { connectFakeAgent } from "./helpers/fakeAgent.js";
import { json, startHub, TOKEN } from "./helpers/hub.js";

let hub: Awaited<ReturnType<typeof startHub>> | undefined;
afterEach(async () => { await hub?.close(); hub = undefined; });

describe("ToolPolicy", () => {
  it("read-only keeps tools that change nothing, and only their reading actions", () => {
    const p = new ToolPolicy({ readOnly: true });
    for (const t of ["list_instances", "logs", "exceptions", "screenshot", "gui_read", "hud_read", "player_state", "world_query", "world_render", "server_info", "wait_for"]) {
      expect(p.lists(t), t).toBe(true);
    }
    for (const t of ["server_command", "server_eval", "world_edit", "gui_action", "input", "plugin_deploy", "bot_spawn", "scenario_run", "camera", "myshop_coins"]) {
      expect(p.lists(t), t).toBe(false);
    }
    expect(p.refusal("server_process", { action: "status" })).toBeUndefined();
    expect(p.refusal("server_process", { action: "stop" })).toMatch(/read-only/);
    expect(p.refusal("plugin_manage", { action: "info" })).toBeUndefined();
    expect(p.refusal("plugin_manage", { action: "disable" })).toMatch(/read-only/);
    expect(p.refusal("chat", { action: "read" })).toBeUndefined();
    expect(p.refusal("chat", { action: "send" })).toMatch(/read-only/);
    expect(p.refusal("bot_action", { action: "messages" })).toBeUndefined();
    expect(p.refusal("bot_action", { action: "attack" })).toMatch(/read-only/);
    expect(p.refusal("events", { action: "watch" })).toBeUndefined();
    expect(p.refusal("record", { action: "status" })).toBeUndefined();
    expect(p.refusal("record", { action: "start", savePath: "a.mp4" })).toMatch(/read-only/);
    expect(p.refusal("client_settings", {})).toBeUndefined();
    expect(p.refusal("client_settings", { fov: 90 })).toMatch(/read-only/);
  });

  it("tool groups limit the surface; extension tools are their own group", () => {
    const p = new ToolPolicy({ groups: parseToolGroups("hub,server") });
    expect(p.lists("logs")).toBe(true);
    expect(p.lists("world_edit")).toBe(true);
    expect(p.lists("screenshot")).toBe(false);
    expect(p.lists("bot_spawn")).toBe(false);
    expect(p.lists("myshop_coins")).toBe(false);
    expect(new ToolPolicy({ groups: parseToolGroups("extensions") }).lists("myshop_coins")).toBe(true);
    expect(() => parseToolGroups("hub,teleport")).toThrow(/Unknown tool group teleport/);
  });

  it("no options allows everything", () => {
    const p = new ToolPolicy({});
    expect(p.lists("server_eval")).toBe(true);
    expect(p.refusal("server_process", { action: "stop" })).toBeUndefined();
  });
});

describe("a hub with a policy", () => {
  it("lists only the allowed tools and refuses writing actions", async () => {
    hub = await startHub({ policy: new ToolPolicy({ readOnly: true }) });
    const names = (await hub.client.listTools()).tools.map((t) => t.name);
    expect(names).toContain("world_render");
    expect(names).not.toContain("server_command");
    const r = json(await hub.call("server_process", { action: "stop" }));
    expect(r.code).toBe("READ_ONLY");
  });

  it("keeps extension tools out of a read-only hub", async () => {
    hub = await startHub({ policy: new ToolPolicy({ readOnly: true }) });
    const agent = await connectFakeAgent(hub.port, { token: TOKEN, kind: "server", name: "s" });
    agent.emit("tools", { tools: [{ name: "myshop_coins", namespace: "myshop", description: "d", inputSchema: { type: "object" } }] });
    await new Promise((r) => setTimeout(r, 200));
    expect((await hub.client.listTools()).tools.map((t) => t.name)).not.toContain("myshop_coins");
  });
});

describe("profiles", () => {
  it("build profile offers hub, build and the five viewing/editing tools", () => {
    const p = parseHubArgs(["--profile", "build"]).policy!;
    for (const t of ["list_instances", "build", "build_manage", "world_query", "world_render", "server_command", "screenshot", "camera"]) expect(p.lists(t), t).toBe(true);
    for (const t of ["bot_action", "record", "server_eval", "client_process"]) expect(p.lists(t), t).toBe(false);
  });

  it("--tools and --profile combine", () => {
    const p = parseHubArgs(["--profile", "build", "--tools", "bots"]).policy!;
    expect(p.lists("bot_spawn")).toBe(true);
    expect(p.lists("build")).toBe(true);
  });

  it("read-only still applies to a profile's extra tools", () => {
    const p = parseHubArgs(["--profile", "build", "--read-only"]).policy!;
    expect(p.lists("world_render")).toBe(true);
    expect(p.lists("server_command")).toBe(false);
  });

  it("no flags means no policy", () => {
    expect(parseHubArgs([]).policy).toBeUndefined();
  });

  it("an unknown profile is an error naming the profiles", () => {
    expect(() => parseHubArgs(["--profile", "nope"])).toThrow(/profiles: build/);
  });
});
