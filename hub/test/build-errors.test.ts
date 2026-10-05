import { describe, expect, it } from "vitest";
import { parseBuildErrors } from "../src/dev/build-errors.js";

describe("parseBuildErrors", () => {
  it("reads javac errors with their symbol and location lines", () => {
    const out = [
      "> Task :compileJava",
      "/p/src/main/java/a/Foo.java:3: error: cannot find symbol",
      "        Bar b;",
      "        ^",
      "  symbol:   class Bar",
      "  location: class Foo",
      "1 error",
    ];
    expect(parseBuildErrors(out)).toEqual([
      { file: "/p/src/main/java/a/Foo.java", line: 3, message: "cannot find symbol; symbol: class Bar; location: class Foo" },
    ]);
  });

  it("handles Windows paths and CRLF output", () => {
    const out = ["C:\\dev\\plugin\\src\\Foo.java:12: error: ';' expected\r", "1 error\r"];
    expect(parseBuildErrors(out)).toEqual([{ file: "C:\\dev\\plugin\\src\\Foo.java", line: 12, message: "';' expected" }]);
  });

  it("reads both Kotlin formats", () => {
    const out = [
      "e: file:///home/u/p/src/Main.kt:7:13 Unresolved reference 'foo'.",
      "e: file:///C:/dev/p/src/Other.kt:2:1 Expecting a top level declaration",
      "e: /home/u/p/src/Old.kt: (4, 9): Type mismatch",
    ];
    expect(parseBuildErrors(out)).toEqual([
      { file: "/home/u/p/src/Main.kt", line: 7, column: 13, message: "Unresolved reference 'foo'." },
      { file: "C:/dev/p/src/Other.kt", line: 2, column: 1, message: "Expecting a top level declaration" },
      { file: "/home/u/p/src/Old.kt", line: 4, column: 9, message: "Type mismatch" },
    ]);
  });

  it("reads Maven errors once even though Maven repeats them", () => {
    const line = "[ERROR] /p/src/main/java/a/Foo.java:[3,9] cannot find symbol";
    expect(parseBuildErrors(["[INFO] Compiling", line, "[ERROR]   symbol:   class Bar", line])).toEqual([
      { file: "/p/src/main/java/a/Foo.java", line: 3, column: 9, message: "cannot find symbol" },
    ]);
  });

  it("ignores warnings and stops at max", () => {
    const out = ["/p/A.java:1: warning: [deprecation] x", ...Array.from({ length: 5 }, (_, i) => `/p/B.java:${i + 1}: error: e${i}`)];
    expect(parseBuildErrors(out, 3).map((e) => e.line)).toEqual([1, 2, 3]);
  });
});
