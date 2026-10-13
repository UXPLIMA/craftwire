import { existsSync, readFileSync, realpathSync } from "node:fs";
import { isAbsolute, relative, resolve } from "node:path";
import { CraftwireError } from "./errors.js";

/**
 * A build script on the hub's machine. Over HTTP the caller is remote, so files are read only from --build-dir.
 */
export function readBuildFile(path: string, o: { dir?: string; http: boolean }, cwd: string = process.cwd()): { code: string; file: string } {
  if (o.http && o.dir === undefined) {
    throw new CraftwireError("BAD_ARGUMENT", "file is not available on this HTTP hub (it was started without --build-dir)", "Start craftwire serve with --build-dir <dir>, or pass code instead.");
  }
  const base = o.dir !== undefined ? resolve(cwd, o.dir) : cwd;
  const full = resolve(base, path);
  if (!existsSync(full)) throw new CraftwireError("BAD_ARGUMENT", `No build file at ${full}`, "Write the script first, or check the path (relative paths start at the hub's working directory).");
  if (o.dir !== undefined) {
    const rel = relative(realpathSync(base), realpathSync(full));
    if (rel.startsWith("..") || isAbsolute(rel)) throw new CraftwireError("BAD_ARGUMENT", `${full} is outside --build-dir ${base}`, "Put build scripts inside the --build-dir folder.");
  }
  return { code: readFileSync(full, "utf8"), file: full };
}
