import { describe, expect, it } from "vitest";
import { fabricApiFile, fabricLibraryFiles, libraryKey, mavenPath, mergeLibraries } from "../src/client/fabric.js";
import { pinsFor } from "../src/client/pins.js";

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

  it("builds the download of each Fabric library from its Maven repository", async () => {
    const [f] = await fabricLibraryFiles({
      mainClass: "net.fabricmc.loader.impl.launch.knot.KnotClient", arguments: {},
      libraries: [{ name: "net.fabricmc:sponge-mixin:0.17.4", url: "https://maven.fabricmc.net/", sha1: "a".repeat(40), size: 9 }],
    }, "/l");
    expect(f!.file.url).toBe("https://maven.fabricmc.net/net/fabricmc/sponge-mixin/0.17.4/sponge-mixin-0.17.4.jar");
    expect(slash(f!.file.dest)).toBe("/l/net/fabricmc/sponge-mixin/0.17.4/sponge-mixin-0.17.4.jar");
  });

  it("takes a missing sha1 from the Maven repository and keeps it for offline starts", async () => {
    const { mkdtempSync } = await import("node:fs");
    const { tmpdir } = await import("node:os");
    const { join } = await import("node:path");
    const dir = mkdtempSync(join(tmpdir(), "cw-sha-"));
    const profile = { mainClass: "x", arguments: {}, libraries: [{ name: "net.fabricmc:fabric-loader:0.19.5", url: "https://maven.fabricmc.net/" }] };
    const asked: string[] = [];
    const online = (async (url: string) => { asked.push(url); return new Response("ff9e65cffca4a67f31523e1807fe0855940fcbfa\n"); }) as unknown as typeof fetch;
    const [f] = await fabricLibraryFiles(profile, dir, { fetchImpl: online });
    expect(asked).toEqual(["https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar.sha1"]);
    expect(f!.file.sha1).toBe("ff9e65cffca4a67f31523e1807fe0855940fcbfa");
    const offline = (async () => { throw new Error("ENOTFOUND"); }) as unknown as typeof fetch;
    expect((await fabricLibraryFiles(profile, dir, { fetchImpl: offline }))[0]!.file.sha1).toBe("ff9e65cffca4a67f31523e1807fe0855940fcbfa");
  });

  it("refuses a sha1 file that is not a sha1", async () => {
    const { mkdtempSync } = await import("node:fs");
    const { tmpdir } = await import("node:os");
    const { join } = await import("node:path");
    const html = (async () => new Response("<html>not found</html>")) as unknown as typeof fetch;
    await expect(fabricLibraryFiles({ mainClass: "x", arguments: {}, libraries: [{ name: "a:b:1", url: "https://maven.fabricmc.net/" }] },
      mkdtempSync(join(tmpdir(), "cw-sha-")), { fetchImpl: html })).rejects.toMatchObject({ code: "DOWNLOAD_FAILED" });
  });

  it("points Fabric API at the pinned version of each Minecraft version on Fabric's Maven", () => {
    const f = fabricApiFile("/m", pinsFor("26.2"));
    expect(f.url).toBe("https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.161.0%2B26.2/fabric-api-0.161.0%2B26.2.jar");
    expect(f.sha1).toBe("332da34ebb72171e603a0c538de17f4c54ea9e29");
    expect(slash(f.dest)).toBe("/m/fabric-api-0.161.0+26.2.jar");
    expect(fabricApiFile("/m", pinsFor("26.3")).url).toContain("fabric-api-0.162.0%2B26.3.jar");
  });
});

describe("offline starts", () => {
  it("uses the cached Fabric profile when meta.fabricmc.net cannot be reached", async () => {
    const { mkdtempSync } = await import("node:fs");
    const { tmpdir } = await import("node:os");
    const { join } = await import("node:path");
    const { fetchFabricProfile } = await import("../src/client/fabric.js");
    const dir = mkdtempSync(join(tmpdir(), "cw-fab-"));
    const profile = { mainClass: "K", arguments: {}, libraries: [] };
    const online = (async () => new Response(JSON.stringify(profile))) as unknown as typeof fetch;
    const offline = (async () => { throw new Error("ENOTFOUND"); }) as unknown as typeof fetch;
    await fetchFabricProfile("26.2", "0.19.5", dir, { fetchImpl: online });
    expect((await fetchFabricProfile("26.2", "0.19.5", dir, { fetchImpl: offline })).mainClass).toBe("K");
  });
});
