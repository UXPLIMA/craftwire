import { existsSync, mkdtempSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { beforeEach, describe, expect, it } from "vitest";
import { prepareInstance } from "../src/client/instance.js";

let root: string;
let base: Parameters<typeof prepareInstance>[1];

beforeEach(() => {
  root = mkdtempSync(join(tmpdir(), "cw-inst-"));
  for (const f of ["agent.jar", "fabric-api.jar", "sodium.jar"]) writeFileSync(join(root, f), f);
  base = { username: "Bob", agentJar: join(root, "agent.jar"), fabricApiJar: join(root, "fabric-api.jar"), extraMods: [] };
});

describe("client instance folder", () => {
  it("creates the game dir with exactly the expected mods", () => {
    const dir = prepareInstance(root, { ...base, extraMods: [join(root, "sodium.jar")] });
    expect(dir).toBe(join(root, "instances", "Bob"));
    expect(readdirSync(join(dir, "mods")).sort()).toEqual(["agent.jar", "fabric-api.jar", "sodium.jar"]);
  });

  it("removes mods left from a previous start", () => {
    prepareInstance(root, { ...base, extraMods: [join(root, "sodium.jar")] });
    const dir = prepareInstance(root, base);
    expect(readdirSync(join(dir, "mods")).sort()).toEqual(["agent.jar", "fabric-api.jar"]);
  });

  it("writes test-friendly options and keeps the user's keys", () => {
    const dir = prepareInstance(root, base);
    expect(readFileSync(join(dir, "options.txt"), "utf8")).toContain("tutorialStep:none");
    writeFileSync(join(dir, "options.txt"), "guiScale:4\ntutorialStep:movement\n");
    prepareInstance(root, base);
    const text = readFileSync(join(dir, "options.txt"), "utf8");
    expect(text.startsWith("guiScale:4\ntutorialStep:movement\n")).toBe(true); // the user's lines first, unchanged
    expect(text).toContain("pauseOnLostFocus:false");
    expect(text).toContain("inactivityFpsLimit:minimized");
    expect(text.match(/tutorialStep:/g)).toHaveLength(1);
  });

  it("rejects a missing extra mod before touching anything", () => {
    expect(() => prepareInstance(root, { ...base, extraMods: [join(root, "nope.jar")] })).toThrow(/nope\.jar/);
    expect(existsSync(join(root, "instances", "Bob"))).toBe(false);
  });

  it("rejects an extra mod that is not a jar", () => {
    writeFileSync(join(root, "notes.txt"), "x");
    expect(() => prepareInstance(root, { ...base, extraMods: [join(root, "notes.txt")] })).toThrow(/jar/);
  });
});
