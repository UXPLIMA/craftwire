import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { basename, dirname, join } from "node:path";
import { HUB_VERSION } from "./version.js";

/** Where `craftwire setup` looks. Tests point every field at a temp dir; the CLI fills it from the process. */
export interface SetupEnv {
  home: string;
  /** %APPDATA% on Windows. */
  appData?: string;
  platform: NodeJS.Platform;
  codexHome?: string;
  /** The skills shipped with the package (one folder per skill, each with SKILL.md). */
  skillsDir?: string;
}

type Format = "mcpServers" | "vscode" | "codex";

export interface ClientSpec {
  id: string;
  name: string;
  format: Format;
  config(env: SetupEnv): string;
  /** A folder whose presence means the client is installed. */
  marker(env: SetupEnv): string;
  /** Agent Skills folder (SKILL.md standard), for clients that load skills. */
  skills?(env: SetupEnv): string;
}

/** The per-user app config folder: %APPDATA%, ~/Library/Application Support, or ~/.config. */
function appConfig(env: SetupEnv): string {
  if (env.platform === "win32") return env.appData ?? join(env.home, "AppData", "Roaming");
  if (env.platform === "darwin") return join(env.home, "Library", "Application Support");
  return join(env.home, ".config");
}

const codexHome = (env: SetupEnv) => env.codexHome ?? join(env.home, ".codex");

export const CLIENTS: ClientSpec[] = [
  {
    id: "antigravity", name: "Antigravity", format: "mcpServers",
    config: (e) => {
      const current = join(e.home, ".gemini", "config", "mcp_config.json");
      const older = join(e.home, ".gemini", "antigravity", "mcp_config.json");
      return !existsSync(current) && existsSync(older) ? older : current;
    },
    marker: (e) => join(e.home, ".gemini", "antigravity"),
  },
  {
    id: "codex", name: "Codex", format: "codex",
    config: (e) => join(codexHome(e), "config.toml"),
    marker: codexHome,
    skills: (e) => join(codexHome(e), "skills"),
  },
  {
    id: "gemini", name: "Gemini CLI", format: "mcpServers",
    config: (e) => join(e.home, ".gemini", "settings.json"),
    marker: (e) => join(e.home, ".gemini", "settings.json"),
    skills: (e) => join(e.home, ".gemini", "skills"),
  },
  {
    id: "cursor", name: "Cursor", format: "mcpServers",
    config: (e) => join(e.home, ".cursor", "mcp.json"),
    marker: (e) => join(e.home, ".cursor"),
  },
  {
    id: "windsurf", name: "Windsurf", format: "mcpServers",
    config: (e) => join(e.home, ".codeium", "windsurf", "mcp_config.json"),
    marker: (e) => join(e.home, ".codeium", "windsurf"),
  },
  {
    id: "vscode", name: "VS Code", format: "vscode",
    config: (e) => join(appConfig(e), "Code", "User", "mcp.json"),
    marker: (e) => join(appConfig(e), "Code", "User"),
  },
  {
    id: "claude-desktop", name: "Claude Desktop", format: "mcpServers",
    config: (e) => join(appConfig(e), "Claude", "claude_desktop_config.json"),
    marker: (e) => join(appConfig(e), "Claude"),
  },
];

export interface SetupPlan {
  client: ClientSpec;
  configPath: string;
  change: "create" | "update" | "none";
  before?: string;
  after: string;
  /** Skill folders to copy: [source, target]. */
  skills: Array<[string, string]>;
  /** Same-named skills already there that are not Craftwire's: left alone. */
  skipped: string[];
}

/** How the client starts the hub. Windows clients often cannot start npx.cmd without a shell. */
export function launchCommand(platform: NodeJS.Platform): { command: string; args: string[] } {
  const npx = ["-y", `craftwire@${HUB_VERSION}`];
  return platform === "win32" ? { command: "cmd", args: ["/c", "npx", ...npx] } : { command: "npx", args: npx };
}

export function findClient(id: string): ClientSpec {
  const client = CLIENTS.find((c) => c.id === id);
  if (!client) throw new Error(`unknown client "${id}"; known: ${CLIENTS.map((c) => c.id).join(", ")}`);
  return client;
}

export function detectClients(env: SetupEnv): string[] {
  return CLIENTS.filter((c) => existsSync(c.marker(env))).map((c) => c.id);
}

/** Works out what setup would change, without writing anything (`--dry-run` prints this). */
export function planSetup(id: string, env: SetupEnv): SetupPlan {
  const client = findClient(id);
  const configPath = client.config(env);
  const before = existsSync(configPath) ? readFileSync(configPath, "utf8") : undefined;
  const launch = launchCommand(env.platform);
  const after = client.format === "codex" ? codexConfig(before ?? "", launch) : jsonConfig(configPath, before, client.format, launch);
  const change = before === undefined ? "create" : before === after ? "none" : "update";
  const plan: SetupPlan = { client, configPath, change, after, skills: [], skipped: [] };
  if (before !== undefined) plan.before = before;
  if (client.skills && env.skillsDir && existsSync(env.skillsDir)) {
    const target = client.skills(env);
    for (const name of readdirSync(env.skillsDir)) {
      const src = join(env.skillsDir, name);
      if (!existsSync(join(src, "SKILL.md"))) continue;
      const dst = join(target, name);
      const existing = join(dst, "SKILL.md");
      if (existsSync(existing) && !/craftwire/i.test(readFileSync(existing, "utf8"))) plan.skipped.push(name);
      else plan.skills.push([src, dst]);
    }
  }
  return plan;
}

export function applySetup(plan: SetupPlan): void {
  if (plan.change !== "none") {
    mkdirSync(dirname(plan.configPath), { recursive: true });
    if (plan.before !== undefined) writeFileSync(`${plan.configPath}.bak`, plan.before);
    writeFileSync(plan.configPath, plan.after);
  }
  for (const [src, dst] of plan.skills) {
    rmSync(dst, { recursive: true, force: true });
    cpSync(src, dst, { recursive: true });
  }
}

function jsonConfig(path: string, text: string | undefined, format: Format, launch: { command: string; args: string[] }): string {
  let data: Record<string, unknown> = {};
  if (text !== undefined && text.trim() !== "") {
    try {
      data = JSON.parse(withoutBom(text)) as Record<string, unknown>;
    } catch (e) {
      // Comments (JSONC) or a broken file: rewriting it would lose what the user wrote.
      throw new Error(`could not read ${path} as JSON (${(e as Error).message}); add the craftwire entry by hand, see docs/clients.md`);
    }
    if (typeof data !== "object" || data === null || Array.isArray(data)) throw new Error(`${path} is not a JSON object`);
  }
  const key = format === "vscode" ? "servers" : "mcpServers";
  const servers = (data[key] && typeof data[key] === "object" ? data[key] : {}) as Record<string, unknown>;
  servers.craftwire = format === "vscode" ? { type: "stdio", ...launch } : launch;
  data[key] = servers;
  const out = `${JSON.stringify(data, null, 2)}\n`;
  // Keep an untouched file byte-identical so a rerun reports "none".
  if (text !== undefined && text.trim() !== "" && sameJson(text, out)) return text;
  return out;
}

/** Windows Notepad saves UTF-8 with a byte order mark, which JSON.parse rejects. */
const withoutBom = (text: string) => text.replace(/^﻿/, "");

function sameJson(a: string, b: string): boolean {
  try {
    return JSON.stringify(JSON.parse(withoutBom(a))) === JSON.stringify(JSON.parse(b));
  } catch {
    return false;
  }
}

const CODEX_HEADER = /^\s*\[\s*mcp_servers\s*\.\s*(?:craftwire|"craftwire")\s*(?:\]|\.)/;

/** Replaces (or appends) the [mcp_servers.craftwire] table and its sub-tables; every other line stays as it was. */
function codexConfig(text: string, launch: { command: string; args: string[] }): string {
  const eol = text.includes("\r\n") ? "\r\n" : "\n";
  const table = [
    "[mcp_servers.craftwire]",
    `command = ${JSON.stringify(launch.command)}`,
    `args = [${launch.args.map((a) => JSON.stringify(a)).join(", ")}]`,
    // The first npx run downloads the package; Codex's default 10 s is too short for that.
    "startup_timeout_sec = 60",
    "",
  ];
  const out: string[] = [];
  let skipping = false;
  let at = -1;
  for (const line of text.split(/\r?\n/)) {
    if (/^\s*\[/.test(line)) {
      skipping = CODEX_HEADER.test(line);
      if (skipping && at < 0) at = out.length;
    }
    if (!skipping) out.push(line);
  }
  if (at >= 0) {
    out.splice(at, 0, ...table);
    return out.join(eol);
  }
  const body = text === "" ? "" : text.endsWith("\n") ? text + eol : text + eol + eol;
  return body + table.join(eol);
}

/** The real locations, for the CLI. */
export function setupEnvFromProcess(skillsDir: string | undefined): SetupEnv {
  const env: SetupEnv = { home: homedir(), platform: process.platform };
  if (process.env.APPDATA) env.appData = process.env.APPDATA;
  if (process.env.CODEX_HOME) env.codexHome = process.env.CODEX_HOME;
  if (skillsDir) env.skillsDir = skillsDir;
  return env;
}

const CLAUDE_CODE = ["claude", "claude-code"];

/** `craftwire setup [client] [--dry-run]`: returns what to print. */
export function setupCommand(args: string[], env: SetupEnv): string {
  const dryRun = args.includes("--dry-run");
  const id = args.find((a) => !a.startsWith("--"));
  const lines: string[] = [];
  if (!id) {
    const found = detectClients(env);
    lines.push(`Found on this computer: ${found.length ? found.join(", ") : "none of the clients below"}`, "");
    lines.push("Usage: npx craftwire setup <client> [--dry-run]", "");
    for (const c of CLIENTS) lines.push(`  ${c.id.padEnd(15)} ${c.name}${c.skills ? " (+ skills)" : ""}`);
    lines.push(`  ${"claude-code".padEnd(15)} Claude Code: use the plugin instead`);
    return lines.join("\n") + "\n";
  }
  if (CLAUDE_CODE.includes(id)) {
    return [
      "Claude Code installs Craftwire as a plugin (hub, skills and updates in one). In Claude Code run:",
      "  /plugin marketplace add uxplima/craftwire",
      "  /plugin install craftwire@uxplima",
    ].join("\n") + "\n";
  }
  const plan = planSetup(id, env);
  const name = plan.client.name;
  if (dryRun) {
    if (plan.change === "none") lines.push(`${plan.configPath} is already set up.`);
    else lines.push(`Would ${plan.change} ${plan.configPath}${plan.before !== undefined ? ` (backup: ${plan.configPath}.bak)` : ""}:`, "", plan.after);
    if (plan.skills.length) lines.push(`Would copy skills to ${dirname(plan.skills[0]![1])}: ${plan.skills.map(([s]) => basename(s)).join(", ")}`);
  } else {
    applySetup(plan);
    if (plan.change === "none") lines.push(`${name}: ${plan.configPath} is already set up.`);
    else lines.push(`${name}: ${plan.change === "create" ? "created" : "updated"} ${plan.configPath}${plan.before !== undefined ? ` (backup: ${plan.configPath}.bak)` : ""}`);
    if (plan.skills.length) lines.push(`Skills copied to ${dirname(plan.skills[0]![1])}: ${plan.skills.map(([s]) => basename(s)).join(", ")}`);
    lines.push(`Restart ${name}, then ask it to run list_instances. Next: the agent mod or plugin from https://github.com/uxplima/craftwire/releases`);
  }
  if (plan.skipped.length) lines.push(`Left alone (a skill with that name is already there and is not Craftwire's): ${plan.skipped.join(", ")}`);
  return lines.join("\n") + "\n";
}
