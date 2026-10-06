import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { HUB_VERSION } from "../src/version.js";

const read = (f: string) => JSON.parse(readFileSync(new URL(`../${f}`, import.meta.url), "utf8"));

describe("MCP registry entry (server.json)", () => {
  it("names the npm package and matches its version and mcpName", () => {
    const server = read("server.json");
    const pkg = read("package.json");
    expect(server.name).toBe(pkg.mcpName);
    expect(server.version).toBe(pkg.version);
    expect(server.version).toBe(HUB_VERSION);
    expect(server.packages).toEqual([expect.objectContaining({ registryType: "npm", identifier: pkg.name, version: pkg.version, transport: { type: "stdio" } })]);
    expect(server.description.length).toBeLessThanOrEqual(100);
    expect(server.name).toMatch(/^io\.github\.uxplima\/[a-zA-Z0-9._-]+$/);
  });
});
