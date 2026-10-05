import { existsSync, mkdirSync, mkdtempSync, readdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { pluginInfoOfJar, pluginJarsNamed, readZipEntry } from "../src/dev/jar.js";
import { makeZip } from "./helpers/zip.js";

const tmp = () => mkdtempSync(join(tmpdir(), "cw-jar-"));

describe("plugin jars", () => {
  it("reads plugin.yml from a stored jar", () => {
    const jar = makeZip(join(tmp(), "demo.jar"), { "META-INF/MANIFEST.MF": "Manifest-Version: 1.0\n", "plugin.yml": "name: Demo\nversion: 1.2.3\nmain: a.B\n" });
    expect(pluginInfoOfJar(jar)).toEqual({ name: "Demo", version: "1.2.3", descriptor: "plugin.yml" });
  });

  it("prefers paper-plugin.yml and handles quotes, comments and deflate", () => {
    const jar = makeZip(join(tmp(), "fancy.jar"), {
      "plugin.yml": "name: Legacy\n",
      "paper-plugin.yml": "name: \"Fancy\" # the name\nversion: '2.0'\n",
    }, { deflate: true });
    expect(pluginInfoOfJar(jar)).toEqual({ name: "Fancy", version: "2.0", descriptor: "paper-plugin.yml" });
    expect(readZipEntry(jar, "plugin.yml")?.toString("utf8")).toBe("name: Legacy\n");
    expect(readZipEntry(jar, "missing.yml")).toBeUndefined();
  });

  it("returns undefined for non-plugins and non-zips", () => {
    const dir = tmp();
    expect(pluginInfoOfJar(makeZip(join(dir, "lib.jar"), { "a/B.class": "x" }))).toBeUndefined();
    writeFileSync(join(dir, "text.jar"), "not a zip");
    expect(pluginInfoOfJar(join(dir, "text.jar"))).toBeUndefined();
  });

  it("finds installed jars of a plugin case-insensitively", () => {
    const plugins = join(tmp(), "plugins");
    mkdirSync(plugins);
    makeZip(join(plugins, "craftwire-paper-0.2.0.jar"), { "plugin.yml": "name: Craftwire\n" });
    makeZip(join(plugins, "other.jar"), { "plugin.yml": "name: Other\n" });
    writeFileSync(join(plugins, "notes.txt"), "craftwire");
    expect(pluginJarsNamed(plugins, "craftwire")).toEqual([join(plugins, "craftwire-paper-0.2.0.jar")]);
    expect(pluginJarsNamed(join(plugins, "missing"), "Craftwire")).toEqual([]);
  });

  const libs = join(__dirname, "..", "..", "agent-paper", "build", "libs");
  const real = existsSync(libs) ? readdirSync(libs).find((f) => /^craftwire-paper-.*\.jar$/.test(f)) : undefined;
  it.runIf(real !== undefined)("reads the real Craftwire plugin jar", () => {
    expect(pluginInfoOfJar(join(libs, real!))?.name).toBe("Craftwire");
  });
});
