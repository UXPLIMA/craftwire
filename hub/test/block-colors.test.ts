import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { COLOR_TABLE_VERSION, extractBlockColors } from "../src/blocks/colors.js";
import { encodePng } from "./helpers/png.js";
import { makeZip } from "./helpers/zip.js";

const solid = (r: number, g: number, b: number) => encodePng(2, 2, 6, Array(4).fill([r, g, b, 255]).flat());

describe("extractBlockColors", () => {
  it("averages the top and side textures a block's model names", () => {
    const jar = join(mkdtempSync(join(tmpdir(), "cw-col-")), "client.jar");
    makeZip(jar, {
      "assets/minecraft/blockstates/": "",
      "assets/minecraft/models/block/": "",
      "assets/minecraft/blockstates/stone.json": JSON.stringify({ variants: { "": { model: "minecraft:block/stone" } } }),
      "assets/minecraft/models/block/stone.json": JSON.stringify({ parent: "minecraft:block/cube_all", textures: { all: "minecraft:block/stone" } }),
      "assets/minecraft/models/block/cube_all.json": JSON.stringify({ parent: "block/cube", textures: { particle: "#all" } }),
      "assets/minecraft/blockstates/oak_log.json": JSON.stringify({ variants: { "axis=y": { model: "minecraft:block/oak_log" }, "axis=x": { model: "minecraft:block/oak_log_horizontal" } } }),
      "assets/minecraft/models/block/oak_log.json": JSON.stringify({ parent: "minecraft:block/cube_column", textures: { end: "minecraft:block/oak_log_top", side: "minecraft:block/oak_log" } }),
      "assets/minecraft/blockstates/glowstone.json": JSON.stringify({ variants: { "": { model: "minecraft:block/glowstone" } } }),
      "assets/minecraft/models/block/glowstone.json": JSON.stringify({ textures: { all: { sprite: "block/glowstone", force_translucent: true } } }),
      "assets/minecraft/textures/block/glowstone.png": solid(200, 160, 80),
      "assets/minecraft/textures/block/stone.png": solid(120, 120, 120),
      "assets/minecraft/textures/block/oak_log_top.png": solid(160, 130, 80),
      "assets/minecraft/textures/block/oak_log.png": solid(100, 80, 50),
    });
    const colors = extractBlockColors(jar);
    expect(colors["minecraft:stone"]).toEqual([0x787878, 0x787878]);
    expect(colors["minecraft:oak_log"]).toEqual([0xA08250, 0x645032]);
    expect(colors["minecraft:glowstone"]).toEqual([0xC8A050, 0xC8A050]);   // 26.x textures can be {sprite, ...}
  });

  it("tints the grey textures the game colours by biome: bushes like grass, leaf litter like dry foliage", () => {
    const jar = join(mkdtempSync(join(tmpdir(), "cw-col-")), "client.jar");
    const block = (id: string) => ({
      [`assets/minecraft/blockstates/${id}.json`]: JSON.stringify({ variants: { "": { model: `minecraft:block/${id}` } } }),
      [`assets/minecraft/models/block/${id}.json`]: JSON.stringify({ textures: { all: `minecraft:block/${id}` } }),
      [`assets/minecraft/textures/block/${id}.png`]: solid(200, 200, 200),
    });
    makeZip(jar, { ...block("bush"), ...block("leaf_litter") });
    const colors = extractBlockColors(jar);
    const rgb = (c: number) => [c >> 16 & 255, c >> 8 & 255, c & 255];
    const [br, bg, bb] = rgb(colors["minecraft:bush"]![0]);
    expect(bg).toBeGreaterThan(br);   // green, not grey
    expect(bg).toBeGreaterThan(bb);
    const [lr, lg, lb] = rgb(colors["minecraft:leaf_litter"]![0]);
    expect(lr).toBeGreaterThan(lg);   // brown, not grey
    expect(lg).toBeGreaterThan(lb);
  });
});

describe("iso views carry the colour table", () => {
  it("world_render iso sends the cached table for the server's version", async () => {
    const { mkdirSync, writeFileSync } = await import("node:fs");
    const { startHub, TOKEN } = await import("./helpers/hub.js");
    const { connectFakeAgent } = await import("./helpers/fakeAgent.js");
    const hub = await startHub();
    try {
      mkdirSync(join(hub.home, "block-colors"), { recursive: true });
      // An old table (before a fix to the extraction) is ignored: the cache file is named after the table's version.
      writeFileSync(join(hub.home, "block-colors", "26.2.json"), JSON.stringify({ "minecraft:stone": [9, 9] }));
      writeFileSync(join(hub.home, "block-colors", `26.2-v${COLOR_TABLE_VERSION}.json`), JSON.stringify({ "minecraft:stone": [1, 2] }));
      (hub.ctx as { home?: string }).home = hub.home;
      const agent = await connectFakeAgent(hub.port, { token: TOKEN, kind: "server" });
      let seen: Record<string, unknown> = {};
      agent.onRequest("world.render", (p) => { seen = p; return { mime: "image/png", data: "iVBORw0KGgo=" }; });
      await hub.call("world_render", { x1: 0, z1: 0, x2: 4, z2: 4, view: "iso4" });
      expect(seen.colors).toEqual({ "minecraft:stone": [1, 2] });
      await hub.call("world_render", { x1: 0, z1: 0, x2: 4, z2: 4, view: "top" });
      expect(seen.colors).toBeUndefined();
    } finally {
      await hub.close();
    }
  });
});
