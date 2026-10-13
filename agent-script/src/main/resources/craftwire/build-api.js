// The build API: global functions over the host object __cw (BuildApi). Coordinates are relative to the current frame.
(function (g) {
  const cw = __cw;
  const P = (b) => {
    if (typeof b === "string") return cw.block(b);
    if (typeof b === "function") return cw.custom((x, y, z) => { const r = b(x, y, z); return r == null ? -1 : typeof r === "string" ? cw.state(r) : r; });
    if (b && b.__pick) return b.__pick;
    throw new TypeError("A block is an id, a state string, mix(...), gradient(...) or a function (x,y,z) => block");
  };
  const pick = (p) => ({ __pick: p });
  const o = (opts) => opts || {};
  const frame = (dx, dy, dz, rot, mirror, fn) => { cw.push(dx, dy, dz, rot, mirror); try { fn(); } finally { cw.pop(); } };
  g.mix = (weights) => { const ids = Object.keys(weights); return pick(cw.mix(ids, ids.map((k) => weights[k] | 0))); };
  g.gradient = (axis, from, to, blocks) => pick(cw.gradient(axis, from, to, blocks));
  g.set = (x, y, z, b) => cw.set(x, y, z, P(b));
  g.fill = (x1, y1, z1, x2, y2, z2, b) => cw.fill(x1, y1, z1, x2, y2, z2, P(b));
  g.hollow = (x1, y1, z1, x2, y2, z2, b, opts) => cw.hollow(x1, y1, z1, x2, y2, z2, P(b), o(opts).floor !== false, o(opts).ceiling !== false);
  g.walls = (x1, y1, z1, x2, y2, z2, b) => cw.walls(x1, y1, z1, x2, y2, z2, P(b));
  g.outline = (x1, y1, z1, x2, y2, z2, b) => cw.outline(x1, y1, z1, x2, y2, z2, P(b));
  g.clear = (x1, y1, z1, x2, y2, z2) => cw.clear(x1, y1, z1, x2, y2, z2);
  g.replace = (x1, y1, z1, x2, y2, z2, from, to) => cw.replace(x1, y1, z1, x2, y2, z2, from, P(to));
  g.sphere = (cx, cy, cz, r, b, opts) => cw.sphere(cx, cy, cz, r, P(b), !!o(opts).hollow, o(opts).half === "top" ? 1 : o(opts).half === "bottom" ? -1 : 0);
  g.ellipsoid = (cx, cy, cz, rx, ry, rz, b, opts) => cw.ellipsoid(cx, cy, cz, rx, ry, rz, P(b), !!o(opts).hollow);
  g.cylinder = (cx, y, cz, r, h, b, opts) => cw.cylinder(cx, y, cz, r, h, P(b), !!o(opts).hollow, o(opts).axis || "y");
  g.cone = (cx, y, cz, r, h, b, opts) => cw.cone(cx, y, cz, r, h, P(b), !!o(opts).hollow);
  g.line = (x1, y1, z1, x2, y2, z2, b, opts) => cw.line(x1, y1, z1, x2, y2, z2, P(b), o(opts).width || 1);
  g.arch = (x1, y, z1, x2, z2, height, b, opts) => cw.arch(x1, y, z1, x2, z2, height, P(b), o(opts).thickness || 1);
  g.polygon = (points, y, h, b, opts) => cw.polygon(points.map((p) => p[0]), points.map((p) => p[1]), y, h, P(b), !!o(opts).hollow);
  g.roof = (r) => cw.roof(r.x1, r.z1, r.x2, r.z2, r.y, r.type || "gable", r.axis || "", r.overhang || 0, r.stairs, r.slab || "",
    r.fill === undefined ? null : P(r.fill), r.facing || "north");
  g.stairs = (x, y, z, dir, length, b, opts) => cw.stairs(x, y, z, dir, length, b, o(opts).width || 1);
  g.layers = (x, y, z, legend, layers) => { const keys = Object.keys(legend); cw.layers(x, y, z, keys, keys.map((k) => P(legend[k])), layers); };
  g.at = (dx, dy, dz, fn) => frame(dx, dy, dz, 0, "", fn);
  g.rotate = (quarterTurns, fn) => frame(0, 0, 0, ((quarterTurns % 4) + 4) % 4, "", fn);
  g.mirror = (axis, fn) => frame(0, 0, 0, 0, axis, fn);
  g.symmetric = (axis, fn) => {
    fn();
    if (axis === "x" || axis === "xz") g.mirror("x", fn);
    if (axis === "z" || axis === "xz") g.mirror("z", fn);
    if (axis === "xz") frame(0, 0, 0, 2, "", fn);
  };
  g.repeat = (n, step, fn) => { for (let i = 0; i < n; i++) frame(step[0] * i, step[1] * i, step[2] * i, 0, "", () => fn(i)); };
  g.get = (x, y, z) => cw.get(x, y, z);
  g.ground = (x, z) => cw.ground(x, z);
  g.isAir = (x, y, z) => cw.isAir(x, y, z);
  g.rand = () => cw.rand();
  g.print = (...a) => cw.print(a.map(String).join(" "));
  g.__parts = [];
  g.part = (name, fn) => { g.__parts.push([name, fn]); };
})(globalThis);
