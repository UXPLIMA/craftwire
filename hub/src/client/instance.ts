import { copyFileSync, existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { basename, join } from "node:path";
import { CraftwireError } from "../errors.js";

/** Settings for a client nobody watches; written once, never over the user's own values. */
export const DEFAULT_OPTIONS: Record<string, string> = {
  pauseOnLostFocus: "false",
  tutorialStep: "none",
  onboardAccessibility: "false",
  soundCategory_master: "0.0",
  narrator: "0",
  renderDistance: "8",
  // "afk" would also throttle a client that never gets keyboard or mouse input.
  inactivityFpsLimit: "minimized",
};

export interface InstanceOptions {
  username: string;
  agentJar: string;
  fabricApiJar: string;
  extraMods: string[];
}

/**
 * The game directory of one client_process client: `<root>/instances/<username>/`. `mods/` is rebuilt on every
 * start with exactly the agent, Fabric API and the extra mods; options.txt gets the defaults it lacks.
 */
export function prepareInstance(root: string, o: InstanceOptions): string {
  for (const mod of o.extraMods) {
    if (!existsSync(mod) || !statSync(mod).isFile()) throw new CraftwireError("INVALID_PARAMS", `${mod} does not exist`, "Pass absolute paths to mod jars.");
    if (!mod.toLowerCase().endsWith(".jar")) throw new CraftwireError("INVALID_PARAMS", `${mod} is not a jar`, "mods takes Fabric mod jars.");
  }
  const dir = join(root, "instances", o.username);
  const mods = join(dir, "mods");
  rmSync(mods, { recursive: true, force: true });
  mkdirSync(mods, { recursive: true });
  for (const jar of [o.agentJar, o.fabricApiJar, ...o.extraMods]) copyFileSync(jar, join(mods, basename(jar)));
  writeOptions(join(dir, "options.txt"));
  return dir;
}

function writeOptions(file: string): void {
  const text = existsSync(file) ? readFileSync(file, "utf8") : "";
  const present = new Set(text.split(/\r?\n/).map((l) => l.split(":")[0]).filter((k) => k));
  const missing = Object.entries(DEFAULT_OPTIONS).filter(([k]) => !present.has(k)).map(([k, v]) => `${k}:${v}`);
  if (missing.length === 0) return;
  const body = text === "" || text.endsWith("\n") ? text : `${text}\n`;
  writeFileSync(file, `${body}${missing.join("\n")}\n`);
}
