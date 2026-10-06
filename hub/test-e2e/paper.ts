import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { createServer } from "node:net";
import { join } from "node:path";

export const repoRoot = join(__dirname, "..", "..");

function readProps(file: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const line of readFileSync(file, "utf8").split(/\r?\n/)) {
    const m = /^([\w.]+)=(.*)$/.exec(line.trim());
    if (m) out[m[1]!] = m[2]!;
  }
  return out;
}

/**
 * gradle.properties plus versions/<mc>.properties of the Minecraft version under test:
 * CRAFTWIRE_E2E_MC (e.g. 26.3), default the oldest supported, like the Java integration tests.
 */
export function gradleProperties(): Record<string, string> {
  const base = readProps(join(repoRoot, "gradle.properties"));
  const mc = process.env.CRAFTWIRE_E2E_MC ?? base.mc_versions!.split(",")[0]!.trim();
  return { ...base, ...readProps(join(repoRoot, "versions", `${mc}.properties`)) };
}

const sha256 = (b: Buffer) => createHash("sha256").update(b).digest("hex");
const AGENT = "craftwire-e2e-tests (https://github.com/uxplima/craftwire)";

/** The Paper build pinned for the version under test (same pin as the Java integration tests), checksum-verified. */
export async function ensurePaper(cacheDir: string, props: Record<string, string>): Promise<string> {
  const mc = props.minecraft_version!;
  const build = props.paper_build!;
  const sum = props.paper_sha256!;
  const jar = join(cacheDir, `paper-${mc}-${build}.jar`);
  if (existsSync(jar) && sha256(readFileSync(jar)) === sum) return jar;
  mkdirSync(cacheDir, { recursive: true });
  const metaRes = await fetch(`https://fill.papermc.io/v3/projects/paper/versions/${mc}/builds/${build}`, { headers: { "User-Agent": AGENT } });
  const meta = (await metaRes.json()) as { downloads: Record<string, { url: string }> };
  const body = Buffer.from(await (await fetch(meta.downloads["server:default"]!.url, { headers: { "User-Agent": AGENT } })).arrayBuffer());
  if (sha256(body) !== sum) throw new Error(`Paper jar checksum mismatch: ${sha256(body)}`);
  writeFileSync(`${jar}.part`, body);
  renameSync(`${jar}.part`, jar);
  return jar;
}

export function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const s = createServer();
    s.once("error", reject);
    s.listen(0, "127.0.0.1", () => {
      const port = (s.address() as { port: number }).port;
      s.close(() => resolve(port));
    });
  });
}
