import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { CraftwireError } from "../errors.js";
import type { FetchOptions, FileSpec } from "./download.js";
import { PINS } from "./pins.js";

/** A library with its Maven coordinate, so Mojang's and Fabric's lists can be merged. */
export interface NamedFile {
  name: string;
  file: FileSpec;
}

/** Fabric's loader profile (meta.fabricmc.net …/profile/json): it extends the Mojang version. */
export interface FabricProfile {
  mainClass: string;
  arguments: { jvm?: string[]; game?: string[] };
  libraries: { name: string; url: string; sha1?: string; size?: number }[];
}

const META = "https://meta.fabricmc.net/v2/versions/loader";
const MAVEN = "https://maven.fabricmc.net/";

/** `group:artifact:version[:classifier]` → `group/path/artifact/version/artifact-version[-classifier].jar`. */
export function mavenPath(coord: string): string {
  const [group, artifact, version, classifier] = coord.split(":");
  if (!group || !artifact || !version) throw new CraftwireError("INVALID_PARAMS", `Not a Maven coordinate: ${coord}`);
  return `${group.replace(/\./g, "/")}/${artifact}/${version}/${artifact}-${version}${classifier ? `-${classifier}` : ""}.jar`;
}

/** The identity of a library regardless of its version: group:artifact[:classifier]. */
export function libraryKey(coord: string): string {
  const [group, artifact, , classifier] = coord.split(":");
  return [group, artifact, ...(classifier ? [classifier] : [])].join(":");
}

export function fabricLibraryFiles(p: FabricProfile, libDir: string): NamedFile[] {
  return p.libraries.map((lib) => {
    if (!lib.sha1) throw new CraftwireError("DOWNLOAD_FAILED", `Fabric's profile has no sha1 for ${lib.name}`, "Update craftwire.");
    const path = mavenPath(lib.name);
    const base = lib.url.endsWith("/") ? lib.url : `${lib.url}/`;
    return {
      name: lib.name,
      file: { url: `${base}${path}`, sha1: lib.sha1, ...(lib.size !== undefined ? { size: lib.size } : {}), dest: join(libDir, ...path.split("/")) },
    };
  });
}

/** Mojang's libraries plus Fabric's; where both ship one (e.g. ASM), Fabric's version wins, as in every Fabric launcher. */
export function mergeLibraries(mojang: NamedFile[], fabric: NamedFile[]): FileSpec[] {
  const fabricKeys = new Set(fabric.map((l) => libraryKey(l.name)));
  return [...mojang.filter((l) => !fabricKeys.has(libraryKey(l.name))), ...fabric].map((l) => l.file);
}

/** The loader profile from meta.fabricmc.net, kept in `cacheDir` so later starts also work offline. */
export async function fetchFabricProfile(minecraft: string, loader: string, cacheDir: string, o: FetchOptions = {}): Promise<FabricProfile> {
  const url = `${META}/${encodeURIComponent(minecraft)}/${encodeURIComponent(loader)}/profile/json`;
  const cached = join(cacheDir, `fabric-loader-${loader}-${minecraft}.json`);
  try {
    const res = await (o.fetchImpl ?? fetch)(url);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const text = await res.text();
    const profile = JSON.parse(text) as FabricProfile;
    mkdirSync(cacheDir, { recursive: true });
    writeFileSync(cached, text);
    return profile;
  } catch (e) {
    if (existsSync(cached)) return JSON.parse(readFileSync(cached, "utf8")) as FabricProfile;
    throw new CraftwireError("DOWNLOAD_FAILED", `${url}: ${(e as Error).message}`, "Check the network connection, then retry.");
  }
}

/** Fabric API, which the agent needs, from Fabric's own Maven at the pinned version and sha1. */
export function fabricApiFile(modsDir: string): FileSpec {
  const v = encodeURIComponent(PINS.fabricApi);
  return {
    url: `${MAVEN}net/fabricmc/fabric-api/fabric-api/${v}/fabric-api-${v}.jar`,
    sha1: PINS.fabricApiSha1,
    dest: join(modsDir, `fabric-api-${PINS.fabricApi}.jar`),
  };
}
