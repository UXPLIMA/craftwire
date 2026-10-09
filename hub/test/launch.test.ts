import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { samePath } from "../src/dev/paths.js";
import { eulaAccepted, javaMajorVersion, parseStartScript, resolveLaunch, tokenize } from "../src/dev/launch.js";

const AIKAR = "java -Xms6144M -Xmx6144M --add-modules=jdk.incubator.vector -XX:+UseG1GC -Dusing.aikars.flags=https://mcflags.emc.gs -jar server.jar --nogui";
const dir = (files: Record<string, string>) => {
  const d = mkdtempSync(join(tmpdir(), "cw-launch-"));
  for (const [f, text] of Object.entries(files)) writeFileSync(join(d, f), text);
  return d;
};

describe("start scripts", () => {
  it("tokenizes with double quotes", () => {
    expect(tokenize(`"C:\\Program Files\\Java\\bin\\java.exe" -Xmx2G  -jar "my server.jar"`)).toEqual(["C:\\Program Files\\Java\\bin\\java.exe", "-Xmx2G", "-jar", "my server.jar"]);
  });

  it("reads the java line of a typical start.bat", () => {
    expect(parseStartScript(`@echo off\r\n${AIKAR}\r\npause\r\n`)).toEqual({
      java: "java",
      jvmArgs: ["-Xms6144M", "-Xmx6144M", "--add-modules=jdk.incubator.vector", "-XX:+UseG1GC", "-Dusing.aikars.flags=https://mcflags.emc.gs"],
      jar: "server.jar",
      serverArgs: ["--nogui"],
    });
  });

  it("joins continuation lines and reads sh scripts", () => {
    expect(parseStartScript("#!/bin/sh\nexec java -Xmx4G \\\n  -jar paper-26.2.jar nogui\n")).toEqual({ java: "java", jvmArgs: ["-Xmx4G"], jar: "paper-26.2.jar", serverArgs: ["nogui"] });
    expect(parseStartScript("java -Xmx1G ^\r\n -jar server.jar\r\n")?.jar).toBe("server.jar");
  });

  it("gives up on variables it cannot expand", () => {
    expect(parseStartScript("java -Xmx%RAM% -jar server.jar\n")).toBeUndefined();
    expect(parseStartScript('"$JAVA" -jar server.jar\n')).toBeUndefined();
    expect(parseStartScript("echo hi\n")).toBeUndefined();
  });
});

describe("resolveLaunch", () => {
  it("uses the start script's flags, forces UTF-8 console output and adds --nogui once", () => {
    const d = dir({ "start.bat": AIKAR, "start.sh": AIKAR, "server.jar": "" });
    const l = resolveLaunch(d);
    expect(l.command).toBe("java");
    expect(l.source).toMatch(/^start\.(bat|sh)$/);
    expect(l.args.slice(0, 2)).toEqual(["-Xms6144M", "-Xmx6144M"]);
    expect(l.args).toContain("-Dstdout.encoding=UTF-8");
    expect(l.args.slice(-3)).toEqual(["-jar", join(d, "server.jar"), "--nogui"]);
    expect(l.args.filter((a) => a.includes("nogui"))).toHaveLength(1);
  });

  it("lets parameters override the script", () => {
    const d = dir({ "start.sh": AIKAR, "start.bat": AIKAR, "server.jar": "", "other.jar": "" });
    const l = resolveLaunch(d, { java: "/opt/jdk25/bin/java", jvmArgs: ["-Xmx1G"], jar: "other.jar" });
    expect(l).toMatchObject({ command: "/opt/jdk25/bin/java", source: "jvmArgs", jar: join(d, "other.jar") });
    expect(l.args[0]).toBe("-Xmx1G");
  });

  it("falls back to defaults and a single paper jar", () => {
    const l = resolveLaunch(dir({ "paper-26.2-130.jar": "" }));
    expect(l.source).toBe("defaults");
    expect(l.args).toContain("-Xmx2G");
    expect(l.jar).toMatch(/paper-26\.2-130\.jar$/);
  });

  it("finds a single folia jar too", () => {
    expect(resolveLaunch(dir({ "folia-26.2-7.jar": "" })).jar).toMatch(/folia-26\.2-7\.jar$/);
  });

  it("fails clearly without a server jar", () => {
    expect(() => resolveLaunch(dir({}))).toThrow(expect.objectContaining({ code: "SERVER_JAR_NOT_FOUND" }));
  });
});

describe("environment checks", () => {
  it("parses java -version output", () => {
    expect(javaMajorVersion('openjdk version "26.0.1" 2026-07-21\nOpenJDK Runtime Environment')).toBe(26);
    expect(javaMajorVersion('openjdk version "25" 2025-09-16')).toBe(25);
    expect(javaMajorVersion('java version "1.8.0_391"')).toBe(8);
    expect(javaMajorVersion("garbage")).toBeUndefined();
  });

  it("checks the EULA without changing it", () => {
    expect(eulaAccepted(dir({ "eula.txt": "#By changing...\neula=true\n" }))).toBe(true);
    expect(eulaAccepted(dir({ "eula.txt": "eula=false\n" }))).toBe(false);
    expect(eulaAccepted(dir({}))).toBe(false);
  });

  it.runIf(process.platform === "win32")("compares Windows paths ignoring case and slash style", () => {
    expect(samePath("C:\\Users\\pc\\Desktop\\server", "c:/users/pc/desktop/server/")).toBe(true);
  });
});
