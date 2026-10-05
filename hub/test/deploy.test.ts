import { existsSync, mkdirSync, mkdtempSync, readdirSync, utimesSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { detectBuild, expandGlob, findBuiltJar, installPluginJar, pluginProblems, runBuild, withLockRetry } from "../src/dev/deploy.js";
import { pluginInfoOfJar } from "../src/dev/jar.js";
import { makeZip } from "./helpers/zip.js";

const tmp = () => mkdtempSync(join(tmpdir(), "cw-deploy-"));
const NODE = `"${process.execPath}"`;
const touch = (dir: string, ...files: string[]) => {
  for (const f of files) {
    mkdirSync(join(dir, f, ".."), { recursive: true });
    writeFileSync(join(dir, f), "");
  }
  return dir;
};
const jar = (file: string, name: string, version = "1.0") => {
  mkdirSync(join(file, ".."), { recursive: true });
  return makeZip(file, { "plugin.yml": `name: ${name}\nversion: ${version}\n` });
};
const age = (file: string, secondsAgo: number) => {
  const t = Date.now() / 1000 - secondsAgo;
  utimesSync(file, t, t);
};

describe("detectBuild", () => {
  // Explicit .\ path: Claude Code sets NoDefaultCurrentDirectoryInExePath, so cmd would not find a bare gradlew.bat.
  it("prefers the Gradle wrapper, skips tests and fits the platform", () => {
    const d = touch(tmp(), "gradlew", "gradlew.bat", "build.gradle.kts");
    expect(detectBuild(d, "win32")).toEqual({ tool: "gradle", command: ".\\gradlew.bat build -x test --console=plain" });
    expect(detectBuild(d, "linux")).toEqual({ tool: "gradle", command: "sh ./gradlew build -x test --console=plain" });
  });

  it("falls back to gradle, then Maven, then nothing", () => {
    expect(detectBuild(touch(tmp(), "build.gradle"), "linux")?.command).toBe("gradle build -x test --console=plain");
    expect(detectBuild(touch(tmp(), "pom.xml", "mvnw.cmd"), "win32")?.command).toBe(".\\mvnw.cmd -B package -DskipTests");
    expect(detectBuild(touch(tmp(), "pom.xml"), "linux")).toEqual({ tool: "maven", command: "mvn -B package -DskipTests" });
    expect(detectBuild(tmp(), "linux")).toBeUndefined();
  });
});

describe("runBuild", () => {
  it("collects output and the exit code, with JAVA_HOME set when asked", async () => {
    const r = await runBuild(tmp(), `${NODE} -e "console.log(process.env.JAVA_HOME); process.exit(3)"`, { timeoutMs: 20_000, javaHome: "/opt/jdk-25" });
    expect(r).toMatchObject({ exitCode: 3, timedOut: false });
    expect(r.output).toContain("/opt/jdk-25");
  });

  it("kills a build that runs too long", async () => {
    const r = await runBuild(tmp(), `${NODE} -e "setInterval(() => {}, 1000)"`, { timeoutMs: 500 });
    expect(r.timedOut).toBe(true);
  });
});

describe("jar discovery", () => {
  it("matches globs relative to the project", () => {
    const d = touch(tmp(), "build/libs/a.jar", "mod/build/libs/b.jar", "node_modules/x/build/libs/c.jar");
    expect(expandGlob(d, "**/build/libs/*.jar").map((f) => f.slice(d.length + 1).replace(/\\/g, "/"))).toEqual(["build/libs/a.jar", "mod/build/libs/b.jar"]);
  });

  it("picks the newest plugin jar and skips sources/javadoc jars and libraries", () => {
    const d = tmp();
    age(jar(join(d, "build/libs/demo-1.0.jar"), "Demo", "1.0"), 60);
    jar(join(d, "build/libs/demo-1.1.jar"), "Demo", "1.1");
    jar(join(d, "build/libs/demo-1.1-sources.jar"), "Demo", "1.1");
    makeZip(join(d, "build/libs/lib.jar"), { "a/B.class": "x" });
    expect(findBuiltJar(d)).toMatchObject({ path: join(d, "build/libs/demo-1.1.jar"), plugin: { name: "Demo", version: "1.1" } });
  });

  it("asks for jarGlob when jars of different plugins are found", () => {
    const d = tmp();
    jar(join(d, "a/build/libs/a.jar"), "Alpha");
    jar(join(d, "b/build/libs/b.jar"), "Beta");
    expect(() => findBuiltJar(d)).toThrow(expect.objectContaining({ code: "AMBIGUOUS_JAR" }));
    expect(findBuiltJar(d, "b/build/libs/*.jar").plugin.name).toBe("Beta");
    expect(() => findBuiltJar(tmp())).toThrow(expect.objectContaining({ code: "JAR_NOT_FOUND" }));
  });
});

describe("installPluginJar", () => {
  it("replaces the old jar of the same plugin and keeps a backup", async () => {
    const server = tmp();
    const old = jar(join(server, "plugins/demo-1.0.jar"), "Demo", "1.0");
    jar(join(server, "plugins/other.jar"), "Other");
    const fresh = jar(join(tmp(), "demo-1.1.jar"), "Demo", "1.1");
    const r = await installPluginJar(server, fresh, pluginInfoOfJar(fresh)!, { running: false });
    expect(r).toEqual({ installed: join(server, "plugins", "demo-1.1.jar"), replaced: [old], backupDir: join(server, "plugins", ".craftwire-backup"), needsRestart: false });
    expect(readdirSync(join(server, "plugins")).sort()).toEqual([".craftwire-backup", "demo-1.1.jar", "other.jar"]);
    expect(existsSync(join(server, "plugins", ".craftwire-backup", "demo-1.0.jar"))).toBe(true);
  });

  it("stages the jar in plugins/update under the old name while the server runs (jars are locked on Windows)", async () => {
    const server = tmp();
    const old = jar(join(server, "plugins/demo-1.0.jar"), "Demo", "1.0");
    const fresh = jar(join(tmp(), "demo-1.1.jar"), "Demo", "1.1");
    const r = await installPluginJar(server, fresh, pluginInfoOfJar(fresh)!, { running: true });
    expect(r).toEqual({ installed: join(server, "plugins", "update", "demo-1.0.jar"), replaced: [], needsRestart: true });
    expect(existsSync(old)).toBe(true);
  });
});

describe("withLockRetry", () => {
  const locked = () => Object.assign(new Error("EBUSY: resource busy or locked"), { code: "EBUSY" });

  it("retries while Windows still holds the file lock", async () => {
    let calls = 0;
    const r = await withLockRetry(() => { if (++calls < 3) throw locked(); return "done"; }, { timeoutMs: 2000, everyMs: 10 });
    expect(r).toBe("done");
    expect(calls).toBe(3);
  });

  it("gives up with FILE_LOCKED, and does not retry other errors", async () => {
    await expect(withLockRetry(() => { throw locked(); }, { timeoutMs: 50, everyMs: 10 })).rejects.toMatchObject({ code: "FILE_LOCKED" });
    let calls = 0;
    await expect(withLockRetry(() => { calls++; throw new Error("ENOENT"); }, { timeoutMs: 1000, everyMs: 10 })).rejects.toThrow("ENOENT");
    expect(calls).toBe(1);
  });
});

describe("pluginProblems", () => {
  it("keeps WARN+ lines from or about the plugin", () => {
    const line = (level: string, logger: string, message: string) => ({ time: 1, level, logger, message });
    const lines = [line("WARN", "Demo", "config missing"), line("INFO", "Demo", "enabled"), line("ERROR", "Server", "Error occurred while enabling Demo v1.1"), line("WARN", "Other", "noise")];
    expect(pluginProblems(lines, "demo").map((l) => l.message)).toEqual(["config missing", "Error occurred while enabling Demo v1.1"]);
  });
});
