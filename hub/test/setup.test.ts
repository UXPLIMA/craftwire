import { existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { describe, expect, it } from "vitest";
import { applySetup, CLIENTS, detectClients, planSetup, type SetupEnv } from "../src/setup.js";
import { HUB_VERSION } from "../src/version.js";

/** Every test gets its own fake home and AppData: nothing here may touch the real client configs. */
function fakeEnv(platform: NodeJS.Platform = "linux"): SetupEnv {
  const root = mkdtempSync(join(tmpdir(), "cw-setup-"));
  const skills = join(root, "skills-src");
  for (const name of ["craftwire", "paper-plugin-dev"]) {
    mkdirSync(join(skills, name), { recursive: true });
    writeFileSync(join(skills, name, "SKILL.md"), `---\nname: ${name}\n---\nUse the craftwire tools.\n`);
  }
  return { home: join(root, "home"), appData: join(root, "appdata"), platform, skillsDir: skills };
}

function put(path: string, text: string): void {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, text);
}

const json = (path: string) => JSON.parse(readFileSync(path, "utf8"));
const PKG = `craftwire@${HUB_VERSION}`;

describe("craftwire setup: JSON mcpServers clients", () => {
  const paths: Record<string, (e: SetupEnv) => string> = {
    antigravity: (e) => join(e.home, ".gemini", "config", "mcp_config.json"),
    gemini: (e) => join(e.home, ".gemini", "settings.json"),
    cursor: (e) => join(e.home, ".cursor", "mcp.json"),
    windsurf: (e) => join(e.home, ".codeium", "windsurf", "mcp_config.json"),
    "claude-desktop": (e) => join(e.home, ".config", "Claude", "claude_desktop_config.json"),
  };

  it("claude-desktop: uses %APPDATA% on Windows", () => {
    const env = fakeEnv("win32");
    expect(planSetup("claude-desktop", env).configPath).toBe(join(env.appData!, "Claude", "claude_desktop_config.json"));
  });

  for (const [client, pathOf] of Object.entries(paths)) {
    it(`${client}: creates the config with a craftwire entry`, () => {
      const env = fakeEnv();
      const plan = planSetup(client, env);
      expect(plan.configPath).toBe(pathOf(env));
      applySetup(plan);
      expect(json(pathOf(env)).mcpServers.craftwire).toEqual({ command: "npx", args: ["-y", PKG] });
    });
  }

  it("keeps the user's other servers and settings, and backs the file up first", () => {
    const env = fakeEnv();
    const path = paths.cursor(env);
    const original = JSON.stringify({ mcpServers: { github: { command: "gh-mcp" } }, theme: "dark" }, null, 2);
    put(path, original);
    applySetup(planSetup("cursor", env));
    const after = json(path);
    expect(after.mcpServers.github).toEqual({ command: "gh-mcp" });
    expect(after.theme).toBe("dark");
    expect(after.mcpServers.craftwire.args).toEqual(["-y", PKG]);
    expect(readFileSync(`${path}.bak`, "utf8")).toBe(original);
  });

  it("updates an old craftwire entry in place", () => {
    const env = fakeEnv();
    const path = paths.gemini(env);
    put(path, JSON.stringify({ mcpServers: { craftwire: { command: "npx", args: ["-y", "craftwire@0.1.0"] } } }));
    const plan = planSetup("gemini", env);
    expect(plan.change).toBe("update");
    applySetup(plan);
    expect(json(path).mcpServers.craftwire.args).toEqual(["-y", PKG]);
  });

  it("does nothing when the entry is already current", () => {
    const env = fakeEnv();
    applySetup(planSetup("cursor", env));
    const plan = planSetup("cursor", env);
    expect(plan.change).toBe("none");
    applySetup(plan);
    expect(existsSync(`${paths.cursor(env)}.bak`)).toBe(false);
  });

  it("refuses a config it cannot parse instead of overwriting it", () => {
    const env = fakeEnv();
    const path = paths.cursor(env);
    put(path, "{ // my servers\n \"mcpServers\": {} }");
    expect(() => planSetup("cursor", env)).toThrow(/could not read .*mcp\.json/);
    expect(readFileSync(path, "utf8")).toContain("// my servers");
  });

  it("an empty config file counts as an empty object", () => {
    const env = fakeEnv();
    put(paths.windsurf(env), "");
    applySetup(planSetup("windsurf", env));
    expect(json(paths.windsurf(env)).mcpServers.craftwire.command).toBe("npx");
  });

  it("uses cmd /c npx on Windows", () => {
    const env = fakeEnv("win32");
    applySetup(planSetup("cursor", env));
    expect(json(paths.cursor(env)).mcpServers.craftwire).toEqual({ command: "cmd", args: ["/c", "npx", "-y", PKG] });
  });

  it("antigravity: keeps using the older config location when only that one exists", () => {
    const env = fakeEnv();
    const old = join(env.home, ".gemini", "antigravity", "mcp_config.json");
    put(old, "{}");
    expect(planSetup("antigravity", env).configPath).toBe(old);
  });
});

describe("craftwire setup: VS Code", () => {
  it("writes a stdio entry under servers in the user mcp.json", () => {
    const env = fakeEnv("win32");
    const path = join(env.appData!, "Code", "User", "mcp.json");
    put(path, JSON.stringify({ servers: { other: { type: "http", url: "https://x" } }, inputs: [] }));
    applySetup(planSetup("vscode", env));
    const after = json(path);
    expect(after.servers.craftwire).toEqual({ type: "stdio", command: "cmd", args: ["/c", "npx", "-y", PKG] });
    expect(after.servers.other.url).toBe("https://x");
    expect(after.inputs).toEqual([]);
  });

  it("finds the user folder on macOS and Linux", () => {
    const mac = fakeEnv("darwin");
    expect(planSetup("vscode", mac).configPath).toBe(join(mac.home, "Library", "Application Support", "Code", "User", "mcp.json"));
    const linux = fakeEnv("linux");
    expect(planSetup("vscode", linux).configPath).toBe(join(linux.home, ".config", "Code", "User", "mcp.json"));
  });
});

describe("craftwire setup: Codex", () => {
  it("appends an mcp_servers.craftwire table and keeps the rest of config.toml", () => {
    const env = fakeEnv();
    const path = join(env.home, ".codex", "config.toml");
    const original = 'model = "gpt-5"\n\n[mcp_servers.github]\ncommand = "gh-mcp"\n';
    put(path, original);
    applySetup(planSetup("codex", env));
    const text = readFileSync(path, "utf8");
    expect(text.startsWith(original)).toBe(true);
    expect(text).toContain(`[mcp_servers.craftwire]\ncommand = "npx"\nargs = ["-y", "${PKG}"]\nstartup_timeout_sec = 60\n`);
    expect(readFileSync(`${path}.bak`, "utf8")).toBe(original);
  });

  it("replaces an existing craftwire table and its sub-tables, leaving the tables after it", () => {
    const env = fakeEnv();
    const path = join(env.home, ".codex", "config.toml");
    put(path, '[mcp_servers.craftwire]\ncommand = "old"\n\n[mcp_servers.craftwire.env]\nX = "1"\n\n[mcp_servers.github]\ncommand = "gh-mcp"\n');
    applySetup(planSetup("codex", env));
    const text = readFileSync(path, "utf8");
    expect(text).not.toContain('"old"');
    expect(text).not.toContain("craftwire.env");
    expect(text.match(/\[mcp_servers\.craftwire\]/g)).toHaveLength(1);
    expect(text).toContain('[mcp_servers.github]\ncommand = "gh-mcp"\n');
  });

  it("keeps Windows line endings", () => {
    const env = fakeEnv();
    const path = join(env.home, ".codex", "config.toml");
    put(path, 'model = "gpt-5"\r\n\r\n[mcp_servers.craftwire]\r\ncommand = "old"\r\n');
    applySetup(planSetup("codex", env));
    const text = readFileSync(path, "utf8");
    expect(text.replace(/\r\n/g, "")).not.toContain("\n");
    expect(text).toContain('startup_timeout_sec = 60\r\n');
  });

  it("honours CODEX_HOME", () => {
    const env = { ...fakeEnv(), codexHome: join(tmpdir(), "elsewhere") };
    expect(planSetup("codex", env).configPath).toBe(join(env.codexHome, "config.toml"));
  });

  it("is idempotent", () => {
    const env = fakeEnv("win32");
    applySetup(planSetup("codex", env));
    expect(planSetup("codex", env).change).toBe("none");
    expect(readFileSync(join(env.home, ".codex", "config.toml"), "utf8")).toContain(`args = ["/c", "npx", "-y", "${PKG}"]`);
  });
});

describe("craftwire setup: skills", () => {
  it("copies the skills for Codex and Gemini CLI", () => {
    for (const [client, dir] of [["codex", [".codex", "skills"]], ["gemini", [".gemini", "skills"]]] as const) {
      const env = fakeEnv();
      applySetup(planSetup(client, env));
      expect(readFileSync(join(env.home, ...dir, "craftwire", "SKILL.md"), "utf8")).toContain("name: craftwire");
      expect(existsSync(join(env.home, ...dir, "paper-plugin-dev", "SKILL.md"))).toBe(true);
    }
  });

  it("leaves a same-named skill that is not ours alone", () => {
    const env = fakeEnv();
    const theirs = join(env.home, ".codex", "skills", "paper-plugin-dev", "SKILL.md");
    put(theirs, "---\nname: paper-plugin-dev\n---\nMy own notes.\n");
    const plan = planSetup("codex", env);
    expect(plan.skipped).toEqual(["paper-plugin-dev"]);
    applySetup(plan);
    expect(readFileSync(theirs, "utf8")).toContain("My own notes.");
  });

  it("clients without a skills folder get no skills", () => {
    expect(planSetup("cursor", fakeEnv()).skills).toEqual([]);
  });
});

describe("craftwire setup: dry run and detection", () => {
  it("a plan writes nothing until it is applied", () => {
    const env = fakeEnv();
    const plan = planSetup("codex", env);
    expect(plan.change).toBe("create");
    expect(plan.after).toContain("[mcp_servers.craftwire]");
    expect(existsSync(plan.configPath)).toBe(false);
    expect(existsSync(join(env.home, ".codex", "skills"))).toBe(false);
  });

  it("detects the clients installed in the fake home", () => {
    const env = fakeEnv("win32");
    mkdirSync(join(env.home, ".cursor"), { recursive: true });
    mkdirSync(join(env.home, ".codex"), { recursive: true });
    mkdirSync(join(env.appData!, "Code", "User"), { recursive: true });
    expect(detectClients(env).sort()).toEqual(["codex", "cursor", "vscode"]);
  });

  it("rejects an unknown client and lists the known ones", () => {
    expect(() => planSetup("chatgpt", fakeEnv())).toThrow(/unknown client "chatgpt".*codex/);
    expect(CLIENTS.map((c) => c.id)).toEqual(["antigravity", "codex", "gemini", "cursor", "windsurf", "vscode", "claude-desktop"]);
  });
});
