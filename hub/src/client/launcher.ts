import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { delimiter, join, posix, win32 } from "node:path";
import { CraftwireError } from "../errors.js";
import { probeJavaMajor } from "../dev/launch.js";
import { HUB_VERSION } from "../version.js";
import type { PrepareInput } from "./client-manager.js";
import { fetchAll, type FetchOptions, type FileSpec } from "./download.js";
import { fabricApiFile, fabricLibraryFiles, fetchFabricProfile, mergeLibraries } from "./fabric.js";
import { checkMods, prepareInstance } from "./instance.js";
import { buildLaunch } from "./launch-args.js";
import { assetFiles, currentOs, fetchAssetIndex, fetchVersion, namedLibraryFiles } from "./mojang.js";
import { agentJar, PINS } from "./pins.js";

type Command = { command: string; args: string[] };

/** JAVA_HOME's java when it exists, else `java` on PATH. */
export function defaultJava(env: NodeJS.ProcessEnv = process.env, platform: NodeJS.Platform = process.platform, exists: (p: string) => boolean = existsSync): string {
  if (env.JAVA_HOME) {
    const java = platform === "win32" ? win32.join(env.JAVA_HOME, "bin", "java.exe") : posix.join(env.JAVA_HOME, "bin", "java");
    if (exists(java)) return java;
  }
  return "java";
}

/** On a Linux machine without a display the client runs under xvfb-run; elsewhere it runs as is. */
export function withDisplay(cmd: Command, platform: NodeJS.Platform = process.platform, env: NodeJS.ProcessEnv = process.env,
  hasXvfb: () => boolean = () => spawnSync("xvfb-run", ["--help"], { stdio: "ignore" }).error === undefined): Command {
  if (platform !== "linux" || env.DISPLAY || env.WAYLAND_DISPLAY) return cmd;
  if (hasXvfb()) return { command: "xvfb-run", args: ["-a", cmd.command, ...cmd.args] };
  throw new CraftwireError("NO_DISPLAY", "This Linux machine has no display for the game window",
    "Install Xvfb (e.g. apt install xvfb); client_process then runs the client under xvfb-run.");
}

/**
 * Everything a client_process start needs, downloaded and checked: Minecraft (version JSON, client jar, libraries,
 * assets without sounds unless asked, log config), Fabric Loader and Fabric API, plus the instance folder.
 */
export async function prepareClient(p: PrepareInput, o: FetchOptions = {}): Promise<{ command: string; args: string[]; gameDir: string; downloadedBytes: number }> {
  checkMods(p.mods);
  const agent = agentJar();
  const os = currentOs();
  const dirs = {
    versions: join(p.root, "versions"),
    libraries: join(p.root, "libraries"),
    assets: join(p.root, "assets"),
    natives: join(p.root, "natives", PINS.minecraft),
    fabricApi: join(p.root, "mods"),
  };

  const version = await fetchVersion(PINS.minecraft, dirs.versions, o);
  const java = p.java ?? defaultJava();
  const major = await probeJavaMajor(java);
  const need = version.javaVersion.majorVersion;
  if (major < need) throw new CraftwireError("JAVA_TOO_OLD", `${java} is Java ${major}; Minecraft ${PINS.minecraft} needs Java ${need}+`, `Pass java: the path to a Java ${need}+ executable.`);

  const fabric = await fetchFabricProfile(PINS.minecraft, PINS.loader, dirs.versions, o);
  const libraries = mergeLibraries(namedLibraryFiles(version, os, dirs.libraries), fabricLibraryFiles(fabric, dirs.libraries));
  const clientJar: FileSpec = { ...version.downloads.client, dest: join(dirs.versions, version.id, `${version.id}.jar`) };
  const log = version.logging?.client?.file;
  const logFile: FileSpec | undefined = log ? { url: log.url, sha1: log.sha1, dest: join(dirs.assets, "log_configs", log.id) } : undefined;
  const fabricApi = fabricApiFile(dirs.fabricApi);
  const index = await fetchAssetIndex(version, dirs.assets, o);
  const files = [clientJar, ...libraries, fabricApi, ...(logFile ? [logFile] : []), ...assetFiles(index, join(dirs.assets, "objects"), p.sounds)];
  const downloadedBytes = await fetchAll(files, { ...o, onProgress: p.onProgress });

  const gameDir = prepareInstance(p.root, { username: p.username, agentJar: agent, fabricApiJar: fabricApi.dest, extraMods: p.mods });
  const cmd = buildLaunch({
    version, fabric, os, java,
    classpath: [...libraries.map((l) => l.dest), clientJar.dest], pathSep: delimiter,
    gameDir, assetsDir: dirs.assets, nativesDir: dirs.natives, libraryDir: dirs.libraries,
    ...(logFile ? { logConfig: logFile.dest } : {}),
    username: p.username, ...(p.server !== undefined ? { server: p.server } : {}),
    width: p.width, height: p.height, hidden: !p.visible, launcherVersion: HUB_VERSION,
  });
  return { ...withDisplay(cmd), gameDir, downloadedBytes };
}
