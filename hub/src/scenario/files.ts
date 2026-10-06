import { readdirSync, readFileSync, statSync } from "node:fs";
import { basename, join, resolve } from "node:path";
import { parseScenario, ScenarioError, type Scenario } from "./scenario.js";

export const SCENARIO_SUFFIX = ".cwtest.json";
const SKIP_DIRS = new Set(["node_modules", "build", "dist", "target", "run"]);

/** The scenario files named, with folders searched for `*.cwtest.json` (sorted, so runs are repeatable). */
export function findScenarioFiles(paths: string[], cwd: string): string[] {
  const out: string[] = [];
  const walk = (dir: string) => {
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      if (e.isDirectory()) {
        if (!SKIP_DIRS.has(e.name) && !e.name.startsWith(".")) walk(join(dir, e.name));
      } else if (e.name.endsWith(SCENARIO_SUFFIX)) {
        out.push(join(dir, e.name));
      }
    }
  };
  for (const p of paths.length > 0 ? paths : ["."]) {
    const full = resolve(cwd, p);
    let isDir: boolean;
    try {
      isDir = statSync(full).isDirectory();
    } catch {
      throw new ScenarioError(`${p}: no such file or folder`);
    }
    if (isDir) walk(full);
    else out.push(full);
  }
  return [...new Set(out)].sort();
}

export function loadScenarioFile(file: string): Scenario {
  let raw: unknown;
  try {
    raw = JSON.parse(readFileSync(file, "utf8"));
  } catch (e) {
    throw new ScenarioError(`${file}: ${(e as Error).message}`);
  }
  try {
    return parseScenario(raw, basename(file, SCENARIO_SUFFIX));
  } catch (e) {
    throw new ScenarioError(`${file}: ${(e as Error).message}`);
  }
}
