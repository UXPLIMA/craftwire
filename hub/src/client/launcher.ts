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

/** Where to look for Java when none is given: JAVA_HOME's, then `java` on PATH. */
export function javaCandidates(env: NodeJS.ProcessEnv = process.env, platform: NodeJS.Platform = process.platform, exists: (p: string) => boolean = existsSync): string[] {
  return [...new Set([defaultJava(env, platform, exists), "java"])];
}

/** The first candidate whose major version is at least `need` (JAVA_HOME is often an older JDK than PATH's). */
export async function pickJava(candidates: string[], probe: (java: string) => Promise<number>, need: number): Promise<string> {
  const found: string[] = [];
  for (const java of candidates) {
    const major = await probe(java).catch(() => undefined);
    if (major !== undefined && major >= need) return java;
    found.push(`${java} (${major === undefined ? "not runnable" : `Java ${major}`})`);
  }
  throw new CraftwireError("JAVA_TOO_OLD", `Minecraft ${PINS.minecraft} needs Java ${need}+; found ${found.join(", ")}`,
    `Install Java ${need}+, or pass java: the path to one.`);
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
  const java = await pickJava(p.java !== undefined ? [p.java] : javaCandidates(), probeJavaMajor, version.javaVersion.majorVersion);

  const fabric = await fetchFabricProfile(PINS.minecraft, PINS.loader, dirs.versions, o);
  const libraries = mergeLibraries(namedLibraryFiles(version, os, dirs.libraries), await fabricLibraryFiles(fabric, dirs.libraries, o));
  const clientJar: FileSpec = { ...version.downloads.client, dest: join(dirs.versions, version.id, `${version.id}.jar`) };
  // Mojang's logging config (version.logging) prints log4j XML for launcher log viewers; without it the game logs
  // plain lines, which is what status logTail and crash diagnosis read.
  const fabricApi = fabricApiFile(dirs.fabricApi);
  const index = await fetchAssetIndex(version, dirs.assets, o);
  const files = [clientJar, ...libraries, fabricApi, ...assetFiles(index, join(dirs.assets, "objects"), p.sounds)];
  const downloadedBytes = await fetchAll(files, { ...o, onProgress: p.onProgress });

  const gameDir = prepareInstance(p.root, { username: p.username, agentJar: agent, fabricApiJar: fabricApi.dest, extraMods: p.mods });
  const cmd = buildLaunch({
    version, fabric, os, java,
    classpath: [...libraries.map((l) => l.dest), clientJar.dest], pathSep: delimiter,
    gameDir, assetsDir: dirs.assets, nativesDir: dirs.natives, libraryDir: dirs.libraries,
    username: p.username, ...(p.server !== undefined ? { server: p.server } : {}),
    width: p.width, height: p.height, hidden: !p.visible, launcherVersion: HUB_VERSION,
  });
  return { ...withDisplay(cmd), gameDir, downloadedBytes };
}
