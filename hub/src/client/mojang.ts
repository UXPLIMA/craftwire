import { readFileSync } from "node:fs";
import { join } from "node:path";
import { CraftwireError } from "../errors.js";
import { type FetchOptions, fetchVerified, type FileSpec } from "./download.js";

/** The OS as Mojang's rules name it. */
export interface Os {
  name: "windows" | "osx" | "linux";
  arch: string;
}

export interface Rule {
  action: "allow" | "disallow";
  os?: { name?: string; arch?: string };
  features?: Record<string, boolean>;
}

export type Argument = string | { rules: Rule[]; value: string | string[] };

interface Download {
  url: string;
  sha1: string;
  size?: number;
}

export interface Library {
  name: string;
  downloads?: { artifact?: Download & { path: string } };
  rules?: Rule[];
}

/** The parts of a Mojang version JSON (version_manifest_v2 → <id>.json) the launcher uses. */
export interface VersionJson {
  id: string;
  type: string;
  mainClass: string;
  javaVersion: { majorVersion: number };
  assetIndex: Download & { id: string };
  downloads: { client: Download };
  libraries: Library[];
  arguments: { game: Argument[]; jvm: Argument[] };
  logging?: { client?: { argument: string; file: Download & { id: string } } };
}

export interface AssetIndex {
  objects: Record<string, { hash: string; size: number }>;
}

export const MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
const RESOURCES = "https://resources.download.minecraft.net";

export function currentOs(platform: NodeJS.Platform = process.platform, arch: string = process.arch): Os {
  const name = platform === "win32" ? "windows" : platform === "darwin" ? "osx" : "linux";
  return { name, arch: arch === "ia32" ? "x86" : arch };
}

function matches(r: Rule, os: Os, features: Record<string, boolean>): boolean {
  if (r.os?.name !== undefined && r.os.name !== os.name) return false;
  if (r.os?.arch !== undefined && !new RegExp(`^(?:${r.os.arch})$`).test(os.arch)) return false;
  for (const [k, want] of Object.entries(r.features ?? {})) if ((features[k] ?? false) !== want) return false;
  return true;
}

/** Mojang's rule semantics: no rules → allowed; otherwise start disallowed and let every matching rule decide in turn. */
export function allowed(rules: Rule[] | undefined, os: Os, features: Record<string, boolean>): boolean {
  if (!rules || rules.length === 0) return true;
  let ok = false;
  for (const r of rules) if (matches(r, os, features)) ok = r.action === "allow";
  return ok;
}

export function argumentList(entries: Argument[], os: Os, features: Record<string, boolean>): string[] {
  const out: string[] = [];
  for (const e of entries) {
    if (typeof e === "string") out.push(e);
    else if (allowed(e.rules, os, features)) out.push(...(Array.isArray(e.value) ? e.value : [e.value]));
  }
  return out;
}

/** Libraries allowed on `os`. Native libraries are plain jars here; LWJGL extracts what it needs itself. */
export function libraryFiles(v: VersionJson, os: Os, libDir: string): FileSpec[] {
  const out: FileSpec[] = [];
  for (const lib of v.libraries) {
    const a = lib.downloads?.artifact;
    if (!a || !allowed(lib.rules, os, {})) continue;
    out.push({ url: a.url, sha1: a.sha1, ...(a.size !== undefined ? { size: a.size } : {}), dest: join(libDir, ...a.path.split("/")) });
  }
  return out;
}

/** Asset objects by hash. Sounds (.ogg, most of the ~460 MB) are skipped unless asked; the game runs without them. */
export function assetFiles(index: AssetIndex, objectsDir: string, sounds: boolean): FileSpec[] {
  const seen = new Set<string>();
  const out: FileSpec[] = [];
  for (const [key, o] of Object.entries(index.objects)) {
    if (!sounds && key.endsWith(".ogg")) continue;
    if (seen.has(o.hash)) continue;
    seen.add(o.hash);
    const prefix = o.hash.slice(0, 2);
    out.push({ url: `${RESOURCES}/${prefix}/${o.hash}`, sha1: o.hash, size: o.size, dest: join(objectsDir, prefix, o.hash) });
  }
  return out;
}

/** The version JSON of `minecraft`, through the manifest (always fetched) and a sha1-checked local copy. */
export async function fetchVersion(minecraft: string, versionsDir: string, o: FetchOptions = {}): Promise<VersionJson> {
  const res = await (o.fetchImpl ?? fetch)(MANIFEST_URL).catch((e: Error) => {
    throw new CraftwireError("DOWNLOAD_FAILED", `${MANIFEST_URL}: ${e.message}`, "Check the network connection, then retry.");
  });
  if (!res.ok) throw new CraftwireError("DOWNLOAD_FAILED", `${MANIFEST_URL}: HTTP ${res.status}`, "Retry in a moment.");
  const manifest = (await res.json()) as { versions: { id: string; url: string; sha1: string }[] };
  const entry = manifest.versions.find((x) => x.id === minecraft);
  if (!entry) throw new CraftwireError("UNKNOWN_VERSION", `Mojang's manifest has no Minecraft ${minecraft}`, "Update craftwire.");
  const dest = join(versionsDir, minecraft, `${minecraft}.json`);
  await fetchVerified({ url: entry.url, sha1: entry.sha1, dest }, o);
  return JSON.parse(readFileSync(dest, "utf8")) as VersionJson;
}

/** The asset index named by the version, sha1-checked, from `assets/indexes/<id>.json`. */
export async function fetchAssetIndex(v: VersionJson, assetsDir: string, o: FetchOptions = {}): Promise<AssetIndex> {
  const dest = join(assetsDir, "indexes", `${v.assetIndex.id}.json`);
  await fetchVerified({ url: v.assetIndex.url, sha1: v.assetIndex.sha1, dest }, o);
  return JSON.parse(readFileSync(dest, "utf8")) as AssetIndex;
}
