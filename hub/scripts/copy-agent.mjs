// Packs the agent jars into the npm package: the Fabric mod (client_process starts clients with the agent that matches
// this hub), the bot runner (botrunner_process runs it), the Paper plugin (plugin_deploy {jar} and the Docker images)
// and the Velocity plugin (for a proxy's plugins folder).
import { copyFileSync, existsSync, mkdirSync, readFileSync, rmSync } from "node:fs";
import { fileURLToPath } from "node:url";

const { version } = JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf8"));
const jars = [
  { name: `craftwire-agent-fabric-${version}.jar`, dir: "agent-fabric", task: ":agent-fabric:build" },
  { name: `craftwire-botrunner-${version}.jar`, dir: "botrunner", task: ":botrunner:jar" },
  { name: `craftwire-paper-${version}.jar`, dir: "agent-paper", task: ":agent-paper:jar" },
  { name: `craftwire-velocity-${version}.jar`, dir: "agent-velocity", task: ":agent-velocity:jar" },
];
const to = fileURLToPath(new URL("../agent/", import.meta.url));
const missing = jars.filter((j) => !existsSync(fileURLToPath(new URL(`../../${j.dir}/build/libs/${j.name}`, import.meta.url))));
if (missing.length > 0) {
  for (const j of missing) console.error(`${j.dir}/build/libs/${j.name} is missing: run ./gradlew ${j.task} first.`);
  process.exit(1);
}
rmSync(to, { recursive: true, force: true });
mkdirSync(to, { recursive: true });
for (const j of jars) copyFileSync(fileURLToPath(new URL(`../../${j.dir}/build/libs/${j.name}`, import.meta.url)), `${to}${j.name}`);
