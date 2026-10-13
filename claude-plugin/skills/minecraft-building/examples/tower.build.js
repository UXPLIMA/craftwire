// A round watchtower, radius 4, with floors, windows on every side, battlements and a spire.
// Origin: the centre of the tower at ground level.
// build {project:"tower", file:"tower.build.js", origin:{x,y,z}, preview:"iso4"}
const R = 4, HEIGHT = 20;
const stone = mix({ stone_bricks: 6, cracked_stone_bricks: 2, mossy_stone_bricks: 1, andesite: 1 });

part("shell", () => {
  cylinder(0, 0, 0, R + 1, 2, mix({ cobblestone: 2, mossy_cobblestone: 1 }), { hollow: true });   // a wider plinth
  cylinder(0, 0, 0, R, HEIGHT, stone, { hollow: true });
  for (const y of [0, 6, 12, 18]) cylinder(0, y, 0, R - 1, 1, "spruce_planks");
});

part("windows", () => {
  // Draw one side, turn it four times: rotate() also turns stairs and other directional blocks.
  for (let q = 0; q < 4; q++) {
    rotate(q, () => {
      for (const y of [3, 9, 15]) fill(0, y, -R, 0, y + 1, -R, "glass_pane");
      set(0, 2, -R - 1, "stone_brick_stairs[facing=south,half=top]");
    });
  }
  set(0, 1, R, "air");
  set(0, 2, R, "air");   // the doorway on the south side
});

part("top", () => {
  cylinder(0, HEIGHT, 0, R + 1, 1, "stone_bricks");
  for (let q = 0; q < 4; q++) {
    rotate(q, () => {
      for (const x of [-2, 0, 2]) set(x, HEIGHT + 1, -R - 1, "stone_brick_wall");
    });
  }
  cone(0, HEIGHT + 2, 0, R + 1, 8, mix({ deepslate_tiles: 3, cracked_deepslate_tiles: 1 }));
});
