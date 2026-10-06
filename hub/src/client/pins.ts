import { existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { CraftwireError } from "../errors.js";
import { HUB_VERSION } from "../version.js";

/** The client client_process runs: the versions the agent mod is built for (gradle.properties; a test keeps them equal). */
export const PINS = {
  minecraft: "26.2",
  loader: "0.19.5",
  fabricApi: "0.161.0+26.2",
  /** sha1 published next to the jar on maven.fabricmc.net. */
  fabricApiSha1: "332da34ebb72171e603a0c538de17f4c54ea9e29",
} as const;

/** The agent mod matching this hub: packed into the npm package by prepack, or built in a repo checkout. */
export function agentJar(): string {
  const here = dirname(fileURLToPath(import.meta.url));
  const name = `craftwire-agent-fabric-${HUB_VERSION}.jar`;
  // dist/client/ (or src/client/ under vitest) → package root / repo root.
  for (const dir of [join(here, "..", "..", "agent"), join(here, "..", "..", "..", "agent-fabric", "build", "libs")]) {
    if (existsSync(join(dir, name))) return join(dir, name);
  }
  throw new CraftwireError("AGENT_JAR_MISSING", `${name} is not next to the hub`,
    "Reinstall craftwire, or in a repo checkout run ./gradlew :agent-fabric:build.");
}
