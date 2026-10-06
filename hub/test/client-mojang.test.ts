import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { allowed, argumentList, assetFiles, currentOs, libraryFiles, type VersionJson } from "../src/client/mojang.js";

const v = JSON.parse(readFileSync(join(__dirname, "fixtures", "mojang-26.2-excerpt.json"), "utf8")) as VersionJson;
const win = { name: "windows", arch: "x64" } as const;
const slash = (p: string) => p.replace(/\\/g, "/");

describe("Mojang version JSON", () => {
  it("keeps only the libraries allowed on this OS", () => {
    const names = libraryFiles(v, win, "/lib").map((f) => slash(f.dest));
    expect(names).toContain("/lib/at/yawk/lz4/lz4-java/1.10.1/lz4-java-1.10.1.jar");
    expect(names.some((n) => n.endsWith("lwjgl-glfw-3.4.1-natives-windows.jar"))).toBe(true);
    expect(names.some((n) => n.includes("natives-windows-arm64"))).toBe(true); // LWJGL picks the right one at runtime
    expect(names.some((n) => n.includes("java-objc-bridge"))).toBe(false);
    expect(names.some((n) => n.includes("natives-linux"))).toBe(false);
  });

  it("carries the published url and sha1 of each library", () => {
    const glfw = libraryFiles(v, win, "/lib").find((f) => f.url.endsWith("lwjgl-glfw-3.4.1-natives-windows.jar"))!;
    expect(glfw.url).toMatch(/^https:\/\/libraries\.minecraft\.net\//);
    expect(glfw.sha1).toMatch(/^[0-9a-f]{40}$/);
  });

  it("evaluates feature rules in arguments", () => {
    const args = argumentList(v.arguments.game, win, { has_custom_resolution: true, is_quick_play_multiplayer: true });
    expect(args).toContain("--quickPlayMultiplayer");
    expect(args).toContain("--width");
    expect(args).not.toContain("--demo");
    expect(args).not.toContain("--quickPlayPath");
  });

  it("adds the macOS-only JVM flag only on macOS", () => {
    expect(argumentList(v.arguments.jvm, win, {})).not.toContain("-XstartOnFirstThread");
    expect(argumentList(v.arguments.jvm, { name: "osx", arch: "arm64" }, {})).toContain("-XstartOnFirstThread");
  });

  it("a disallow rule after an allow wins", () => {
    expect(allowed([{ action: "allow" }, { action: "disallow", os: { name: "osx" } }], { name: "osx", arch: "x64" }, {})).toBe(false);
    expect(allowed([{ action: "allow" }, { action: "disallow", os: { name: "osx" } }], win, {})).toBe(true);
  });

  it("matches the arch rule as a pattern", () => {
    expect(allowed([{ action: "allow", os: { arch: "x86" } }], { name: "windows", arch: "x86" }, {})).toBe(true);
    expect(allowed([{ action: "allow", os: { arch: "x86" } }], win, {})).toBe(false);
  });

  it("names the OS like Mojang does", () => {
    expect(currentOs("win32", "ia32")).toEqual({ name: "windows", arch: "x86" });
    expect(currentOs("darwin", "arm64")).toEqual({ name: "osx", arch: "arm64" });
    expect(currentOs("linux", "x64")).toEqual({ name: "linux", arch: "x64" });
  });

  it("skips sounds unless asked, and fetches each hash once", () => {
    const index = { objects: {
      "minecraft/sounds/a.ogg": { hash: "ab12", size: 3 },
      "minecraft/lang/x.json": { hash: "cd34", size: 4 },
      "minecraft/lang/y.json": { hash: "cd34", size: 4 },
    } };
    const files = assetFiles(index, "/o", false);
    expect(files.map((f) => f.url)).toEqual(["https://resources.download.minecraft.net/cd/cd34"]);
    expect(slash(files[0]!.dest)).toBe("/o/cd/cd34");
    expect(assetFiles(index, "/o", true)).toHaveLength(2);
  });
});

describe("offline starts", () => {
  it("uses the cached version JSON when the manifest cannot be fetched", async () => {
    const { mkdtempSync, mkdirSync, writeFileSync } = await import("node:fs");
    const { tmpdir } = await import("node:os");
    const { fetchVersion } = await import("../src/client/mojang.js");
    const dir = mkdtempSync(join(tmpdir(), "cw-ver-"));
    mkdirSync(join(dir, "26.2"));
    writeFileSync(join(dir, "26.2", "26.2.json"), JSON.stringify(v));
    const offline = (async () => { throw new Error("getaddrinfo ENOTFOUND"); }) as unknown as typeof fetch;
    expect((await fetchVersion("26.2", dir, { fetchImpl: offline })).id).toBe("26.2");
  });
});
