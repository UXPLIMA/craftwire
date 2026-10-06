import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, join } from "node:path";
import { describe, expect, it } from "vitest";
import { agentJar, CLIENT_VERSIONS, LOADER, NEWEST_VERSION, pinsFor, supportedVersion } from "../src/client/pins.js";
import { HUB_VERSION } from "../src/version.js";

const readProps = (file: string): Record<string, string> => Object.fromEntries(readFileSync(file, "utf8")
  .split(/\r?\n/).map((l) => /^([\w.]+)=(.*)$/.exec(l.trim())).filter((m) => m !== null).map((m) => [m[1], m[2]]));
const repo = join(__dirname, "..", "..");
const props = readProps(join(repo, "gradle.properties"));

describe("client pins", () => {
  it("cover exactly the versions the agent is built for, with the same Fabric versions", () => {
    expect(Object.keys(CLIENT_VERSIONS)).toEqual(props.mc_versions!.split(",").map((v) => v.trim()));
    expect(LOADER).toBe(props.loader_version);
    for (const [mc, pin] of Object.entries(CLIENT_VERSIONS)) {
      const v = readProps(join(repo, "versions", `${mc}.properties`));
      expect(pin.fabricApi).toBe(v.fabric_api_version);
      expect(pin.fabricApiSha1).toMatch(/^[0-9a-f]{40}$/);
    }
    expect(pinsFor(NEWEST_VERSION).minecraft).toBe(Object.keys(CLIENT_VERSIONS).at(-1));
  });

  it("maps a reported game version to a supported one", () => {
    expect(supportedVersion("26.3")).toBe("26.3");
    expect(supportedVersion("26.2.1")).toBe("26.2");
    expect(supportedVersion("Paper 26.3")).toBe("26.3");
    expect(supportedVersion("1.21.11")).toBeUndefined();
    expect(supportedVersion("26.9")).toBeUndefined();
    expect(supportedVersion(undefined)).toBeUndefined();
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
