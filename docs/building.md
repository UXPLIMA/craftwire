# Building with Craftwire

`build` runs a JavaScript build script on a Paper server: shapes, roofs, palettes and transforms instead of one call
per block. Scripts live in files the AI edits in place; each run writes only the blocks that changed, and every run
can be undone. The `minecraft-building` skill (Claude Code plugin, or `npx craftwire setup <client>`) teaches the
workflow; this page is the reference.

## Why

Placing blocks one by one through `world_edit set_blocks` costs about 15-20 tokens per block, so a 50,000-block
build costs around a million tokens. A build script costs the same whether it places 50 or 50,000 blocks, and a fix
is a few edited lines plus one call: the hub reads the file itself, so the script is never sent through the
conversation twice. The preview (`preview:"iso4"`) shows all four corners in one image of about 1,400 tokens.

## Tools

### `build`

| argument | meaning |
|---|---|
| `project` | Name of the build (letters, digits, `_`, `-`). Projects keep their parts, undo history and the original blocks under `plugins/Craftwire/builds/`, across restarts. |
| `file` / `code` | The script: a path relative to the hub's working directory, or the text itself. Pass exactly one. |
| `part` | Run only this part of the file. |
| `origin` | `{x,y,z}`; required on a project's first run. Script coordinates are relative to it. |
| `rotation`, `mirror` | `0/90/180/270` clockwise; `none/x/z`. Set on the first run; change later with `build_manage move`. |
| `world` | Default: the main world. |
| `seed` | Changes how `mix`, `gradient` and `rand()` pick (default 0; the same seed always picks the same blocks). |
| `dryRun` | Count and preview without writing. |
| `preview` | `none` (default), `iso` (south-east corner), `iso4` (four corners in one image), `top`. |
| `previewSize` | Image size in pixels, 256-2048 (default 1024). |
| `timeoutMs` | Script time limit, 1000-300000 (default 60000). |

The result: `{project, world, parts:[{part, changed, removed, blocks}], changed, removed, blocks, bbox:{min,max},
palette, warnings, ms, output?}` and, with a preview, the image. With `dryRun` each part reports `wouldChange`
instead. The iso views use block colours averaged from the game's own textures (taken once from the client jar and
cached under the hub's home folder), or map colours when the jar cannot be had.

A file declares parts with `part(name, () => { ... })`. Without parts the whole script is one part named `main`.
Parts are layers: a part run later covers an earlier one where they overlap; removing a part brings back what was
under it (the earlier part, or the original world). Re-running an unchanged part writes nothing.

### `build_manage`

| action | does |
|---|---|
| `list` | Projects with world, origin, rotation, parts (with block counts) and undo steps. |
| `undo {project, steps}` | Reverts the last runs of the project (1-100 steps). |
| `remove {project, part}` | Takes a part away; what was under it comes back. |
| `clear {project}` | Puts the world back as it was before the project, everywhere it touched, and forgets the project. |
| `move {project, origin?, rotation?, mirror?}` | Clears the build and runs its stored scripts again at the new place. |
| `commit {project}` | Keeps the blocks and forgets the project (no more undo). |

### Over HTTP

`craftwire serve` refuses `file` unless it was started with `--build-dir <dir>`; then only files inside that folder are
read. `code` always works.

### The build profile

`--profile build` (on `craftwire`, `craftwire serve` and `setup`) advertises only the tools a building session needs,
which keeps the tool list small in every request.

## The script API

Coordinates are relative to the project origin: +x east, +y up, +z south, before the project's rotation and mirror.
A block is an id (`stone_bricks`, `minecraft:stone_bricks`), a state (`oak_stairs[facing=east,half=top]`), a source
from `mix` or `gradient`, or a function `(x, y, z) => block` (return `null` to leave a position untouched).
Boxes take two corners in any order and include both.

### Placing

| function | |
|---|---|
| `set(x,y,z, b)` | One block. |
| `fill(x1,y1,z1, x2,y2,z2, b)` | A solid box. |
| `hollow(x1,y1,z1, x2,y2,z2, b, {floor = true, ceiling = true})` | A room: walls (and floor/ceiling) of `b`, inside cleared to air. |
| `walls(x1,y1,z1, x2,y2,z2, b)` | Only the four vertical sides. |
| `outline(x1,y1,z1, x2,y2,z2, b)` | Only the twelve edges. |
| `clear(x1,y1,z1, x2,y2,z2)` | Air. |
| `replace(x1,y1,z1, x2,y2,z2, from, to)` | Where the block (this run's or the world's) matches `from`: an id matches every state, `id[prop=value]` those properties. |

### Shapes

| function | |
|---|---|
| `sphere(cx,cy,cz, r, b, {hollow, half: "top" \| "bottom"})` | Inside when the block centre is within r + 0.5. |
| `ellipsoid(cx,cy,cz, rx,ry,rz, b, {hollow})` | |
| `cylinder(cx,y,cz, r, h, b, {hollow, axis: "y" \| "x" \| "z"})` | From `(cx,y,cz)`, `h` blocks up (or east, or south). |
| `cone(cx,y,cz, r, h, b, {hollow})` | Narrows to a point at the top. |
| `line(x1,y1,z1, x2,y2,z2, b, {width = 1})` | `width` sweeps a cube brush, reaching past both ends. |
| `arch(x1,y,z1, x2,z2, height, b, {thickness = 1})` | A half-ellipse arch along x or along z. |
| `polygon([[x,z], ...], y, h, b, {hollow})` | A prism over the polygon; `hollow` keeps the edges. |

`hollow:true` on round shapes keeps a 1-block shell: the blocks with a neighbour outside the shape.

### Roofs and stairs

`roof({x1, z1, x2, z2, y, type, axis, overhang, stairs, slab, fill, facing})`

- `type`: `gable` (two slopes, default), `hip` (four), `shed` (one, rising toward `facing`), `flat` (slabs).
- `axis`: ridge direction for gable roofs, `x` or `z`; default the longer side.
- `overhang`: widens the roof on every side at the same height (default 0).
- `stairs`: the stair block, e.g. `spruce_stairs`; `slab` defaults to the matching slab (`spruce_slab`).
- `fill`: closes the gable ends (gable) or the sides (shed).

Stairs face their high side, so a slope rising toward the south uses `facing=south`; `roof` sets this. Hip roof
corners, fences, walls and panes are connected by the server after writing.

`stairs(x,y,z, dir, length, stairsId, {width = 1})`: a staircase climbing toward `dir`, `width` to the right of `dir`.

### Hand-drawn layers

`layers(x,y,z, legend, ["layer at y", "layer at y+1", ...])`: one string per height from the bottom; rows run north
to south separated by `/` or new lines (rows are trimmed); letters run west to east. `.` is air, `~` leaves the
position untouched, other letters come from `legend` (`{s: "stone_bricks", g: "glass"}`, values may be sources too).

`world_query {action:"region", min, max, encoding:"layers"}` returns the same notation (`{origin, size, legend,
layers}`; `?` marks unloaded chunks), so a region read from the world pastes back exactly:
`layers(0, 0, 0, legend, layers)`. Up to 65,536 blocks per query, and at most as many block states as there are
letters (`TOO_MANY_STATES` otherwise: read a smaller box).

### Sources

- `mix({stone_bricks: 6, cracked_stone_bricks: 2, mossy_stone_bricks: 1})`: weighted, per position.
- `gradient(axis, from, to, [blocks])`: bands from the first block to the last along `x`, `y` or `z`, with a little
  dither where bands meet.
- `(x, y, z) => block`: any rule you like, e.g. `(x, y, z) => (x + z) % 2 ? "white_wool" : "black_wool"`.

### Frames

`at(dx,dy,dz, fn)`, `rotate(quarterTurns, fn)` (clockwise from above, around the current origin),
`mirror("x" | "z", fn)`, `symmetric("x" | "z" | "xz", fn)` (draws `fn` and its mirror images),
`repeat(n, [dx,dy,dz], i => ...)`. Frames nest. Stairs, logs, doors, rails and other directional blocks turn and
mirror with the frame.

### Reading and the rest

`get(x,y,z)` (full state string; this run's blocks first), `ground(x,z)` (highest solid non-leaf block, relative to
the origin), `isAir(x,y,z)`, `rand()` (seeded), `print(...)` (into `output`), `part(name, fn)`.

Scripts run in a sandbox: no Java classes, files, network or threads.

## Errors

| code | meaning | what to do |
|---|---|---|
| `BAD_BLOCK` | Unknown blocks or properties, each with up to three close ids. | Fix the names; nothing was written. |
| `SCRIPT_ERROR` | A JavaScript error, with the line. | Fix the script; nothing was written. |
| `TIMEOUT` | The script ran past `timeoutMs`. | Look for an endless loop; nothing was written. |
| `TOO_LARGE` | A part places more than `max-build-volume` positions. | Split it, or raise the limit. |
| `APPLY_FAILED` | Writing failed (e.g. the server is stopping); written chunks were put back. | Check the log and run again. |
| `BAD_ARGUMENT` | A missing origin on a new project, an unknown part, a file outside `--build-dir`, ... | As the message says. |
| `PROJECT_NOT_FOUND` | `build_manage` on an unknown project. | `build_manage list`. |
| `WORLD_NOT_FOUND` | No world with that name. | `server_info` lists the worlds. |

## Server settings

In `plugins/Craftwire/config.yml`:

| key | default | |
|---|---|---|
| `allow-build` | `true` | Turns `build` and `build_manage` off. |
| `max-build-volume` | `5000000` | Most positions one part may place. |
| `build-journal-limit-mb` | `512` | Disk space for undo history; the oldest undo steps go first. Parts and originals are kept, so `remove` and `clear` always work. |

Writes run on the thread that owns each chunk (Paper's main thread, or each Folia region), within a per-tick time
budget, so the server keeps its tick rate while a large build is written. Chunks load as needed.

## Token tips

- One file per build, edited in place; re-run only the part you changed (`part:"roof"`).
- `iso4` once per round instead of several screenshots; `dryRun` before anything big.
- Read existing builds with `encoding:"layers"` on small boxes (a room, a facade), not whole builds.
- `--profile build` when a session is only about building.
