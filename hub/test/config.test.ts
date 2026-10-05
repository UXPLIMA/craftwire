import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { craftwireHome, loadOrCreateToken, tokensEqual, writeHubConfig } from "../src/config.js";

const tmp = () => mkdtempSync(join(tmpdir(), "cw-"));

describe("config", () => {
  afterEach(() => { delete process.env.CRAFTWIRE_HOME; });

  it("honours CRAFTWIRE_HOME", () => {
    process.env.CRAFTWIRE_HOME = "/x/y";
    expect(craftwireHome()).toBe("/x/y");
  });

  it("creates a 64-hex token when none exists", () => {
    expect(loadOrCreateToken(tmp())).toMatch(/^[0-9a-f]{64}$/);
  });

  it("reuses the token stored in hub.json", () => {
    const home = tmp();
    const token = "b".repeat(64);
    writeHubConfig({ port: 1234, token }, home);
    expect(loadOrCreateToken(home)).toBe(token);
  });

  it("ignores a corrupt hub.json", () => {
    const home = tmp();
    writeFileSync(join(home, "hub.json"), "{not json");
    expect(loadOrCreateToken(home)).toMatch(/^[0-9a-f]{64}$/);
  });

  it("writes port and token", () => {
    const home = tmp();
    const file = writeHubConfig({ port: 47821, token: "c".repeat(64) }, home);
    expect(JSON.parse(readFileSync(file, "utf8"))).toEqual({ port: 47821, token: "c".repeat(64) });
  });

  it("compares tokens safely", () => {
    expect(tokensEqual("abc", "abc")).toBe(true);
    expect(tokensEqual("abc", "abd")).toBe(false);
    expect(tokensEqual("abc", "abcd")).toBe(false);
  });
});
