import { describe, expect, it } from "vitest";
import { select } from "../src/scenario/path.js";

const hud = { sidebar: { title: "Shop", lines: [{ text: "Coins: 30" }, { text: "Rank: VIP" }] }, items: [1, 2, 3], "odd key": true };

describe("select", () => {
  it("follows fields and indexes", () => {
    expect(select(hud, "sidebar.title")).toEqual({ found: true, values: ["Shop"] });
    expect(select(hud, "sidebar.lines[1].text")).toEqual({ found: true, values: ["Rank: VIP"] });
    expect(select(hud, "items[-1]")).toEqual({ found: true, values: [3] });
  });

  it("[*] yields every element", () => {
    expect(select(hud, "sidebar.lines[*].text").values).toEqual(["Coins: 30", "Rank: VIP"]);
  });

  it("the empty path is the whole value", () => {
    expect(select(hud, "").values).toEqual([hud]);
  });

  it("quoted keys allow any character", () => {
    expect(select(hud, '["odd key"]').values).toEqual([true]);
  });

  it("a missing step is not found", () => {
    expect(select(hud, "sidebar.nope.x")).toEqual({ found: false, values: [] });
    expect(select(hud, "items[9]").found).toBe(false);
    expect(select(null, "a").found).toBe(false);
  });

  it("rejects malformed paths", () => {
    expect(() => select(hud, "a[")).toThrow(/path/);
    expect(() => select(hud, "a..b")).toThrow(/path/);
  });
});
