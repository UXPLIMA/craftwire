// Packs the docs the hub serves as MCP resources (craftwire://docs/<topic>) into the npm package.
import { copyFileSync, mkdirSync, rmSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { DOC_TOPICS } from "../dist/resources.js";

const from = fileURLToPath(new URL("../../docs/", import.meta.url));
const to = fileURLToPath(new URL("../docs/", import.meta.url));
rmSync(to, { recursive: true, force: true });
mkdirSync(to, { recursive: true });
// Every topic the hub serves (prepack builds dist first).
for (const topic of DOC_TOPICS) copyFileSync(`${from}${topic}.md`, `${to}${topic}.md`);
