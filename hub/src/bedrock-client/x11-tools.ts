/**
 * The X11 command-line tools the Linux Bedrock client runs (Xvfb, xdotool, ImageMagick's import, ffmpeg): finding them
 * on PATH, running one, and the error that names the package to install when one is missing.
 */
import { execFile } from "node:child_process";
import { existsSync } from "node:fs";
import { delimiter, join } from "node:path";
import { CraftwireError } from "../errors.js";

/** The Debian/Ubuntu package of each tool, for the hint. */
const PACKAGES: Record<string, string> = {
  Xvfb: "xvfb", xdotool: "xdotool", import: "imagemagick", ffmpeg: "ffmpeg", flatpak: "flatpak",
};

export interface RunOptions {
  env: NodeJS.ProcessEnv;
  timeoutMs: number;
}

/** Runs a tool and resolves with its stdout; a tool that is not installed rejects with MISSING_TOOL. */
export type Run = (command: string, args: string[], o: RunOptions) => Promise<Buffer>;

export const runTool: Run = (command, args, o) => new Promise((resolve, reject) => {
  // 64 MB: a PNG of a 4K screen fits with room to spare.
  execFile(command, args, { env: o.env, timeout: o.timeoutMs, encoding: "buffer", maxBuffer: 64 << 20 }, (err, stdout, stderr) => {
    if (err === null) {
      resolve(stdout);
      return;
    }
    if ((err as NodeJS.ErrnoException).code === "ENOENT") {
      reject(missingTool(command));
      return;
    }
    const said = stderr.toString("utf8").trim().split("\n").slice(-3).join(" | ");
    reject(new CraftwireError("HELPER_FAILED", `${command} ${args[0] ?? ""} failed${said === "" ? "" : `: ${said}`}`,
      "bedrock_client {action:'status'} shows whether the game window is there; try again.", { exitCode: err.code }));
  });
});

export function missingTool(command: string): CraftwireError {
  const pkg = PACKAGES[command];
  return new CraftwireError("MISSING_TOOL", `${command} is not installed`,
    pkg === undefined ? `Install ${command} and put it on PATH.` : `Install it (Debian/Ubuntu: apt install ${pkg}).`);
}

/** The full path of an executable on PATH, or undefined. */
export function onPath(name: string, env: NodeJS.ProcessEnv = process.env, exists: (p: string) => boolean = existsSync): string | undefined {
  for (const dir of (env.PATH ?? "").split(delimiter)) {
    if (dir === "") continue;
    const p = join(dir, name);
    if (exists(p)) return p;
  }
  return undefined;
}
