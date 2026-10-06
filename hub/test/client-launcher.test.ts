import { describe, expect, it } from "vitest";
import { defaultJava, withDisplay } from "../src/client/launcher.js";

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

  it("prefers JAVA_HOME, then java on PATH", () => {
    expect(defaultJava({ JAVA_HOME: "/jdk" }, "linux", () => true)).toBe("/jdk/bin/java");
    expect(defaultJava({ JAVA_HOME: "/jdk" }, "linux", () => false)).toBe("java");
    expect(defaultJava({}, "win32", () => true)).toBe("java");
  });
});
