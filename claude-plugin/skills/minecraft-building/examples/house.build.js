// A small timber-frame cottage, 9 x 7 blocks, door facing south (+z).
// Origin: the north-west corner of the walls, at ground level (y 0 = the floor).
// build {project:"cottage", file:"house.build.js", origin:{x,y,z}, preview:"iso4"}
const W = 8, D = 6, H = 4;   // walls from x 0..W, z 0..D, y 1..H
const plaster = mix({ white_terracotta: 5, white_concrete_powder: 2, light_gray_terracotta: 1 });
const frame = "stripped_spruce_log";

part("base", () => {
  // A foundation one block wider than the walls, and the floor inside.
  fill(-1, 0, -1, W + 1, 0, D + 1, mix({ cobblestone: 3, stone_bricks: 2, mossy_cobblestone: 1 }));
  fill(1, 0, 1, W - 1, 0, D - 1, "spruce_planks");
});

part("walls", () => {
  hollow(0, 1, 0, W, H, D, plaster, { floor: false, ceiling: false });
  // Posts at the corners and beside the door, beams along the top: the frame gives the walls depth.
  for (const [x, z] of [[0, 0], [W, 0], [0, D], [W, D], [3, D], [5, D], [0, 3], [W, 3]]) fill(x, 1, z, x, H, z, frame + "[axis=y]");
  fill(0, H, 0, W, H, 0, frame + "[axis=x]");
  fill(0, H, D, W, H, D, frame + "[axis=x]");
  fill(0, H, 0, 0, H, D, frame + "[axis=z]");
  fill(W, H, 0, W, H, D, frame + "[axis=z]");
});

part("windows", () => {
  // Two-high panes with a sill sticking out below each: frames around glass, never a bare hole.
  for (const x of [2, 6]) {
    fill(x, 2, 0, x, 3, 0, "glass_pane");
    set(x, 1, -1, "spruce_stairs[facing=south,half=top]");
  }
  fill(7, 2, D, 7, 3, D, "glass_pane");
  set(7, 1, D + 1, "spruce_stairs[facing=north,half=top]");
  for (const x of [0, W]) {
    fill(x, 2, 1, x, 3, 2, "glass_pane");
    fill(x, 2, 4, x, 3, 5, "glass_pane");
  }
});

part("door", () => {
  set(4, 1, D, "spruce_door[facing=south,half=lower]");
  set(4, 2, D, "spruce_door[facing=south,half=upper]");
  set(4, 0, D + 1, "cobblestone_stairs[facing=north]");
  set(3, 3, D + 1, "wall_torch[facing=south]");
  set(5, 3, D + 1, "wall_torch[facing=south]");
});

part("roof", () => {
  // The ridge runs along the long side; the gable ends are closed with planks.
  roof({ x1: 0, z1: 0, x2: W, z2: D, y: H + 1, type: "gable", axis: "x", overhang: 1, stairs: "dark_oak_stairs", fill: "spruce_planks" });
});

part("inside", () => {
  set(1, 1, 1, "crafting_table");
  set(2, 1, 1, "chest[facing=south]");
  set(W - 1, 1, 1, "furnace[facing=south]");
  set(W - 1, 1, D - 2, "red_bed[facing=north,part=foot]");
  set(W - 1, 1, D - 3, "red_bed[facing=north,part=head]");
});
