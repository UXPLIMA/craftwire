// Packs the docs the hub serves as MCP resources (craftwire://docs/<topic>) into the npm package.
import { copyFileSync, mkdirSync, rmSync } from "node:fs";
import { fileURLToPath } from "node:url";

const from = fileURLToPath(new URL("../../docs/", import.meta.url));
const to = fileURLToPath(new URL("../docs/", import.meta.url));
rmSync(to, { recursive: true, force: true });
mkdirSync(to, { recursive: true });
for (const topic of ["scenarios", "extensions", "http", "video"]) copyFileSync(`${from}${topic}.md`, `${to}${topic}.md`);
