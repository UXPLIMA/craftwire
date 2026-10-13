import { describe, expect, it } from "vitest";
import { decodePng } from "../src/blocks/png.js";
import { encodePng } from "./helpers/png.js";

describe("decodePng", () => {
  it("reads RGBA", () => {
    const img = decodePng(encodePng(2, 1, 6, [255, 0, 0, 255, 0, 0, 255, 128]));
    expect([img.width, img.height]).toEqual([2, 1]);
    expect([...img.rgba]).toEqual([255, 0, 0, 255, 0, 0, 255, 128]);
  });

  it("reads RGB as opaque", () => {
    expect([...decodePng(encodePng(1, 1, 2, [1, 2, 3])).rgba]).toEqual([1, 2, 3, 255]);
  });

  it("reads palettes with transparency", () => {
    const img = decodePng(encodePng(2, 1, 3, [0, 1], [[10, 20, 30], [40, 50, 60]], [0]));
    expect([...img.rgba]).toEqual([10, 20, 30, 0, 40, 50, 60, 255]);
  });

  it("reads 4-bit and 1-bit palettes", () => {
    const pal = [[10, 20, 30], [40, 50, 60], [70, 80, 90]];
    expect([...decodePng(encodePng(3, 1, 3, [2, 0, 1], pal, undefined, 4)).rgba]).toEqual([70, 80, 90, 255, 10, 20, 30, 255, 40, 50, 60, 255]);
    expect([...decodePng(encodePng(3, 1, 3, [1, 0, 1], pal, undefined, 1)).rgba]).toEqual([40, 50, 60, 255, 10, 20, 30, 255, 40, 50, 60, 255]);
  });

  it("refuses what is not a PNG", () => {
    expect(() => decodePng(Buffer.from("nope"))).toThrow(/PNG/);
  });
});
