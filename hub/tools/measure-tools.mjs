// Prints each tool's definition size (what a client that loads every tool pays per session).
// Usage: node tools/measure-tools.mjs [hub flags, e.g. --profile build]
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const hubDir = join(dirname(fileURLToPath(import.meta.url)), "..");
const transport = new StdioClientTransport({
  command: process.execPath,
  args: [join(hubDir, "dist", "cli.js"), ...process.argv.slice(2)],
  env: { ...process.env, CRAFTWIRE_HOME: mkdtempSync(join(tmpdir(), "cw-measure-")), CRAFTWIRE_PORT: "0" },
  stderr: "pipe",
});
const client = new Client({ name: "measure", version: "0" });
await client.connect(transport);
const rows = (await client.listTools()).tools.map((t) => [t.name, JSON.stringify(t).length]).sort((a, b) => b[1] - a[1]);
let total = 0;
for (const [name, size] of rows) {
  total += size;
  console.log(`${String(size).padStart(6)}  ${name}`);
}
console.log(`${String(total).padStart(6)}  TOTAL (~${Math.round(total / 3.5)} tokens)`);
await client.close();
