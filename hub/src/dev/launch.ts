import { spawn } from "node:child_process";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { createServer } from "node:net";
import { isAbsolute, join, resolve } from "node:path";
import { CraftwireError } from "../errors.js";

export const MIN_JAVA = 25;
export const DEFAULT_JVM_ARGS = ["-Xms1G", "-Xmx2G"];
export const EULA_HINT =
  "Craftwire never accepts the Minecraft EULA for you. Ask the user to read https://aka.ms/MinecraftEULA and, if they agree, set eula=true in eula.txt themselves.";

const SCRIPTS = process.platform === "win32"
  ? ["start.bat", "start.cmd", "run.bat", "start.sh", "run.sh"]
  : ["start.sh", "run.sh", "start.bat", "start.cmd", "run.bat"];
const JAVA_EXE = /^(?:.*[\\/])?javaw?(?:\.exe)?$/i;

/** Splits a command line on whitespace, honouring single and double quotes. */
export function tokenize(line: string): string[] {
  const out: string[] = [];
  let cur = "";
  let quote: string | undefined;
  let has = false;
  for (const ch of line) {
    if (quote) {
      if (ch === quote) quote = undefined;
      else cur += ch;
      continue;
    }
    if (ch === '"' || ch === "'") {
      quote = ch;
      has = true;
    } else if (/\s/.test(ch)) {
      if (has) out.push(cur);
      cur = "";
      has = false;
    } else {
      cur += ch;
      has = true;
    }
  }
  if (has) out.push(cur);
  return out;
}

export interface ScriptLaunch {
  java: string;
  jvmArgs: string[];
  jar: string;
  serverArgs: string[];
}

/** The first `java … -jar X` line of a start script; undefined when there is none or it uses variables. */
export function parseStartScript(text: string): ScriptLaunch | undefined {
  const joined = text.replace(/\^\r?\n/g, " ").replace(/\\\r?\n/g, " ");
  for (const raw of joined.split(/\r?\n/)) {
    const line = raw.trim();
    if (line === "" || /^(?:::|rem\b|#|@?echo\b)/i.test(line)) continue;
    const tokens = tokenize(line);
    const at = tokens.findIndex((t) => JAVA_EXE.test(t));
    if (at === -1) continue;
    const jarAt = tokens.indexOf("-jar", at + 1);
    if (jarAt === -1 || jarAt + 1 >= tokens.length) continue;
    if (tokens.slice(at).some((t) => /[%$]/.test(t))) return undefined;
    return { java: tokens[at]!, jvmArgs: tokens.slice(at + 1, jarAt), jar: tokens[jarAt + 1]!, serverArgs: tokens.slice(jarAt + 2) };
  }
  return undefined;
}

export interface LaunchParams {
  java?: string;
  jvmArgs?: string[];
  jar?: string;
}

export interface Launch {
  command: string;
  args: string[];
  jar: string;
  /** Where the JVM flags came from: "jvmArgs", a script file name, or "defaults". */
  source: string;
}

/** The java command for a server folder: parameters first, then its start script, then defaults. */
export function resolveLaunch(serverDir: string, p: LaunchParams = {}): Launch {
  const script = SCRIPTS.find((s) => existsSync(join(serverDir, s)));
  const parsed = script ? parseStartScript(readFileSync(join(serverDir, script), "utf8")) : undefined;
  const jarName = p.jar ?? parsed?.jar ?? defaultJar(serverDir);
  if (!jarName) throw new CraftwireError("SERVER_JAR_NOT_FOUND", `No server jar in ${serverDir}`, "Put the Paper jar there as server.jar, or pass jar.");
  const jar = resolve(serverDir, jarName);
  if (!existsSync(jar)) throw new CraftwireError("SERVER_JAR_NOT_FOUND", `${jar} does not exist`, "Check the jar name, or pass jar.");
  const jvm = p.jvmArgs ?? parsed?.jvmArgs ?? DEFAULT_JVM_ARGS;
  // Windows JVMs write a pipe in the ANSI code page; the hub decodes UTF-8.
  const encoding = jvm.some((a) => a.startsWith("-Dstdout.encoding")) ? [] : ["-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8"];
  const serverArgs = (parsed?.serverArgs ?? []).filter((a) => a !== "nogui" && a !== "--nogui");
  let command = p.java ?? parsed?.java ?? "java";
  if (/[\\/]/.test(command) && !isAbsolute(command)) command = resolve(serverDir, command);
  return {
    command,
    args: [...jvm, ...encoding, "-jar", jar, ...serverArgs, "--nogui"],
    jar,
    source: p.jvmArgs ? "jvmArgs" : parsed ? script! : "defaults",
  };
}

function defaultJar(dir: string): string | undefined {
  if (existsSync(join(dir, "server.jar"))) return "server.jar";
  if (!existsSync(dir)) return undefined;
  const papers = readdirSync(dir).filter((f) => /^paper.*\.jar$/i.test(f));
  return papers.length === 1 ? papers[0] : undefined;
}

/** Major version from `java -version` output ("1.8" style included). */
export function javaMajorVersion(text: string): number | undefined {
  const m = /version "(\d+)(?:\.(\d+))?/.exec(text);
  if (!m) return undefined;
  return m[1] === "1" && m[2] ? Number(m[2]) : Number(m[1]);
}

export function probeJavaMajor(java: string): Promise<number> {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(java, ["-version"], { windowsHide: true });
    let out = "";
    child.stdout.on("data", (d) => (out += d));
    child.stderr.on("data", (d) => (out += d));
    child.once("error", () => reject(new CraftwireError("JAVA_NOT_FOUND", `Cannot run ${java}`, `Install Java ${MIN_JAVA}+ or pass java: the full path to a java executable.`)));
    child.once("close", () => {
      const major = javaMajorVersion(out);
      if (major === undefined) reject(new CraftwireError("JAVA_NOT_FOUND", `${java} -version printed no version`, `Pass java: the full path to a Java ${MIN_JAVA}+ executable.`));
      else resolvePromise(major);
    });
  });
}

export function eulaAccepted(serverDir: string): boolean {
  const file = join(serverDir, "eula.txt");
  return existsSync(file) && /^\s*eula\s*=\s*true\s*$/im.test(readFileSync(file, "utf8"));
}

/** server.properties as a map, or undefined when the server has never run. */
export function serverProperties(serverDir: string): Map<string, string> | undefined {
  const file = join(serverDir, "server.properties");
  if (!existsSync(file)) return undefined;
  const props = new Map<string, string>();
  for (const line of readFileSync(file, "utf8").split(/\r?\n/)) {
    const eq = line.indexOf("=");
    if (eq > 0 && !line.startsWith("#")) props.set(line.slice(0, eq).trim(), line.slice(eq + 1).trim());
  }
  return props;
}

/** Where a client joins this server, and whether it demands Microsoft accounts (online-mode, the default). */
export function serverAddress(serverDir: string): { host: string; port: number; onlineMode: boolean } {
  const props = serverProperties(serverDir) ?? new Map<string, string>();
  return {
    host: props.get("server-ip") || "127.0.0.1",
    port: Number(props.get("server-port") || 25565),
    onlineMode: (props.get("online-mode") ?? "true").toLowerCase() !== "false",
  };
}

/**
 * The port from server.properties when something already listens on it, else undefined. A server folder that has
 * never run has no server.properties and cannot be running. Spots servers running without the Craftwire plugin.
 */
export async function serverPortInUse(serverDir: string): Promise<number | undefined> {
  const props = serverProperties(serverDir);
  if (!props) return undefined;
  const port = Number(props.get("server-port") || 25565);
  if (!Number.isInteger(port) || port <= 0 || port > 65535) return undefined;
  const host = props.get("server-ip") || undefined;
  const busy = await new Promise<boolean>((done) => {
    const probe = createServer();
    probe.once("error", (e: NodeJS.ErrnoException) => done(e.code === "EADDRINUSE" || e.code === "EACCES"));
    probe.listen(port, host, () => probe.close(() => done(false)));
  });
  return busy ? port : undefined;
}
