/**
 * Finds mcpelauncher (minecraft-linux, the open-source launcher that runs the Android Bedrock game on Linux) and the
 * game version its launcher downloaded from Google Play with the user's own account. Craftwire never downloads the
 * game itself.
 */
import { spawnSync } from "node:child_process";
import { existsSync, readdirSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { CraftwireError } from "../errors.js";
import { onPath } from "./x11-tools.js";

export const FLATPAK_ID = "io.mrarm.mcpelauncher";
const CLIENT = "mcpelauncher-client";
const SITE = "https://minecraft-linux.github.io";

export interface Launcher {
  /** The program and the arguments before the game's own ones. */
  command: string;
  args: string[];
  /** The version folder: AndroidManifest.xml, assets/ and lib/<abi>/libminecraftpe.so. Ends in a slash. */
  gameDir: string;
}

export interface FindDeps {
  env: NodeJS.ProcessEnv;
  home: string;
  flatpakInstalled: () => boolean;
}

const defaults: FindDeps = {
  env: process.env,
  home: homedir(),
  flatpakInstalled: () => spawnSync("flatpak", ["info", FLATPAK_ID], { stdio: "ignore" }).status === 0,
};

/**
 * CRAFTWIRE_MCPELAUNCHER (the client program) or mcpelauncher-client on PATH, else the Flatpak; the game folder is
 * CRAFTWIRE_BEDROCK_GAME_DIR or the newest version the launcher downloaded.
 */
export function findLauncher(deps: Partial<FindDeps> = {}): Launcher {
  const d: FindDeps = { ...defaults, ...deps };
  const own = d.env.CRAFTWIRE_MCPELAUNCHER;
  const path = own !== undefined && own !== "" ? own : onPath(CLIENT, d.env);
  let command: string;
  let args: string[] = [];
  let dataHome: string;
  if (path !== undefined) {
    command = path;
    dataHome = d.env.XDG_DATA_HOME !== undefined && d.env.XDG_DATA_HOME !== "" ? d.env.XDG_DATA_HOME : join(d.home, ".local", "share");
  } else if (d.flatpakInstalled()) {
    command = "flatpak";
    args = ["run", `--command=${CLIENT}`, FLATPAK_ID];
    // A Flatpak keeps its data under ~/.var/app/<id>, mounted at the same path inside the sandbox.
    dataHome = join(d.home, ".var", "app", FLATPAK_ID, "data");
  } else {
    throw new CraftwireError("MCPELAUNCHER_NOT_FOUND", "mcpelauncher, which runs Bedrock on Linux, is not installed",
      `Install it (${SITE}), or set CRAFTWIRE_MCPELAUNCHER to its ${CLIENT} program.`);
  }
  const dir = d.env.CRAFTWIRE_BEDROCK_GAME_DIR;
  const gameDir = dir !== undefined && dir !== "" ? dir : newestVersion(join(dataHome, "mcpelauncher", "versions"));
  return { command, args, gameDir: gameDir.endsWith("/") ? gameDir : `${gameDir}/` };
}

/** The most recently changed version folder that holds a game (the launcher writes one folder per version). */
export function newestVersion(versions: string): string {
  let best: { dir: string; mtime: number } | undefined;
  let names: string[] = [];
  try {
    names = readdirSync(versions);
  } catch {
    names = [];
  }
  for (const name of names) {
    const dir = join(versions, name);
    if (!existsSync(join(dir, "AndroidManifest.xml"))) continue;
    const mtime = statSync(dir).mtimeMs;
    if (best === undefined || mtime > best.mtime) best = { dir, mtime };
  }
  if (best === undefined) {
    throw new CraftwireError("BEDROCK_GAME_NOT_DOWNLOADED", `No game version in ${versions}`,
      "Open the mcpelauncher launcher once, sign in to Google Play with the account that bought Minecraft and download "
        + "a version; or set CRAFTWIRE_BEDROCK_GAME_DIR to a version folder.");
  }
  return best.dir;
}
