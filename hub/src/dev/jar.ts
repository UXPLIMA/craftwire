import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { inflateRawSync } from "node:zlib";

/** One entry of a zip/jar, or undefined when absent. Stored and deflated entries; no zip64 (plugin jars are small). */
export function readZipEntry(file: string, entryName: string): Buffer | undefined {
  const buf = readFileSync(file);
  const eocd = findEndOfCentralDirectory(buf);
  if (eocd < 0) throw new Error(`${file} is not a zip file`);
  const count = buf.readUInt16LE(eocd + 10);
  let p = buf.readUInt32LE(eocd + 16);
  for (let n = 0; n < count; n++) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error(`${file}: corrupt central directory`);
    const method = buf.readUInt16LE(p + 10);
    const size = buf.readUInt32LE(p + 20);
    const nameLen = buf.readUInt16LE(p + 28);
    const extraLen = buf.readUInt16LE(p + 30);
    const commentLen = buf.readUInt16LE(p + 32);
    const local = buf.readUInt32LE(p + 42);
    if (buf.toString("utf8", p + 46, p + 46 + nameLen) === entryName) {
      // Sizes come from the central directory: jars written with data descriptors leave them 0 in the local header.
      const start = local + 30 + buf.readUInt16LE(local + 26) + buf.readUInt16LE(local + 28);
      const data = buf.subarray(start, start + size);
      if (method === 0) return Buffer.from(data);
      if (method === 8) return inflateRawSync(data);
      throw new Error(`${file}: unsupported compression ${method} for ${entryName}`);
    }
    p += 46 + nameLen + extraLen + commentLen;
  }
  return undefined;
}

function findEndOfCentralDirectory(buf: Buffer): number {
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65557); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) return i;
  }
  return -1;
}

export interface PluginJarInfo {
  name: string;
  version?: string;
  descriptor: "paper-plugin.yml" | "plugin.yml";
}

/** Name and version from a jar's paper-plugin.yml or plugin.yml; undefined when the file is not a plugin jar. */
export function pluginInfoOfJar(file: string): PluginJarInfo | undefined {
  for (const descriptor of ["paper-plugin.yml", "plugin.yml"] as const) {
    let entry: Buffer | undefined;
    try {
      entry = readZipEntry(file, descriptor);
    } catch {
      return undefined;
    }
    if (!entry) continue;
    const text = entry.toString("utf8");
    const name = yamlScalar(text, "name");
    if (!name) return undefined;
    const version = yamlScalar(text, "version");
    return version === undefined ? { name, descriptor } : { name, version, descriptor };
  }
  return undefined;
}

function yamlScalar(text: string, key: string): string | undefined {
  const m = new RegExp(`^${key}:[ \\t]*(.*)$`, "m").exec(text);
  if (!m) return undefined;
  const raw = m[1]!.replace(/\s+#.*$/, "").trim();
  const value = raw.replace(/^(['"])(.*)\1$/, "$2").trim();
  return value === "" ? undefined : value;
}

/** Top-level jars in `pluginsDir` whose plugin name equals `name`, ignoring case. */
export function pluginJarsNamed(pluginsDir: string, name: string): string[] {
  if (!existsSync(pluginsDir)) return [];
  const want = name.toLowerCase();
  return readdirSync(pluginsDir, { withFileTypes: true })
    .filter((e) => e.isFile() && e.name.toLowerCase().endsWith(".jar"))
    .map((e) => join(pluginsDir, e.name))
    .filter((f) => pluginInfoOfJar(f)?.name.toLowerCase() === want)
    .sort();
}
