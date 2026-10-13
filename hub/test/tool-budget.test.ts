import { readFileSync } from "node:fs";
import { join } from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { startHub } from "./helpers/hub.js";

let hub: Awaited<ReturnType<typeof startHub>>;
beforeAll(async () => { hub = await startHub(); });
afterAll(async () => { await hub.close(); });

const sizes = async () => (await hub.client.listTools()).tools.map((t) => ({ name: t.name, size: JSON.stringify(t).length }));

describe("tool definitions fit the token budget", () => {
  it("all tools together stay under 28,000 chars", async () => {
    const total = (await sizes()).reduce((n, t) => n + t.size, 0);
    expect(total).toBeLessThanOrEqual(28_000);
  });

  it("no tool is over 1,800 chars", async () => {
    const big = (await sizes()).filter((t) => t.size > 1_800);
    expect(big).toEqual([]);
  });
});

describe("skills stay small", () => {
  const skills = join(__dirname, "..", "..", "claude-plugin", "skills");
  it("minecraft-building SKILL.md is at most 9,000 chars", () => {
    expect(readFileSync(join(skills, "minecraft-building", "SKILL.md"), "utf8").length).toBeLessThanOrEqual(9_000);
  });
  it("the craftwire skill no longer tells Claude to keep build loops small", () => {
    expect(readFileSync(join(skills, "craftwire", "SKILL.md"), "utf8")).not.toMatch(/keep loops small/);
  });
});
