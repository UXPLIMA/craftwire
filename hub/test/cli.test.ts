import { execSync, spawn } from "node:child_process";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { connectFakeAgent } from "./helpers/fakeAgent.js";

const hubDir = join(__dirname, "..");
const home = mkdtempSync(join(tmpdir(), "cw-cli-"));
let client: Client;

beforeAll(async () => {
  execSync("npm run build", { cwd: hubDir, stdio: "inherit" });
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [join(hubDir, "dist", "cli.js")],
    env: { ...(process.env as Record<string, string>), CRAFTWIRE_HOME: home, CRAFTWIRE_PORT: "0" },
    stderr: "pipe",
  });
  client = new Client({ name: "e2e", version: "0.0.0" });
  await client.connect(transport);
}, 120_000);

afterAll(async () => { await client?.close(); });

describe("craftwire CLI over stdio", () => {
  it("exposes all M1, M2 and M3 tools", async () => {
    const names = (await client.listTools()).tools.map((t) => t.name).sort();
    expect(names).toEqual([
      "camera", "chat", "client_settings", "get_request_status", "gui_action", "gui_read",
      "hud_read", "input", "list_instances", "logs", "player_state", "plugin_manage", "screenshot",
      "server_command", "server_eval", "server_info", "server_process", "wait_for", "world_edit", "world_query",
    ]);
  });

  it("writes hub.json and accepts an agent using it", async () => {
    const cfg = JSON.parse(readFileSync(join(home, "hub.json"), "utf8"));
    expect(cfg.port).toBeGreaterThan(0);
    expect(cfg.token).toMatch(/^[0-9a-f]{64}$/);
    await connectFakeAgent(cfg.port, { token: cfg.token, name: "E2E" });
    const res = (await client.callTool({ name: "list_instances", arguments: {} })) as CallToolResult;
    expect(JSON.parse((res.content[0] as { text: string }).text).instances[0].name).toBe("E2E");
  });

  it("exits cleanly when stdin closes (Claude Code went away)", async () => {
    const child = spawn(process.execPath, [join(hubDir, "dist", "cli.js")], {
      env: { ...process.env, CRAFTWIRE_HOME: mkdtempSync(join(tmpdir(), "cw-cli-eof-")), CRAFTWIRE_PORT: "0" },
      stdio: ["pipe", "ignore", "ignore"],
    });
    await new Promise((r) => setTimeout(r, 500));
    child.stdin.end();
    const code = await new Promise<number | null>((resolve) => {
      const t = setTimeout(() => { child.kill(); resolve(-1); }, 5000);
      child.once("exit", (c) => { clearTimeout(t); resolve(c); });
    });
    expect(code).toBe(0);
  });
});
