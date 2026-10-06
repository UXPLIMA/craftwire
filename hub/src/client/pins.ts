import { existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { CraftwireError } from "../errors.js";
import { HUB_VERSION } from "../version.js";

/** Fabric Loader for every client client_process runs (gradle.properties loader_version; a test keeps them equal). */
export const LOADER = "0.19.5";

/**
 * The Minecraft versions client_process can run, oldest first: the versions the agent mod supports
 * (gradle.properties mc_versions, versions/<mc>.properties; a test keeps them equal).
 * fabricApiSha1 is the sha1 published next to the jar on maven.fabricmc.net.
 */
export const CLIENT_VERSIONS = {
  "26.2": { fabricApi: "0.161.0+26.2", fabricApiSha1: "332da34ebb72171e603a0c538de17f4c54ea9e29" },
  "26.3": { fabricApi: "0.162.0+26.3", fabricApiSha1: "273cd2dcbd92d1559edcc91c9f23fee47f6ff93f" },
} as const;

export type McVersion = keyof typeof CLIENT_VERSIONS;
export const SUPPORTED_VERSIONS = Object.keys(CLIENT_VERSIONS) as McVersion[];
export const NEWEST_VERSION: McVersion = SUPPORTED_VERSIONS[SUPPORTED_VERSIONS.length - 1]!;

export interface ClientPins {
  minecraft: McVersion;
  loader: string;
  fabricApi: string;
  fabricApiSha1: string;
}

export function pinsFor(minecraft: McVersion): ClientPins {
  return { minecraft, loader: LOADER, ...CLIENT_VERSIONS[minecraft] };
}

/** The supported version a game version string belongs to ("26.3.1" → 26.3, "Paper 26.2" → 26.2), if any. */
export function supportedVersion(name: string | undefined): McVersion | undefined {
  const m = name === undefined ? null : /(\d+)\.(\d+)(?:\.\d+)?/.exec(name);
  if (m === null) return undefined;
  const minor = `${m[1]}.${m[2]}`;
  return SUPPORTED_VERSIONS.find((v) => v === minor);
}

/**
 * dist/client/ (or src/client/ under vitest) → a repo checkout's build output first (a fresh build must win over a
 * jar left in agent/ by an earlier npm pack), then the package's agent/ folder (an installed package has only that).
 */
function agentJarDirs(): string[] {
  const here = dirname(fileURLToPath(import.meta.url));
  return [join(here, "..", "..", "..", "agent-fabric", "build", "libs"), join(here, "..", "..", "agent")];
}

/** The agent mod matching this hub (one jar for every supported version): packed by prepack, or built in a checkout. */
export function agentJar(dirs: string[] = agentJarDirs()): string {
  const name = `craftwire-agent-fabric-${HUB_VERSION}.jar`;
  for (const dir of dirs) {
    if (existsSync(join(dir, name))) return join(dir, name);
  }
  throw new CraftwireError("AGENT_JAR_MISSING", `${name} is not next to the hub`,
    "Reinstall craftwire, or in a repo checkout run ./gradlew :agent-fabric:build.");
}
