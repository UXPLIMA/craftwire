import { describe, expect, it } from "vitest";
import { fabricApiFile, fabricLibraryFiles, libraryKey, mavenPath, mergeLibraries } from "../src/client/fabric.js";

const slash = (p: string) => p.replace(/\\/g, "/");

describe("Fabric loader profile", () => {
  it("maps Maven coordinates to paths", () => {
    expect(mavenPath("net.fabricmc:fabric-loader:0.19.5")).toBe("net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar");
    expect(mavenPath("org.lwjgl:lwjgl:3.4.1:natives-windows")).toBe("org/lwjgl/lwjgl/3.4.1/lwjgl-3.4.1-natives-windows.jar");
  });

  it("keys libraries by group, artifact and classifier", () => {
    expect(libraryKey("org.ow2.asm:asm:9.6")).toBe("org.ow2.asm:asm");
    expect(libraryKey("org.lwjgl:lwjgl:3.4.1:natives-windows")).toBe("org.lwjgl:lwjgl:natives-windows");
  });

  it("lets Fabric's copy of a library win", () => {
    const mojang = [
      { name: "org.ow2.asm:asm:9.6", file: { url: "m/asm", sha1: "1", dest: "/l/asm-9.6.jar" } },
      { name: "com.google.code.gson:gson:2.11", file: { url: "m/gson", sha1: "2", dest: "/l/gson.jar" } },
    ];
    const fabric = [{ name: "org.ow2.asm:asm:9.10.1", file: { url: "f/asm", sha1: "3", dest: "/l/asm-9.10.jar" } }];
    expect(mergeLibraries(mojang, fabric).map((x) => x.url)).toEqual(["m/gson", "f/asm"]);
  });

  it("builds the download of each Fabric library from its Maven repository", () => {
    const [f] = fabricLibraryFiles({
      mainClass: "net.fabricmc.loader.impl.launch.knot.KnotClient", arguments: {},
      libraries: [{ name: "net.fabricmc:fabric-loader:0.19.5", url: "https://maven.fabricmc.net/", sha1: "a".repeat(40), size: 9 }],
    }, "/l");
    expect(f!.file.url).toBe("https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar");
    expect(slash(f!.file.dest)).toBe("/l/net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar");
  });

  it("needs a sha1 for every Fabric library", () => {
    expect(() => fabricLibraryFiles({ mainClass: "x", arguments: {}, libraries: [{ name: "a:b:1", url: "https://maven.fabricmc.net/" }] }, "/l"))
      .toThrow(/no sha1/);
  });

  it("points Fabric API at the pinned version on Fabric's Maven", () => {
    const f = fabricApiFile("/m");
    expect(f.url).toBe("https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.161.0%2B26.2/fabric-api-0.161.0%2B26.2.jar");
    expect(f.sha1).toBe("332da34ebb72171e603a0c538de17f4c54ea9e29");
    expect(slash(f.dest)).toBe("/m/fabric-api-0.161.0+26.2.jar");
  });
});
