import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { buildLaunch, type LaunchInput, offlineUuid } from "../src/client/launch-args.js";
import type { VersionJson } from "../src/client/mojang.js";

const version = JSON.parse(readFileSync(join(__dirname, "fixtures", "mojang-26.2-excerpt.json"), "utf8")) as VersionJson;
const fabric = {
  mainClass: "net.fabricmc.loader.impl.launch.knot.KnotClient",
  arguments: { jvm: ["-DFabricMcEmu= net.minecraft.client.main.Main "], game: [] },
  libraries: [],
};

function input(over: Partial<LaunchInput> = {}): LaunchInput {
  return {
    version, fabric, os: { name: "windows", arch: "x64" }, java: "java",
    classpath: ["/l/a.jar", "/v/26.2.jar"], pathSep: ":",
    gameDir: "/g", assetsDir: "/a", nativesDir: "/n", libraryDir: "/l", logConfig: "/a/log_configs/client.xml",
    username: "Craftwire", server: "127.0.0.1:25599", width: 1280, height: 720, hidden: true, launcherVersion: "0.5.0",
    ...over,
  };
}

describe("client launch command", () => {
  it("computes the vanilla offline UUID", () => {
    expect(offlineUuid("Notch")).toBe("b50ad385829d3141a2167e7d7539ba7f");
  });

  it("fills every placeholder", () => {
    const { command, args } = buildLaunch(input());
    expect(command).toBe("java");
    expect(args.join(" ")).not.toMatch(/\$\{/);
    expect(args).toEqual(expect.arrayContaining([
      "--username", "Craftwire", "--quickPlayMultiplayer", "127.0.0.1:25599", "--width", "1280", "--height", "720",
      "--accessToken", "0", "--uuid", offlineUuid("Craftwire"), "--gameDir", "/g", "--assetsDir", "/a", "--assetIndex", version.assetIndex.id,
    ]));
    expect(args).toContain("-Dcraftwire.hidden=true");
    expect(args).toContain("-Dlog4j.configurationFile=/a/log_configs/client.xml");
    expect(args).toContain("-DFabricMcEmu= net.minecraft.client.main.Main ");
  });

  it("puts JVM flags before the main class and game arguments after it", () => {
    const { args } = buildLaunch(input());
    const main = args.indexOf("net.fabricmc.loader.impl.launch.knot.KnotClient");
    expect(args.indexOf("-cp")).toBeLessThan(main);
    expect(args.indexOf("-Xmx2G")).toBeLessThan(main);
    expect(args.indexOf("--username")).toBeGreaterThan(main);
  });

  it("joins the classpath with the OS separator", () => {
    const { args } = buildLaunch(input({ pathSep: ";" }));
    expect(args[args.indexOf("-cp") + 1]).toBe("/l/a.jar;/v/26.2.jar");
  });

  it("drops options whose value is empty", () => {
    const { args } = buildLaunch(input());
    expect(args).not.toContain("--clientId");
    expect(args).not.toContain("--xuid");
  });

  it("leaves out quick play without a server, and the hidden flag when visible", () => {
    const { args } = buildLaunch(input({ server: undefined, hidden: false }));
    expect(args).not.toContain("--quickPlayMultiplayer");
    expect(args).not.toContain("-Dcraftwire.hidden=true");
  });
});
