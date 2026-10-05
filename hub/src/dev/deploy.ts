import { spawn } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync, readdirSync, renameSync, statSync } from "node:fs";
import { basename, delimiter, dirname, join, resolve } from "node:path";
import { createInterface } from "node:readline";
import type { AgentServer } from "../agents.js";
import { CraftwireError } from "../errors.js";
import { RingBuffer } from "../ringbuffer.js";
import { groupLogs, type LogLine, rank } from "../tools/log-tools.js";
import { parseBuildErrors } from "./build-errors.js";
import { type PluginJarInfo, pluginInfoOfJar, pluginJarsNamed } from "./jar.js";
import { samePath } from "./paths.js";
import { notManagedError, type ServerManager } from "./server-manager.js";

export interface DetectedBuild {
  tool: "gradle" | "maven";
  command: string;
}

/** Gradle (wrapper first) or Maven, packaging without running tests. */
export function detectBuild(projectDir: string, platform: NodeJS.Platform = process.platform): DetectedBuild | undefined {
  const has = (f: string) => existsSync(join(projectDir, f));
  const win = platform === "win32";
  const gradlew = win ? (has("gradlew.bat") ? "gradlew.bat" : undefined) : has("gradlew") ? "sh ./gradlew" : undefined;
  if (gradlew) return { tool: "gradle", command: `${gradlew} build -x test --console=plain` };
  if (has("build.gradle") || has("build.gradle.kts")) return { tool: "gradle", command: "gradle build -x test --console=plain" };
  const mvnw = win ? (has("mvnw.cmd") ? "mvnw.cmd" : undefined) : has("mvnw") ? "sh ./mvnw" : undefined;
  if (mvnw) return { tool: "maven", command: `${mvnw} -B package -DskipTests` };
  if (has("pom.xml")) return { tool: "maven", command: "mvn -B package -DskipTests" };
  return undefined;
}

export interface BuildOutcome {
  command: string;
  exitCode: number | null;
  ms: number;
  timedOut: boolean;
  output: string[];
}

/** Runs `command` in a shell in `projectDir`; keeps the last 5000 output lines. */
export function runBuild(projectDir: string, command: string, o: { timeoutMs: number; javaHome?: string }): Promise<BuildOutcome> {
  const env = { ...process.env };
  if (o.javaHome) {
    env.JAVA_HOME = o.javaHome;
    const pathVar = Object.keys(env).find((k) => k.toUpperCase() === "PATH") ?? "PATH";
    env[pathVar] = join(o.javaHome, "bin") + delimiter + (env[pathVar] ?? "");
  }
  const started = Date.now();
  const output = new RingBuffer<string>(5000);
  return new Promise((resolvePromise) => {
    // POSIX: own process group so a timeout can kill the whole tree.
    const child = spawn(command, { cwd: projectDir, env, shell: true, windowsHide: true, detached: process.platform !== "win32" });
    for (const stream of [child.stdout, child.stderr]) {
      createInterface({ input: stream, crlfDelay: Infinity }).on("line", (l) => output.push(l));
    }
    let timedOut = false;
    const timer = setTimeout(() => { timedOut = true; killTree(child.pid); }, o.timeoutMs);
    child.once("error", (e) => output.push(`[craftwire] ${e.message}`));
    child.once("close", (code) => {
      clearTimeout(timer);
      resolvePromise({ command, exitCode: code, ms: Date.now() - started, timedOut, output: output.toArray() });
    });
  });
}

export function killTree(pid: number | undefined): void {
  if (pid === undefined) return;
  if (process.platform === "win32") {
    spawn("taskkill", ["/pid", String(pid), "/T", "/F"], { windowsHide: true, stdio: "ignore" });
    return;
  }
  try {
    process.kill(-pid, "SIGKILL");
  } catch {
    // already gone
  }
}

const SKIP_DIRS = new Set(["node_modules", ".git", ".gradle", ".idea"]);

/** `*` and `?` stay inside one path segment; `**` spans segments. Matched against '/'-separated relative paths. */
export function globToRegExp(pattern: string): RegExp {
  const p = pattern.replace(/\\/g, "/");
  let re = "";
  for (let i = 0; i < p.length; i++) {
    const ch = p[i]!;
    if (ch === "*" && p[i + 1] === "*") {
      const slash = p[i + 2] === "/";
      re += slash ? "(?:.*/)?" : ".*";
      i += slash ? 2 : 1;
    } else if (ch === "*") re += "[^/]*";
    else if (ch === "?") re += "[^/]";
    else re += ch.replace(/[.+^${}()|[\]\\]/g, "\\$&");
  }
  return new RegExp(`^${re}$`, process.platform === "win32" ? "i" : "");
}

export function expandGlob(baseDir: string, pattern: string, maxDepth = 6): string[] {
  const re = globToRegExp(pattern);
  const out: string[] = [];
  const walk = (dir: string, rel: string, depth: number) => {
    let entries;
    try {
      entries = readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entries) {
      const r = rel ? `${rel}/${e.name}` : e.name;
      if (e.isDirectory()) {
        if (depth < maxDepth && !SKIP_DIRS.has(e.name)) walk(join(dir, e.name), r, depth + 1);
      } else if (re.test(r)) out.push(join(dir, e.name));
    }
  };
  walk(baseDir, "", 0);
  return out.sort();
}

export const DEFAULT_JAR_GLOBS = ["build/libs/*.jar", "*/build/libs/*.jar", "target/*.jar", "*/target/*.jar"];
const NOT_PLUGIN_JAR = /(?:-sources|-javadoc|-plain)\.jar$|^original-/i;

/** The built plugin jar: newest among jars carrying a plugin descriptor; several plugins need jarGlob. */
export function findBuiltJar(projectDir: string, jarGlob?: string): { path: string; plugin: PluginJarInfo } {
  const globs = jarGlob ? [jarGlob] : DEFAULT_JAR_GLOBS;
  const files = [...new Set(globs.flatMap((g) => expandGlob(projectDir, g)))].filter((f) => !NOT_PLUGIN_JAR.test(basename(f)));
  const plugins = files.flatMap((path) => {
    const plugin = pluginInfoOfJar(path);
    if (!plugin) return [];
    const st = statSync(path);
    return [{ path, plugin, mtime: st.mtimeMs, size: st.size }];
  });
  if (plugins.length === 0) {
    throw new CraftwireError("JAR_NOT_FOUND", `No plugin jar found in ${projectDir}`, "Pass jarGlob (relative to projectDir), e.g. 'build/libs/*-all.jar'.", { looked: globs, jars: files });
  }
  const names = [...new Set(plugins.map((p) => p.plugin.name))];
  if (names.length > 1) {
    throw new CraftwireError("AMBIGUOUS_JAR", `Jars of ${names.length} plugins were found: ${names.join(", ")}`, "Pass jarGlob to pick one, e.g. 'my-plugin/build/libs/*.jar'.",
      { jars: plugins.map((p) => ({ path: p.path, plugin: p.plugin.name })) });
  }
  plugins.sort((a, b) => b.mtime - a.mtime || b.size - a.size);
  return { path: plugins[0]!.path, plugin: plugins[0]!.plugin };
}

export interface InstallResult {
  installed: string;
  replaced: string[];
  backupDir?: string;
  needsRestart: boolean;
}

/**
 * Puts `jar` into serverDir/plugins, moving older jars of the same plugin to plugins/.craftwire-backup/.
 * While the server runs its jars are open (locked on Windows), so the jar is staged in plugins/update/
 * under the old jar's name, which Paper swaps in on the next start.
 */
export function installPluginJar(serverDir: string, jar: string, plugin: PluginJarInfo, o: { running: boolean }): InstallResult {
  const pluginsDir = join(serverDir, "plugins");
  mkdirSync(pluginsDir, { recursive: true });
  const existing = pluginJarsNamed(pluginsDir, plugin.name).filter((p) => !samePath(p, jar));
  if (o.running) {
    const target = existing.length ? join(pluginsDir, "update", basename(existing[0]!)) : join(pluginsDir, basename(jar));
    mkdirSync(dirname(target), { recursive: true });
    copyFileSync(jar, target);
    return { installed: target, replaced: [], needsRestart: true };
  }
  const installed = join(pluginsDir, basename(jar));
  if (existing.length === 0) {
    if (!samePath(installed, jar)) copyFileSync(jar, installed);
    return { installed, replaced: [], needsRestart: false };
  }
  const backupDir = join(pluginsDir, ".craftwire-backup");
  mkdirSync(backupDir, { recursive: true });
  for (const old of existing) renameSync(old, join(backupDir, basename(old)));
  copyFileSync(jar, installed);
  return { installed, replaced: existing, backupDir, needsRestart: false };
}

/** WARN and worse lines logged by the plugin or naming it. */
export function pluginProblems(lines: LogLine[], name: string, limit = 20): LogLine[] {
  const n = name.toLowerCase();
  return lines
    .filter((l) => rank(l.level) >= rank("WARN"))
    .filter((l) => l.logger.toLowerCase() === n || `${l.message}\n${l.thrown ?? ""}`.toLowerCase().includes(n))
    .slice(-limit);
}

export interface DeployArgs {
  projectDir?: string;
  jar?: string;
  buildCommand?: string;
  jarGlob?: string;
  javaHome?: string;
  serverDir?: string;
  restart: boolean;
  takeOver: boolean;
  buildTimeoutMs: number;
  timeoutMs: number;
}

export async function deploy(a: DeployArgs, servers: ServerManager, agents: AgentServer): Promise<Record<string, unknown>> {
  if ((a.projectDir === undefined) === (a.jar === undefined)) {
    throw new CraftwireError("INVALID_PARAMS", "Pass exactly one of projectDir or jar", "projectDir builds the project first; jar installs a ready jar.");
  }
  const serverDir = servers.resolveDir(a.serverDir);
  const before = servers.runningState(serverDir);
  // Refuse before building: a long build that ends in NOT_MANAGED wastes minutes.
  if (before === "external" && a.restart && !a.takeOver) throw notManagedError(servers.external(serverDir)!);

  const result: Record<string, unknown> = { serverDir };
  let jarPath: string;
  let plugin: PluginJarInfo;
  if (a.projectDir !== undefined) {
    const projectDir = resolve(a.projectDir);
    if (!existsSync(projectDir)) throw new CraftwireError("PROJECT_NOT_FOUND", `${projectDir} does not exist`, "Pass projectDir: the plugin project's root folder.");
    const command = a.buildCommand ?? detectBuild(projectDir)?.command;
    if (!command) throw new CraftwireError("BUILD_NOT_DETECTED", `No Gradle or Maven build in ${projectDir}`, "Pass buildCommand, e.g. 'gradlew.bat shadowJar'.");
    const build = await runBuild(projectDir, command, { timeoutMs: a.buildTimeoutMs, ...(a.javaHome ? { javaHome: a.javaHome } : {}) });
    if (build.timedOut) {
      throw new CraftwireError("BUILD_FAILED", `${command} did not finish within ${a.buildTimeoutMs} ms`, "Raise buildTimeoutMs, or run the build once by hand to warm the caches.", { command, outputTail: build.output.slice(-60) });
    }
    if (build.exitCode !== 0) {
      const errors = parseBuildErrors(build.output);
      throw new CraftwireError("BUILD_FAILED",
        errors.length ? `Build failed with ${errors.length} compiler error(s)` : `Build failed (exit code ${build.exitCode})`,
        errors.length ? "Fix each file:line in details.errors and deploy again." : "No compiler errors were recognised; read details.outputTail.",
        { command, exitCode: build.exitCode, errors, outputTail: build.output.slice(-60) });
    }
    const built = findBuiltJar(projectDir, a.jarGlob);
    jarPath = built.path;
    plugin = built.plugin;
    result.build = { command, ms: build.ms };
  } else {
    jarPath = resolve(a.jar!);
    if (!existsSync(jarPath)) throw new CraftwireError("JAR_NOT_FOUND", `${jarPath} does not exist`, "Pass the path of a built plugin jar.");
    const info = pluginInfoOfJar(jarPath);
    if (!info) throw new CraftwireError("NOT_A_PLUGIN", `${jarPath} has no plugin.yml or paper-plugin.yml`, "Pick the plugin jar (for shaded builds usually the -all jar).");
    plugin = info;
  }
  result.jar = jarPath;
  result.plugin = plugin.version === undefined ? { name: plugin.name } : { name: plugin.name, version: plugin.version };

  if (!a.restart) {
    const install = installPluginJar(serverDir, jarPath, plugin, { running: before !== "none" });
    return { ...result, install, restarted: false, ...(install.needsRestart ? { hint: "Restart the server (server_process {action:'restart'}) to load the new jar." } : {}) };
  }

  let install: InstallResult | undefined;
  const status = await servers.restart({ serverDir, timeoutMs: a.timeoutMs, takeOver: a.takeOver }, async () => {
    install = installPluginJar(serverDir, jarPath, plugin, { running: false });
  });
  result.install = install;
  result.restarted = true;
  result.server = { state: status.state, agent: status.agent ?? null, readyMs: status.readyMs };
  if (status.warning) result.warning = status.warning;
  if (status.agent) {
    const loaded = await agents.request(status.agent, "plugin.manage", { action: "info", name: plugin.name })
      .catch((e: unknown) => ({ error: e instanceof CraftwireError ? e.toJSON() : String(e) }));
    result.loaded = loaded;
    result.problems = pluginProblems(groupLogs(agents.events(status.agent)), plugin.name);
    if ((loaded as { enabled?: boolean }).enabled !== true) result.hint = "The jar is installed but the plugin is not enabled: read problems, or logs {level:'WARN'}.";
  }
  return result;
}
