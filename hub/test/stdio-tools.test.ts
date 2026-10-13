import { execSync } from "node:child_process";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { beforeAll, describe, expect, it } from "vitest";

const hubDir = join(__dirname, "..");
beforeAll(() => { execSync("npm run build", { cwd: hubDir, stdio: "inherit" }); }, 120_000);

async function toolsWith(args: string[]): Promise<Array<{ name: string; size: number }>> {
  const transport = new StdioClientTransport({
    command: process.execPath, args: [join(hubDir, "dist", "cli.js"), ...args],
    env: { ...(process.env as Record<string, string>), CRAFTWIRE_HOME: mkdtempSync(join(tmpdir(), "cw-prof-")), CRAFTWIRE_PORT: "0" }, stderr: "pipe",
  });
  const client = new Client({ name: "t", version: "0" });
  await client.connect(transport);
  try {
    return (await client.listTools()).tools.map((t) => ({ name: t.name, size: JSON.stringify(t).length }));
  } finally {
    await client.close();
  }
}

describe("stdio hub with --profile build", () => {
  it("offers only the build profile", async () => {
    const names = (await toolsWith(["--profile", "build"])).map((t) => t.name);
    expect(names).toContain("world_render");
    expect(names).not.toContain("bot_action");
  });

  it("the build profile stays under 10,500 chars", async () => {
    const size = (await toolsWith(["--profile", "build"])).reduce((n, t) => n + t.size, 0);
    expect(size).toBeLessThanOrEqual(10_500);
  });
});
