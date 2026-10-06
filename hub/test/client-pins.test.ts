import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, join } from "node:path";
import { describe, expect, it } from "vitest";
import { agentJar, PINS } from "../src/client/pins.js";
import { HUB_VERSION } from "../src/version.js";

const props = Object.fromEntries(readFileSync(join(__dirname, "..", "..", "gradle.properties"), "utf8")
  .split(/\r?\n/).map((l) => /^([\w.]+)=(.*)$/.exec(l.trim())).filter((m) => m !== null).map((m) => [m[1], m[2]]));

describe("client pins", () => {
  it("match the versions the agent is built for", () => {
    expect(PINS.minecraft).toBe(props.minecraft_version);
    expect(PINS.loader).toBe(props.loader_version);
    expect(PINS.fabricApi).toBe(props.fabric_api_version);
    expect(PINS.fabricApiSha1).toMatch(/^[0-9a-f]{40}$/);
  });

  it("finds the agent jar of this hub version in the first folder that has it", () => {
    const empty = mkdtempSync(join(tmpdir(), "cw-agent-"));
    const packed = mkdtempSync(join(tmpdir(), "cw-agent-"));
    writeFileSync(join(packed, "craftwire-agent-fabric-0.0.1.jar"), "old");
    writeFileSync(join(packed, `craftwire-agent-fabric-${HUB_VERSION}.jar`), "x");
    const jar = agentJar([empty, packed]);
    expect(basename(jar)).toBe(`craftwire-agent-fabric-${HUB_VERSION}.jar`);
    expect(existsSync(jar)).toBe(true);
  });

  it("says how to get the agent jar when it is missing", () => {
    expect(() => agentJar([mkdtempSync(join(tmpdir(), "cw-agent-"))])).toThrow(expect.objectContaining({ code: "AGENT_JAR_MISSING" }));
  });
});
