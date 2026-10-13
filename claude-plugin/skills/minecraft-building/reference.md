# Building reference

Read this when the API card in SKILL.md is not enough. Both example files build cleanly on a real server (they are
part of Craftwire's tests): copy one, change the sizes and palette, and run it.

## Example: `examples/house.build.js`

A 9 x 7 timber-frame cottage in six parts: `base` (foundation one block wider than the walls, plank floor),
`walls` (plaster `hollow` with log posts and beams for depth), `windows` (two-high panes with upside-down stair sills
outside), `door` (both door halves, a step, two wall torches), `roof` (gable along x, overhang 1, gable ends filled),
`inside` (crafting table, chest, furnace, bed). Sizes are constants at the top: change `W`, `D`, `H` and the rest
follows.

## Example: `examples/tower.build.js`

A round watchtower: a hollow `cylinder` shell on a wider plinth, plank floors every 6 blocks, windows drawn once and
turned four times with `rotate(q, ...)` (the stair sills turn with them), battlements of walls on a wider ring, and a
`cone` spire of mixed deepslate tiles.

## How the functions behave

- `hollow` clears the inside to air (it is a room); `walls` only places the four sides and leaves the inside alone.
- `fill` with a `mix` picks per position from a hash of the position and the run's `seed`, so a re-run places the
  same blocks and writes nothing. Pass a different `seed` to the `build` tool to reshuffle.
- `sphere`/`ellipsoid`/`cylinder`/`cone` count a block as inside when its centre is within radius + 0.5, which gives
  round shapes without spikes. `hollow:true` keeps only blocks with an outside neighbour (a 1-block shell).
- `line` with `width:3` sweeps a 3 x 3 x 3 brush, so it also reaches one block past both ends.
- `arch` spans from (x1,z1) to (x2,z2) along one axis and rises `height` above `y` in a half ellipse.
- `roof`: `axis` is the ridge direction for gable roofs (default: the longer side). `overhang` widens the roof on all
  sides at the same height. `fill` closes the gable triangles at the walls' ends (gable) or the sides (shed).
  `type:"shed"` rises toward `facing`. `type:"flat"` is a slab layer. Hip roofs have four slopes; their corners are
  shaped by the server after writing.
- `stairs(x,y,z, dir, length, id, {width})` climbs toward `dir`, `width` to the right of `dir`.
- `layers` takes one string per height from the bottom; rows run north to south separated by `/` or new lines (rows
  are trimmed, so template strings with indentation work); letters run west to east. `.` is air, `~` leaves the
  position untouched.
- `at`, `rotate`, `mirror`, `symmetric` and `repeat` nest; directional states (stairs, logs, doors, rails) turn and
  mirror with the frame.
- `get` returns the full state string at a position (this run's placement first, else the world); `ground(x,z)` is
  the height of the highest solid, non-leaf block, relative to the origin.
- `print(...)` lines come back in the result's `output`.

## Results and warnings

`build` returns `{project, parts:[{part, changed, removed, blocks}], changed, removed, blocks, bbox, palette, warnings,
ms, output?}` plus the preview image. Warnings name blocks without support (with positions), blocks outside the build
height, and overlapping parts. With `dryRun:true` each part reports `wouldChange` instead and nothing is written.
