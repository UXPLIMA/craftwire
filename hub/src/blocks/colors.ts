import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { readZipEntries } from "../dev/jar.js";
import { fetchVerified } from "../client/download.js";
import { fetchVersion } from "../client/mojang.js";
import { decodePng } from "./png.js";

type Json = Record<string, unknown>;
const BIOME_TINT: Record<string, number> = {
  grass_block: 0x91bd59, short_grass: 0x91bd59, tall_grass: 0x91bd59, fern: 0x91bd59, large_fern: 0x91bd59, sugar_cane: 0x91bd59,
  oak_leaves: 0x77ab2f, jungle_leaves: 0x77ab2f, acacia_leaves: 0x77ab2f, dark_oak_leaves: 0x77ab2f, mangrove_leaves: 0x77ab2f, vine: 0x77ab2f,
  birch_leaves: 0x80a755, spruce_leaves: 0x619961, lily_pad: 0x208030, water: 0x3f76e4,
};

const tex = (ref: string) => `assets/minecraft/textures/${ref.replace("minecraft:", "")}.png`;
const model = (ref: string) => `assets/minecraft/models/${ref.replace("minecraft:", "")}.json`;

/** Average top and side colour of every block, from its blockstate's first model. Transparent textures give 0. */
export function extractBlockColors(jar: string): Record<string, [number, number]> {
  // Directory entries (names ending in /) are skipped: only .json and .png files.
  const files = readZipEntries(jar, (n) => /^assets\/minecraft\/(blockstates\/|models\/block\/|textures\/block\/).+\.(json|png)$/.test(n));
  const json = (name: string): Json | undefined => { const b = files.get(name); return b ? JSON.parse(b.toString("utf8")) as Json : undefined; };
  const averages = new Map<string, number>();
  const average = (ref: string, tint?: number): number => {
    const key = `${ref}|${tint ?? ""}`;
    const cached = averages.get(key);
    if (cached !== undefined) return cached;
    const png = files.get(tex(ref));
    let out = 0;
    let img: ReturnType<typeof decodePng> | undefined;
    try {
      img = png ? decodePng(png) : undefined;
    } catch {
      img = undefined;   // one texture we cannot read leaves its block to the map colour, not the whole table
    }
    if (img) {
      const h = Math.min(img.height, img.width);   // animated textures: first frame
      let r = 0, g = 0, b = 0, a = 0;
      for (let i = 0; i < img.width * h; i++) {
        const w = img.rgba[i * 4 + 3]!;
        if (w < 16) continue;
        r += img.rgba[i * 4]! * w; g += img.rgba[i * 4 + 1]! * w; b += img.rgba[i * 4 + 2]! * w; a += w;
      }
      if (a > img.width * h * 255 * 0.4) {
        r /= a; g /= a; b /= a;
        if (tint !== undefined) { r = r * (tint >> 16 & 255) / 255; g = g * (tint >> 8 & 255) / 255; b = b * (tint & 255) / 255; }
        out = (Math.round(r) << 16) | (Math.round(g) << 8) | Math.round(b);
      }
    }
    averages.set(key, out);
    return out;
  };
  const textures = (ref: string, seen = 0): Record<string, string> => {
    const m = json(model(ref.includes("/") ? ref : `block/${ref}`));
    if (!m || seen > 20) return {};
    // A texture is "minecraft:block/x", "#ref", or (26.x) {sprite: "block/x", force_translucent?}.
    const own: Record<string, string> = {};
    for (const [k, v] of Object.entries((m.textures ?? {}) as Record<string, unknown>)) {
      if (typeof v === "string") own[k] = v;
      else if (v !== null && typeof v === "object" && typeof (v as { sprite?: unknown }).sprite === "string") own[k] = (v as { sprite: string }).sprite;
    }
    return { ...(typeof m.parent === "string" ? textures(m.parent, seen + 1) : {}), ...own };
  };
  const resolve = (t: Record<string, string>, k: string | undefined): string | undefined => {
    let v = k === undefined ? undefined : t[k];
    for (let i = 0; v?.startsWith("#") && i < 10; i++) v = t[v.slice(1)];
    return v?.startsWith("#") ? undefined : v;
  };
  const out: Record<string, [number, number]> = {};
  for (const [name, buf] of files) {
    if (!name.startsWith("assets/minecraft/blockstates/")) continue;
    const id = name.slice("assets/minecraft/blockstates/".length, -".json".length);
    const bs = JSON.parse(buf.toString("utf8")) as Json;
    let first: unknown;
    if (bs.variants) first = Object.values(bs.variants as Json)[0];
    else if (Array.isArray(bs.multipart)) first = (bs.multipart[0] as Json).apply;
    if (Array.isArray(first)) first = first[0];
    const ref = (first as Json | undefined)?.model as string | undefined;
    if (!ref) continue;
    const t = textures(ref);
    const pick = (...keys: string[]) => keys.map((k) => resolve(t, k)).find((v) => v !== undefined);
    const top = pick("top", "end", "up", "all", "particle");
    const side = pick("side", "all", "north", "front", "particle", "top");
    if (!top && !side) continue;
    const tint = BIOME_TINT[id];
    out[`minecraft:${id}`] = [average(top ?? side!, tint), average(side ?? top!, id === "grass_block" ? undefined : tint)];
  }
  return out;
}

const memo = new Map<string, Promise<Record<string, [number, number]> | undefined>>();

/** The colour table for a Minecraft version: cached as JSON, built from the client jar the hub already has or downloads. */
export function blockColorsFor(home: string, mc: string, log: (m: string) => void = () => {}): Promise<Record<string, [number, number]> | undefined> {
  const key = `${home}|${mc}`;
  let p = memo.get(key);
  if (!p) {
    p = (async () => {
      const dir = join(home, "block-colors");
      const cache = join(dir, `${mc}.json`);
      if (existsSync(cache)) return JSON.parse(readFileSync(cache, "utf8")) as Record<string, [number, number]>;
      try {
        let jar = join(home, "client", "versions", mc, `${mc}.jar`);
        if (!existsSync(jar)) {
          mkdirSync(dir, { recursive: true });
          const v = await fetchVersion(mc, join(dir, "versions"));
          jar = join(dir, `${mc}.jar`);
          await fetchVerified({ ...v.downloads.client, dest: jar });
        }
        const table = extractBlockColors(jar);
        mkdirSync(dir, { recursive: true });
        writeFileSync(cache, JSON.stringify(table));
        return table;
      } catch (e) {
        log(`block colours for ${mc} unavailable (${e instanceof Error ? e.message : String(e)}); iso views use map colours`);
        memo.delete(key);
        return undefined;
      }
    })();
    memo.set(key, p);
  }
  return p;
}
