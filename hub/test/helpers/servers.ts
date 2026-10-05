import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { makeZip } from "./zip.js";

export const FAKE_PAPER = join(__dirname, "fake-paper.mjs");

/** A throwaway server folder: empty server.jar, eula.txt (true unless told otherwise), plugins/, optionally a Craftwire jar. */
export function makeServerDir(o: { eula?: boolean; craftwire?: boolean } = {}): string {
  const dir = mkdtempSync(join(tmpdir(), "cw-srv-"));
  mkdirSync(join(dir, "plugins"));
  writeFileSync(join(dir, "server.jar"), "");
  writeFileSync(join(dir, "eula.txt"), `eula=${o.eula ?? true}\n`);
  if (o.craftwire) makeZip(join(dir, "plugins", "craftwire.jar"), { "plugin.yml": "name: Craftwire\nversion: 0.3.0\nmain: a.B\n" });
  return dir;
}

/** Launch parameters that run fake-paper.mjs with node: node ignores -jar/--nogui after the script path. */
export const fakeLaunch = (mode: "ok" | "agent" | "crash" | "hang" | "nostop") =>
  ({ java: process.execPath, jvmArgs: [FAKE_PAPER, `--mode=${mode}`], jar: "server.jar" });
