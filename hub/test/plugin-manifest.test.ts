import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const root = join(__dirname, "..", "..");
const read = (p: string) => JSON.parse(readFileSync(join(root, p), "utf8"));

describe("Claude Code plugin packaging", () => {
  const hubPkg = read("hub/package.json");

  it("marketplace lists the craftwire plugin", () => {
    const m = read(".claude-plugin/marketplace.json");
    expect(m.name).toBe("uxplima");
    expect(m.plugins).toContainEqual(expect.objectContaining({ name: "craftwire", source: "./claude-plugin" }));
  });

  it("plugin version is lockstep with the hub", () => {
    expect(read("claude-plugin/.claude-plugin/plugin.json").version).toBe(hubPkg.version);
  });

  it(".mcp.json starts the pinned hub via npx", () => {
    const mcp = read("claude-plugin/.mcp.json");
    expect(mcp.mcpServers.craftwire.command).toBe("npx");
    expect(mcp.mcpServers.craftwire.args).toEqual(["-y", `craftwire@${hubPkg.version}`]);
  });

  it("skills have name and description front matter", () => {
    for (const skill of ["craftwire", "minecraft-promo-shots", "paper-plugin-dev"]) {
      const text = readFileSync(join(root, "claude-plugin/skills", skill, "SKILL.md"), "utf8");
      expect(text).toMatch(new RegExp(`^---\\nname: ${skill}\\ndescription: .+\\n---`, "m"));
    }
  });
});
