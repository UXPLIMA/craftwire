/** Files in a client's game directory that client_process reads or clears: crash reports and saves. */
import { existsSync, readdirSync, rmSync, statSync } from "node:fs";
import { join, relative, resolve, sep } from "node:path";
import { CraftwireError } from "../errors.js";

export function newestCrashReport(gameDir: string, since: number): string | undefined {
  const dir = join(gameDir, "crash-reports");
  if (!existsSync(dir)) return undefined;
  const files = readdirSync(dir).map((f) => join(dir, f)).filter((f) => statSync(f).mtimeMs >= since - 1000);
  return files.sort((a, b) => statSync(b).mtimeMs - statSync(a).mtimeMs)[0];
}

/** Deletes saves/<name> of a client's game directory, refusing anything that would resolve outside saves/. */
export function removeSave(gameDir: string, name: string): void {
  const saves = resolve(gameDir, "saves");
  const target = resolve(saves, name);
  const rel = relative(saves, target);
  if (rel === "" || rel.startsWith("..") || rel.includes(sep)) throw new CraftwireError("INVALID_PARAMS", `"${name}" is not a save folder name`);
  rmSync(target, { recursive: true, force: true });
}
