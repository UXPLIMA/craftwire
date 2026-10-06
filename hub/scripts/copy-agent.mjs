// Packs the agent mod into the npm package, so client_process starts clients with the agent that matches this hub.
import { copyFileSync, existsSync, mkdirSync, readFileSync, rmSync } from "node:fs";
import { fileURLToPath } from "node:url";

const { version } = JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf8"));
const name = `craftwire-agent-fabric-${version}.jar`;
const from = fileURLToPath(new URL(`../../agent-fabric/build/libs/${name}`, import.meta.url));
const to = fileURLToPath(new URL("../agent/", import.meta.url));
if (!existsSync(from)) {
  console.error(`${from} is missing: run ./gradlew :agent-fabric:build first.`);
  process.exit(1);
}
rmSync(to, { recursive: true, force: true });
mkdirSync(to, { recursive: true });
copyFileSync(from, `${to}${name}`);
