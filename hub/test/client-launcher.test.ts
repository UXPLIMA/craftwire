import { describe, expect, it } from "vitest";
import { defaultJava, pickJava, withDisplay } from "../src/client/launcher.js";

describe("client launcher", () => {
  it("runs as is on Windows, macOS and Linux desktops", () => {
    const cmd = { command: "java", args: ["-cp", "x"] };
    expect(withDisplay(cmd, "win32", {}, () => false)).toEqual(cmd);
    expect(withDisplay(cmd, "darwin", {}, () => false)).toEqual(cmd);
    expect(withDisplay(cmd, "linux", { DISPLAY: ":0" }, () => false)).toEqual(cmd);
    expect(withDisplay(cmd, "linux", { WAYLAND_DISPLAY: "wayland-0" }, () => false)).toEqual(cmd);
  });

  it("wraps with xvfb-run on a Linux machine without a display", () => {
    expect(withDisplay({ command: "java", args: ["-cp", "x"] }, "linux", {}, () => true))
      .toEqual({ command: "xvfb-run", args: ["-a", "java", "-cp", "x"] });
  });

  it("explains NO_DISPLAY when there is no display and no xvfb-run", () => {
    expect(() => withDisplay({ command: "java", args: [] }, "linux", {}, () => false)).toThrow(expect.objectContaining({ code: "NO_DISPLAY" }));
  });

  it("skips a Java that is too old for one that is new enough", async () => {
    const majors: Record<string, number> = { "/jdk21/bin/java": 21, java: 26 };
    expect(await pickJava(["/jdk21/bin/java", "java"], async (j) => majors[j]!, 25)).toBe("java");
  });

  it("names every Java it tried when none is new enough", async () => {
    await expect(pickJava(["/jdk21/bin/java", "java"], async () => 21, 25))
      .rejects.toMatchObject({ code: "JAVA_TOO_OLD", message: expect.stringMatching(/jdk21.*java/s) });
  });

  it("prefers JAVA_HOME, then java on PATH", () => {
    expect(defaultJava({ JAVA_HOME: "/jdk" }, "linux", () => true)).toBe("/jdk/bin/java");
    expect(defaultJava({ JAVA_HOME: "/jdk" }, "linux", () => false)).toBe("java");
    expect(defaultJava({}, "win32", () => true)).toBe("java");
  });
});
