/**
 * Mod profiles: named sets of mods for client_process (e.g. "shaders": Sodium and Iris), kept in
 * <client root>/mod-profiles.json. An entry is an absolute jar path or `modrinth:<slug>`; Modrinth entries resolve to
 * the newest Fabric build for the client's Minecraft version and are downloaded once, checked by sha1.
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { basename, isAbsolute, join } from "node:path";
import { CraftwireError } from "../errors.js";
import { fetchVerified, type FetchOptions } from "./download.js";
import { checkMods } from "./instance.js";

const MODRINTH = "https://api.modrinth.com/v2";
const USER_AGENT = "craftwire (https://github.com/uxplima/craftwire)";
const PROFILE_NAME = /^[A-Za-z0-9_-]{1,32}$/;
const MODRINTH_ENTRY = /^modrinth:([A-Za-z0-9_-]{2,64})$/;

interface ModrinthFile {
  url: string;
  filename: string;
  primary?: boolean;
  size?: number;
  hashes: { sha1: string };
}

export class ModProfiles {
  private readonly file: string;

  constructor(private readonly root: string, private readonly o: FetchOptions = {}) {
    this.file = join(root, "mod-profiles.json");
  }

  list(): Record<string, string[]> {
    if (!existsSync(this.file)) return {};
    try {
      return JSON.parse(readFileSync(this.file, "utf8")) as Record<string, string[]>;
    } catch {
      return {};
    }
  }

  /** Saves a profile; an empty list deletes it. Paths are checked now, Modrinth slugs when a client starts. */
  save(name: string, mods: string[]): Record<string, string[]> {
    if (!PROFILE_NAME.test(name)) throw new CraftwireError("INVALID_PARAMS", `"${name}" is not a profile name`, "Use 1-32 letters, digits, - or _.");
    for (const m of mods) {
      if (!MODRINTH_ENTRY.test(m) && !isAbsolute(m)) {
        throw new CraftwireError("INVALID_PARAMS", `"${m}" is neither an absolute jar path nor modrinth:<slug>`, "e.g. modrinth:sodium.");
      }
    }
    checkMods(mods.filter((m) => !MODRINTH_ENTRY.test(m)));
    const all = this.list();
    if (mods.length === 0) delete all[name];
    else all[name] = mods;
    mkdirSync(this.root, { recursive: true });
    writeFileSync(this.file, JSON.stringify(all, null, 2));
    return all;
  }

  /** The jar paths of a profile for one Minecraft version, downloading Modrinth entries that are not cached yet. */
  async resolve(name: string, minecraft: string): Promise<string[]> {
    const mods = this.list()[name];
    if (mods === undefined) {
      const known = Object.keys(this.list());
      throw new CraftwireError("MOD_PROFILE_NOT_FOUND", `No mod profile named ${name}`,
        known.length > 0 ? `Profiles: ${known.join(", ")}.` : "Create one: client_process {action:'mod_profile', name, mods}.");
    }
    const out: string[] = [];
    for (const m of mods) {
      const slug = MODRINTH_ENTRY.exec(m)?.[1];
      out.push(slug === undefined ? m : await this.modrinth(slug, minecraft));
    }
    return out;
  }

  private async modrinth(slug: string, minecraft: string): Promise<string> {
    const q = `loaders=${encodeURIComponent(JSON.stringify(["fabric"]))}&game_versions=${encodeURIComponent(JSON.stringify([minecraft]))}`;
    const res = await (this.o.fetchImpl ?? fetch)(`${MODRINTH}/project/${slug}/version?${q}`, { headers: { "User-Agent": USER_AGENT } });
    if (res.status === 404) throw new CraftwireError("MOD_NOT_FOUND", `Modrinth has no project ${slug}`, "Check the slug in the project's URL.");
    const versions = (await res.json().catch(() => [])) as { files?: ModrinthFile[] }[];
    const files = versions[0]?.files ?? [];
    const file = files.find((f) => f.primary) ?? files[0];
    if (file === undefined) {
      throw new CraftwireError("MOD_NOT_FOUND", `${slug} has no Fabric build for Minecraft ${minecraft}`,
        "Pick another version, or leave the mod out of the profile.");
    }
    const dest = join(this.root, "mods-cache", minecraft, basename(file.filename));
    await fetchVerified({ url: file.url, sha1: file.hashes.sha1, dest, ...(file.size !== undefined ? { size: file.size } : {}) }, this.o);
    return dest;
  }
}
