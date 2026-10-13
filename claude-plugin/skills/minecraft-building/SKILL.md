---
name: minecraft-building
description: Use when building anything in Minecraft through Craftwire (houses, towers, castles, spawns, lobbies, statues, interiors, terrain features) — the build tool workflow, the script API card, palettes, proportion and depth rules, and how to review with cheap iso previews.
---

# Building with Craftwire

Build with **code in a file**, never block by block. `build` runs a JavaScript file on the server: you write the
file once, and every later fix is a small edit plus one call. Per-block `world_edit set_blocks` costs ~15-20 tokens
per block; a script costs the same for 50 or 50,000 blocks.

## The loop

1. **Pick the spot.** `world_render {view:"top", grid:10}` around the player (or `player_state`) to see the ground.
   Choose the origin: the build's north-west corner (or centre for round builds) at ground level.
2. **Write `builds/<name>.build.js`** with one `part()` per element: `base`, `walls`, `floors`, `windows`, `roof`,
   `details`, `inside`. Parts are independent layers: re-running one replaces only its own blocks.
3. **Run it once with a preview:** `build {project:"<name>", file:"builds/<name>.build.js", origin:{x,y,z}, preview:"iso4"}`.
   The image shows all four corners (nw, ne / sw, se). The JSON says what changed and lists warnings.
4. **Fix by editing the file** (only the lines that change), then `build {project, file, part:"roof", preview:"iso4"}`.
   Unchanged parts write nothing. Two or three rounds are normal.
5. **Done:** leave the project as is (undo still works) or `build_manage {action:"commit"}` when the user is happy.
   `build_manage undo` reverts the last run, `remove {part}` brings back what was under a part, `clear` restores
   the world, `move {origin?, rotation?}` moves or turns the whole build.

Before anything big, `dryRun:true` counts and previews without writing. For an existing build, read a small box with
`world_query {action:"region", encoding:"layers"}`: one letter per block, and `layers()` pastes it back exactly.

## Token rules

- One file per build, edited in place; never paste a script into `code` twice.
- `preview:"iso4"` at the default 1024 px is one image for four views; do not take four screenshots.
- `world_query` layers only on small boxes (a room, a facade), not whole builds.
- Use `screenshot` only for the final beauty check in a real client, if the user wants one.

## API card

Coordinates are relative to the origin: +x east, +y up, +z south. `rotate` turns clockwise seen from above.
A block is an id (`stone_bricks`), a state (`oak_stairs[facing=east,half=top]`), `mix({...})`, `gradient(...)`
or a function `(x,y,z) => block`. Boxes take corners in any order and are inclusive.

```
fill(x1,y1,z1, x2,y2,z2, b)            set(x,y,z, b)            clear(x1,y1,z1, x2,y2,z2)
hollow(x1,y1,z1, x2,y2,z2, b, {floor, ceiling})   // a room: shell of b, inside air
walls(...box, b)   outline(...box, b)   replace(...box, "oak_stairs", b)
sphere(cx,cy,cz, r, b, {hollow, half:"top"|"bottom"})   ellipsoid(cx,cy,cz, rx,ry,rz, b, {hollow})
cylinder(cx,y,cz, r, h, b, {hollow, axis:"y"|"x"|"z"})  cone(cx,y,cz, r, h, b, {hollow})
line(x1,y1,z1, x2,y2,z2, b, {width})   arch(x1,y,z1, x2,z2, height, b, {thickness})
polygon([[x,z],...], y, h, b, {hollow})
roof({x1,z1,x2,z2, y, type:"gable"|"hip"|"shed"|"flat", axis:"x"|"z", overhang, stairs:"spruce_stairs", slab?, fill?, facing?})
stairs(x,y,z, "north", length, "oak_stairs", {width})
layers(x,y,z, {s:"stone_bricks", g:"glass"}, ["ss/sg", "s./.s"])   // per y level; rows north->south; . air, ~ skip
mix({stone_bricks:6, cracked_stone_bricks:2, mossy_stone_bricks:1})   gradient("y", 0, 20, ["a","b","c"])
at(dx,dy,dz, fn)  rotate(quarterTurns, fn)  mirror("x"|"z", fn)  symmetric("x"|"z"|"xz", fn)  repeat(n, [dx,dy,dz], i => ...)
get(x,y,z)  ground(x,z)  isAir(x,y,z)  rand()  print(...)  part(name, () => {...})
```

Full reference and two complete examples: `reference.md` here, `examples/house.build.js`, `examples/tower.build.js`,
and `craftwire://docs/building`.

## Shape first, then depth, then detail

1. **Block out** the volumes with `fill`/`hollow`: main body, wings, towers. Check proportions in the preview before
   adding anything else. Most builds look better taller than you first think; roofs take a third of the height.
2. **Roof** with `roof()`: the ridge runs along the longer side; odd widths get a slab ridge, even widths meet in
   two stair rows. Overhang 1 almost always; close gable ends with `fill`.
3. **Depth.** No flat wall wider than ~5 blocks: put pillars (logs, a darker stone) every 4-6 blocks and set the
   wall between them back by 1 (inset), or push a beam/trim out by 1. Window frames: stairs or slabs above and
   below, trapdoors as shutters. A base course of a darker block along the bottom. Eaves: upside-down stairs under
   the roof edge.
4. **Detail** last: lanterns, flower boxes, fences, buttons as studs, banners. Small things on a big wall, not big
   things on a small one.

## Palettes

Mix 3-4 blocks per surface; one block alone reads as a texture-pack demo.

- Medieval stone: `mix({stone_bricks:6, cracked_stone_bricks:2, mossy_stone_bricks:1, andesite:1})`, trim `polished_andesite`, roof `dark_oak_stairs`.
- Timber frame: walls `mix({white_terracotta:5, white_concrete_powder:2, light_gray_terracotta:1})`, frame `stripped_spruce_log`, roof `spruce_stairs`.
- Nordic: `mix({spruce_planks:4, stripped_spruce_wood:1})`, base `mix({cobblestone:2, mossy_cobblestone:1})`, roof `dark_oak_stairs`.
- Desert: `mix({sandstone:4, smooth_sandstone:3, cut_sandstone:1})`, trim `cut_red_sandstone`, roof flat `smooth_sandstone_slab`.
- Modern: `white_concrete` with `light_gray_concrete` bands, `gray_stained_glass_pane`, flat roof, `smooth_quartz` trim.
- Fantasy: `mix({deepslate_bricks:3, polished_blackstone_bricks:2, cracked_deepslate_bricks:1})`, roof `mix({purpur_block:1, amethyst_block:1})` or `warped_planks`.

Use `gradient("y", ...)` for weathering: mossy and cracked blocks low, clean blocks high.

## Common mistakes

- **Stairs:** `facing` is the high side. A roof slope rising toward the south uses `facing=south`; upside-down stairs
  need `half=top`. `roof()` sets these for you; the server connects corners and fences after writing.
- **Directional blocks:** logs need `axis`, doors need both halves (`half=lower` and `half=upper`), beds both parts.
- **Support:** torches, lanterns, plants and carpets need a block under or behind them; `warnings` lists the ones
  that do not have it.
- **Overlapping parts:** the part run later covers the earlier one (warning `parts overlap`); keep each element in
  one part.
- **Symmetry:** perfect symmetry looks fine on castles and temples, artificial on houses and nature. Break it with a
  chimney, a side door, an extension.
- **Errors:** `BAD_BLOCK` names the unknown ids with suggestions; `SCRIPT_ERROR` names the line; nothing is written
  until the whole script succeeded.
