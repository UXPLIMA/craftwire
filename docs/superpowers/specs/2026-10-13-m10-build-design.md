# M10 — Build tools and token diet

Status: requested by the user on 2026-10-13 after a complaint that building through Craftwire is hard. The user
asked for strong token optimisation, faster use and better building; the design below was approved in chat
("kabul ediyorum"). Decisions are delegated to me. Nothing is released or published without the user's npm step.

Follow-up M11 (separate spec): the same build engine in singleplayer (Fabric integrated server) and schematic files
(.schem / .litematic / .nbt import and export). M10 keeps the engine platform-neutral so M11 only adds adapters.

## Why: measured on main (0.9.0)

| cost | measured | consequence |
|---|---|---|
| tool definitions | 33 tools, 55,046 chars (~15.7k tokens); `bot_action` alone 5,139 | paid every session by clients that load all tools (Cursor, Codex, Gemini) |
| tool results | every result is `JSON.stringify(data, null, 2)`; a `world_query` region with 3,000 runs is 92,951 chars pretty vs 20,908 compact (4.4x) | reading builds is needlessly expensive |
| placing blocks | `world_edit set_blocks` takes `{x,y,z,block}` per block, ~15-20 tokens each, max 10,000 per call | a 50k-block build costs ~1M tokens of output |
| code builds | `server_eval` runs on the server thread, 5 s default / 60 s max, raw Bukkit API, no shapes, no undo; the skill says "keep loops small" | Claude avoids code and falls back to per-block edits |
| looking at a build | `screenshot` default 1600 px (~1.6k tokens), one angle per call, needs a client; `world_render` is flat top/slice/side only | review loops are slow and costly |
| knowledge | no building guidance in any skill | flat walls, one-block palettes |

## Goals and acceptance

1. The tower task (below) costs at least 10x fewer tokens than the per-block baseline and takes at most 12 tool
   calls including two review-and-fix rounds.
2. Full tool list (all groups) at most 28,000 chars (~8k tokens); the `build` profile at most 10,500 chars
   (~3k tokens); no single tool definition over 1,500 chars. Enforced by a hub test.
3. 1,000,000 changed blocks apply in at most 10 s on the user's machine while the server stays at TPS >= 18.
4. Re-running an unchanged part writes 0 blocks; undo restores the world exactly (verified block by block in ITs).

Tower task: on a flat area, a medieval tower with a 25x25 footprint, 40 high, three floors with stairs, windows, a
hip roof with overhang, a varied stone palette. Baseline = the finished tower's changed-block count x the measured
chars of one `set_blocks` entry plus the calls that needs (10,000 per call); measured, not run the old way.
New way = run for real through the hub; chars of every tool call argument and result are counted by the driver.
Result goes to `docs/acceptance/m10-build.md` with the preview images; I also judge the look.

## 1. Token diet (whole hub)

- `ok()` returns compact JSON (no indentation). Images unchanged.
- Tool descriptions rewritten to a budget: what the tool does, the arguments that are not obvious from the schema,
  one pointer to a doc resource for the rest. Long reference text moves to `craftwire://docs/<topic>` resources
  (existing mechanism, `DOC_TOPICS`), new topics `building`, `bots`, `video`, `debug`. Schema `.describe()` texts are
  counted in the budget too.
- `--tools <groups>` works for the stdio hub too (today only `craftwire serve`), so `npx craftwire --tools build`
  can go in any client's MCP config. New group `build` = `build`, `build_manage`; preset `--profile build` =
  hub + build + `world_query` + `world_render` + `server_command` + `screenshot` + `camera`. `craftwire setup <client>`
  gains `--profile`.
- `screenshot` default `maxSize` 1600 -> 1024.
- `world_query region` gains `encoding:'layers'`: `{legend:{"a":"stone_bricks",...}, origin, size, layers:["..."]}`,
  one string per y level, rows north to south separated by `/`, `.` = air, `?` = not loaded. The same format is the
  input of `layers()` in build scripts, so reading and writing a build use one notation. Up to 65,536 blocks.
- `tools/measure-tools.mjs` prints the per-tool size table (the measurement above); `hub/test/tool-budget.test.ts`
  enforces goal 2.

## 2. Build engine

### Modules

| module | contents | depends on |
|---|---|---|
| `build-core` (new, plain Java) | script API objects, shapes, block sources (mix, gradient, function), transforms, `layers` parser, `EditBuffer` (packed long position -> int state id), part layer stack, journal format, diff | nothing from Minecraft; JUnit-tested |
| `mc-shared` (existing, Mojang-mapped) | `BuildStates`: parse a state string to `BlockState`, rotate/mirror via `BlockState.rotate/mirror`, closest-id suggestions | Minecraft |
| `agent-paper` | `BuildWorld` Paper adapter: chunk reads, fast writes, region scheduling, journal storage, `build.*` RPC handlers | the two above + NMS |

`build-core` talks to the world only through a `BuildWorld` interface (`read(x,y,z)`, `ground(x,z)`,
`apply(chunkX, chunkZ, changes)`, `stateId(String)`, `stateName(int)`, `rotate(int, quarterTurns)`, `mirror(int, axis)`),
so M11's Fabric adapter is the only new piece for singleplayer.

### Running a part

1. Compute (worker thread, not the server thread): a GraalJS context per project runs the part's code with the
   build API (section 3) and fills an `EditBuffer`. No 5 s limit: default 60 s, max 300 s (`timeoutMs`). Reads
   (`get`, `ground`) copy the needed chunk section from its owning thread on demand (cached per run).
   Unknown block ids fail the whole run before anything is written, with the closest ids and the script line.
2. Diff: the project keeps a layer stack per position (parts in the order they were first run; a later part covers
   an earlier one; under all parts is the original world, recorded in the journal the first time a part touches a
   position). The new buffer replaces the part's layer; only positions whose visible state changes are written.
   Re-running an unchanged part writes nothing.
3. Apply: changes grouped by chunk, each chunk applied on its owning thread (Paper: main; Folia: its region) with
   `ServerLevel.setBlock(pos, state, UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE | UPDATE_SUPPRESS_DROPS)` (no physics,
   no neighbour updates), at most 8 ms of writes per tick per thread; the rest continues next tick. Chunks load
   asynchronously in batches of 64 (existing `ChunkWork`). If the benchmark misses goal 3, the fallback is direct
   `LevelChunkSection.setBlockState` + one light update and chunk resend per finished chunk.
4. Result (compact): `{project, part, changed, removed, bbox, blocks, palette (top 10 with counts), warnings, ms,
   preview?}`. Warnings: blocks needing support placed unsupported (torches, doors, plants on air), parts overlapping
   another part, writes outside the world height (clipped). With `preview` the image is in the same result.

`dryRun:true` stops after the diff (counts, bbox, warnings, preview of the buffer itself) and writes nothing.

### Projects, parts, files

- A project = name, world, origin `{x,y,z}`, rotation (0/90/180/270) and mirror, set on its first `build` call and
  changeable later (moving or turning a project re-applies all its parts at the new place, removing the old).
- The `build` tool takes `file` or `code`. `file` is read by the hub (path relative to the hub's working directory,
  like `savePath`), so Claude writes the script once with its file tools and afterwards edits only changed lines and
  calls `build {file}` again: the code is never re-sent through the conversation.
- A file declares parts: `part("walls", () => { ... })`. `build {file}` runs every part in the file; unchanged parts
  produce identical buffers and write nothing. `part` in the arguments runs just that one. `code` without parts is
  one part named by the `part` argument (default `main`).
- In HTTP mode (`craftwire serve`) `file` is refused unless the hub was started with `--build-dir <dir>`, and then
  only paths inside that directory are read.
- Storage: journal and layer stack in `plugins/Craftwire/builds/<project>/` (compressed, survives restarts).
  Config `max-build-volume` (default 5,000,000 positions per project), `allow-build` (default true),
  `build-journal-limit-mb` (default 512; when exceeded, the oldest undo history entries are dropped first; current
  parts and recorded originals are never dropped, so `remove` and `clear` always work).

### Tools (two new, nothing removed)

- `build {project, file? | code?, part?, world?, origin?, rotation?, mirror?, seed?, dryRun?, preview?:
  'none'|'iso'|'iso4'|'top', previewSize?, timeoutMs?}`. `iso4` = the four corners in one 2x2 image.
- `build_manage {action}`: `list` (projects with parts, bbox, block counts), `undo {project, steps}` (reverts the last
  runs of that project), `remove {project, part}` (the part's layer goes away, what was under it comes back),
  `clear {project}` (world back to the original everywhere the project touched), `move {project, origin?, rotation?,
  mirror?}`, `commit {project}` (keep the blocks, forget the journal).

`world_edit` stays as it is for one-off edits; its description points to `build` for anything larger than a few
hundred blocks.

### Folia

Compute runs off-thread as on Paper. Reads hop to the owning region. Writes go per chunk to the owning region with
the same per-tick budget per region thread. A project spanning regions is applied region by region; the result adds
`regions`. Covered by the Folia IT run (`-Pserver=folia`).

## 3. Build API (JavaScript)

Coordinates are relative to the project origin, +x east, +y up, +z south, before the project's rotation and mirror.
A block argument is an id (`stone_bricks`), a state (`oak_stairs[facing=east,half=top]`), or a source.

- Placing: `set(x,y,z,b)`, `fill(x1,y1,z1,x2,y2,z2,b)`, `hollow(x1,y1,z1,x2,y2,z2,b,{floor,ceiling})`,
  `walls(x1,y1,z1,x2,y2,z2,b)`, `outline(...)` (edges only), `clear(...)` (air), `replace(x1..z2, from, to)`.
- Shapes: `sphere(cx,cy,cz,r,b,{hollow,half:'top'|'bottom'})`, `ellipsoid(cx,cy,cz,rx,ry,rz,b,{hollow})`,
  `cylinder(cx,y,cz,r,h,b,{hollow,axis})`, `cone(cx,y,cz,r,h,b,{hollow})`, `line(x1,y1,z1,x2,y2,z2,b,{width})`,
  `arch(x1,y,z1,x2,z2,height,b,{thickness})`, `polygon(points,y,h,b,{hollow})`.
- Roofs and stairs: `roof({x1,z1,x2,z2,y,type:'gable'|'hip'|'shed'|'flat',axis,overhang,stairs,slab,fill})` places
  stairs with correct facing/half/shape and slabs at the ridge; `stairs(x,y,z,dir,length,b,{width})` for staircases.
- Hand detail: `layers(x,y,z, legend, ["layer y0 rows / separated", ...])`, same notation as `world_query`
  `encoding:'layers'`; legend letters map to blocks or sources, `.` sets air (as in the query output, so a read
  region pastes back exactly), `~` leaves the position untouched.
- Sources: `mix({stone_bricks:7, cracked_stone_bricks:2, mossy_stone_bricks:1})` (seeded by position + `seed`, so
  re-runs are identical), `gradient('y', y0, y1, [blocks])`, `(x,y,z) => block`.
- Transforms: `at(dx,dy,dz, fn)`, `rotate(quarterTurns, fn)` (around the current origin; directional states turn
  with it), `mirror('x'|'z', fn)`, `symmetric('x'|'z'|'xz', fn)` (draw once, mirrored copies), `repeat(n, [dx,dy,dz], fn)`.
- Reads: `get(x,y,z)` (state string), `ground(x,z)` (y of the highest solid block), `isAir(x,y,z)`.
- Misc: `print(...)` (into the result's `output`), `rand()` (seeded), `part(name, fn)`.

Rotating a whole shape is cheaper and more reliable than computing rotated coordinates by hand; the skill says so.

## 4. Preview

`world_render` gains `view:'iso'` with `corner:'ne'|'nw'|'se'|'sw'` and `view:'iso4'` (all four in a 2x2 image,
default 1024x1024, ~1.4k tokens). Painter's-order isometric rendering in the existing Java renderer
(`world/render`): top, left and right faces shaded differently, simple ambient occlusion from neighbours, sky
background, optional grid labels at the bbox corners. `build` uses the same renderer on the part's or project's
bbox plus a 2-block margin.

Colours: the hub builds `block-colors-<mc>.json` (average top / side colour per block, from the client jar's
textures; the jar is fetched with the existing client download code and cached in `CRAFTWIRE_HOME`) and sends it to
an agent with the first render that needs it. Without it (offline, download refused) the renderer uses the
server's map colours and says so in the result (`colors:'map'`).

## 5. `minecraft-building` skill

Ships in `claude-plugin/skills/` and `hub/skills/`. Contents: the loop (block-out volumes -> shapes and roof ->
depth (insets, pillars, overhangs, trims) -> detail -> `iso4` review -> fix by editing the file and re-running only
the changed part); palette recipes (stone, wood, nordic, desert, modern, fantasy) as `mix` snippets; proportion and
depth rules (no flat wall over ~5 blocks without depth, frames around windows, roof overhang 1-2, mix 3-4 blocks
per surface, avoid perfect symmetry on organic builds); the API reference card; common mistakes. Budget: SKILL.md
at most 9,000 chars, details in a `reference.md` loaded on demand. The `craftwire` skill's "keep loops small" line is
replaced by a pointer to `build`.

## 6. Error handling

- Script errors: `SCRIPT_ERROR` with message, line and column in the file; nothing written.
- Unknown block/state: `BAD_BLOCK` listing the offending strings with up to 3 closest ids each.
- Volume over `max-build-volume`: `TOO_LARGE` before writing, with the counted size.
- Timeout in compute: `TIMEOUT`, nothing written. A failure during apply (chunk failed to load, server stopping):
  the run is rolled back from the journal for the chunks already written and `APPLY_FAILED` names the chunks.
- File missing or outside `--build-dir`: `BAD_ARGUMENT` with the resolved path.
- Agent too old (no `build.*` methods): the hub says to update the plugin (existing version check).

## 7. Testing

- `build-core` JUnit: every shape against golden block sets; transforms incl. state rotation through a fake
  `BuildWorld`; `layers` parsing errors; mix determinism; layer-stack diff (re-run = 0 writes, remove restores the
  layer below, undo order); journal round trip.
- `mc-shared`: state parse/rotate/mirror for stairs, logs, doors, rails, walls (game tests on both versions).
- Paper ITs (`BuildIT`, also under `-Pserver=folia`): file build, edit-and-rerun writes only the change, undo and
  clear restore exactly (compare `world_query` before/after), move/rotate, `dryRun` writes nothing, `BAD_BLOCK`
  writes nothing, apply budget keeps ticking (TPS sampled during a 1M-block run).
- Renderer: small golden images for `iso` and `iso4` (pixel tolerance).
- Hub (vitest): compact JSON, `--tools`/`--profile` on stdio, `file` read and `--build-dir` confinement, tool budget,
  `encoding:'layers'` round trip with `layers()`.
- Acceptance: the tower task and the 1M-block benchmark on the user's server, recorded in
  `docs/acceptance/m10-build.md`.

## Versioning and docs

0.10.0 for hub, plugin and mod. New `docs/building.md` (also the `building` doc resource), README section, release
notes with the token numbers before/after. MC versions unchanged (26.2, 26.3; Folia 26.2).

## Not in M10

Singleplayer and schematic files (M11). Block entity contents (sign text, chest items, banner patterns), entities
(armor stands, item frames), redo, multi-user locking of projects, biome painting.
