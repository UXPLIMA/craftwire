import { describe, expect, it } from "vitest";
import { ok } from "../src/tools/registry.js";

describe("tool results", () => {
  it("are compact JSON", () => {
    const r = ok({ palette: ["air", "stone"], runs: [[0, 3], [1, 2]] });
    expect(r.content[0]).toEqual({ type: "text", text: '{"palette":["air","stone"],"runs":[[0,3],[1,2]]}' });
  });
});
