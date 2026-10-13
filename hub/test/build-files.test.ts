import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { readBuildFile } from "../src/build-files.js";

const dir = mkdtempSync(join(tmpdir(), "cw-bf-"));
mkdirSync(join(dir, "builds"));
writeFileSync(join(dir, "builds", "tower.build.js"), "fill(0,0,0,1,1,1,'stone')");
writeFileSync(join(dir, "secret.txt"), "nope");

describe("build files", () => {
  it("reads a path relative to the working directory", () => {
    const r = readBuildFile("builds/tower.build.js", { http: false }, dir);
    expect(r.code).toBe("fill(0,0,0,1,1,1,'stone')");
    expect(r.file).toBe(join(dir, "builds", "tower.build.js"));
  });

  it("names the resolved path when the file is missing", () => {
    expect(() => readBuildFile("nope.js", { http: false }, dir)).toThrow(/nope\.js/);
  });

  it("refuses files over HTTP without --build-dir", () => {
    expect(() => readBuildFile("builds/tower.build.js", { http: true }, dir)).toThrow(/--build-dir/);
  });

  it("confines HTTP reads to --build-dir", () => {
    const o = { http: true, dir: join(dir, "builds") };
    expect(readBuildFile("tower.build.js", o, dir).code).toContain("fill");
    expect(() => readBuildFile("../secret.txt", o, dir)).toThrow(/outside --build-dir/);
    expect(() => readBuildFile(join(dir, "secret.txt"), o, dir)).toThrow(/outside --build-dir/);
  });
});

describe("craftwire serve --build-dir", () => {
  it("is parsed", async () => {
    const { parseServeArgs } = await import("../src/http/serve-command.js");
    expect(parseServeArgs(["--build-dir", "builds"]).buildDir).toBe("builds");
  });
});
