import { execFileSync, execSync, spawn } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { HUB_VERSION } from "../src/version.js";
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
  it("exposes every built-in tool", async () => {
    const names = (await client.listTools()).tools.map((t) => t.name).sort();
    expect(names).toEqual([
      "bot_action", "bot_remove", "bot_spawn", "camera", "chat", "client_eval", "client_process", "client_settings", "events", "exceptions", "get_request_status", "gui_action", "gui_read",
      "hud_read", "input", "list_instances", "logs", "player_state", "plugin_deploy", "plugin_manage", "profile", "record", "scenario_run", "screenshot",
      "server_command", "server_eval", "server_info", "server_process", "trace", "wait_for", "world_edit", "world_query", "world_render",
    ]);
  });

  it("doctor reports the running hub", () => {
    const out = execFileSync(process.execPath, [join(hubDir, "dist", "cli.js"), "doctor"], { env: { ...process.env, CRAFTWIRE_HOME: home }, encoding: "utf8" });
    expect(out).toContain(`[ok] hub ${HUB_VERSION} is running`);
  });

  it("writes hub.json and accepts an agent using it", async () => {
    const cfg = JSON.parse(readFileSync(join(home, "hub.json"), "utf8"));
    expect(cfg.port).toBeGreaterThan(0);
    expect(cfg.token).toMatch(/^[0-9a-f]{64}$/);
    await connectFakeAgent(cfg.port, { token: cfg.token, name: "E2E" });
    const res = (await client.callTool({ name: "list_instances", arguments: {} })) as CallToolResult;
    expect(JSON.parse((res.content[0] as { text: string }).text).instances[0].name).toBe("E2E");
  });

  describe("setup", () => {
    // A fake home for every OS variable a client path is built from: never the real configs.
    const fake = mkdtempSync(join(tmpdir(), "cw-cli-setup-"));
    const env = {
      ...process.env, HOME: fake, USERPROFILE: fake, APPDATA: join(fake, "AppData", "Roaming"),
      XDG_CONFIG_HOME: join(fake, ".config"), CODEX_HOME: join(fake, ".codex"),
    };
    const setup = (...args: string[]) =>
      execFileSync(process.execPath, [join(hubDir, "dist", "cli.js"), "setup", ...args], { env, encoding: "utf8" });

    it("with no client lists the known and the detected clients", () => {
      mkdirSync(join(fake, ".cursor"), { recursive: true });
      const out = setup();
      expect(out).toContain("Found on this computer: cursor");
      expect(out).toMatch(/antigravity.*codex.*gemini.*cursor.*windsurf.*vscode.*claude-desktop/s);
    });

    it("--dry-run shows the change and writes nothing", () => {
      const out = setup("codex", "--dry-run");
      expect(out).toContain("[mcp_servers.craftwire]");
      expect(out).toMatch(/skills/i);
      expect(existsSync(join(fake, ".codex"))).toBe(false);
    });

    it("writes the client config and the skills", () => {
      const out = setup("codex");
      expect(out).toContain("Restart Codex");
      expect(readFileSync(join(fake, ".codex", "config.toml"), "utf8")).toContain(`craftwire@${HUB_VERSION}`);
      expect(existsSync(join(fake, ".codex", "skills", "craftwire", "SKILL.md"))).toBe(true);
      expect(existsSync(join(fake, ".codex", "skills", "fabric-mod-dev", "SKILL.md"))).toBe(true);
    });

    it("points Claude Code users to the plugin", () => {
      expect(setup("claude-code")).toContain("/plugin install craftwire@uxplima");
    });
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
