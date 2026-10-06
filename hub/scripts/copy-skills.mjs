// Packs the plugin's skills into the npm package, so `craftwire setup` can install them for Codex and Gemini CLI.
import { cpSync, rmSync } from "node:fs";
import { fileURLToPath } from "node:url";

const from = fileURLToPath(new URL("../../claude-plugin/skills", import.meta.url));
const to = fileURLToPath(new URL("../skills", import.meta.url));
rmSync(to, { recursive: true, force: true });
cpSync(from, to, { recursive: true });
