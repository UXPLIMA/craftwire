import { type ChildProcess, spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { join, resolve } from "node:path";
import { createInterface } from "node:readline";
import { setTimeout as sleep } from "node:timers/promises";
import type { AgentServer, InstanceInfo } from "../agents.js";
import { CraftwireError } from "../errors.js";
import { RingBuffer } from "../ringbuffer.js";
import { pluginJarsNamed } from "./jar.js";
import { EULA_HINT, eulaAccepted, type Launch, type LaunchParams, MIN_JAVA, probeJavaMajor, resolveLaunch } from "./launch.js";
import { pathKey, samePath } from "./paths.js";

export type ServerState = "starting" | "running" | "stopping" | "stopped" | "crashed";

export interface StartOptions extends LaunchParams {
  serverDir: string;
  timeoutMs?: number;
}

export type RestartOptions = Partial<StartOptions> & { takeOver?: boolean };

export interface ServerStatus {
  serverDir: string;
  state: ServerState;
  pid?: number;
  startedAt?: number;
  readyMs?: number;
  exitCode?: number | null;
  agent?: string | null;
  launch?: Launch;
  warning?: string;
  consoleTail: string[];
}

export interface ExternalServer {
  instance: string;
  name: string;
  serverDir: string;
  pid?: number;
}

export interface StopResult {
  serverDir: string;
  stopped: true;
  external: boolean;
  forced: boolean;
  exitCode?: number | null;
}

export interface ServerManagerOptions {
  agents: AgentServer;
  /** Passed to the server as CRAFTWIRE_HOME so its plugin finds this hub's hub.json. */
  home: string;
  javaMajor?: (java: string) => Promise<number>;
  agentWaitMs?: number;
  stopTimeoutMs?: number;
}

interface Managed {
  dir: string;
  options: StartOptions;
  state: ServerState;
  console: RingBuffer<string>;
  child?: ChildProcess;
  exited?: Promise<number | null>;
  startedAt?: number;
  readyMs?: number;
  exitCode?: number | null;
  agent?: string | null;
  onAgent?: () => void;
  launch?: Launch;
  warning?: string;
}

const DONE = /\bDone \([\d.,]+s\)!/;
const LIVE: readonly ServerState[] = ["starting", "running", "stopping"];
const isLive = (m: Managed | undefined): m is Managed => m !== undefined && LIVE.includes(m.state);

const CRASHES: [RegExp, string, string][] = [
  [/FAILED TO BIND TO PORT/i, "PORT_IN_USE", "Another process uses the server port: stop it, or change server-port in server.properties."],
  [/session\.lock|already locked/i, "WORLD_LOCKED", "Another server is using this world: stop it first (server_process {action:'status'} lists external servers)."],
  [/UnsupportedClassVersionError|requires running the server with Java/i, "JAVA_TOO_OLD", `Paper 26.x needs Java ${MIN_JAVA}+: pass java with the path to a newer Java.`],
  [/agree to the EULA/i, "EULA_NOT_ACCEPTED", EULA_HINT],
];

export function diagnoseCrash(lines: string[]): { code: string; hint: string } {
  const text = lines.join("\n");
  for (const [re, code, hint] of CRASHES) if (re.test(text)) return { code, hint };
  return { code: "SERVER_CRASHED", hint: "Read consoleTail (and logs/latest.log in the server folder) for the cause." };
}

export function definedOnly<T extends object>(o: T): Partial<T> {
  return Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined)) as Partial<T>;
}

export function pidAlive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch (e) {
    return (e as NodeJS.ErrnoException).code === "EPERM";
  }
}

/** Kills a process and its children (taskkill /T on Windows, the process group elsewhere). */
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

export async function waitUntil(check: () => boolean, ms: number, everyMs = 250): Promise<boolean> {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (check()) return true;
    await sleep(everyMs);
  }
  return check();
}

export function notManagedError(ext: ExternalServer): CraftwireError {
  return new CraftwireError("NOT_MANAGED",
    `The server in ${ext.serverDir} was started outside Craftwire (${ext.instance}${ext.pid !== undefined ? `, pid ${ext.pid}` : ""})`,
    "Ask the user first; then pass takeOver:true to stop it (it runs under the hub after a restart).");
}

async function race<T extends string>(ps: Promise<T>[], ms: number, onTimeout: T): Promise<T> {
  let timer: NodeJS.Timeout | undefined;
  const timeout = new Promise<T>((r) => { timer = setTimeout(() => r(onTimeout), ms); });
  try {
    return await Promise.race([...ps, timeout]);
  } finally {
    clearTimeout(timer);
  }
}

/** Runs local Paper servers for the hub and recognises servers started elsewhere by their agent's serverDir. */
export class ServerManager {
  private readonly servers = new Map<string, Managed>();
  private queue: Promise<unknown> = Promise.resolve();

  constructor(private readonly opts: ServerManagerOptions) {
    opts.agents.on("connected", (i: InstanceInfo) => {
      if (i.kind !== "server" || i.serverDir === undefined) return;
      const m = this.servers.get(pathKey(i.serverDir));
      if (!isLive(m)) return;
      m.agent = i.id;
      m.onAgent?.();
    });
    opts.agents.on("disconnected", (i: InstanceInfo) => {
      for (const m of this.servers.values()) if (m.agent === i.id) m.agent = null;
    });
  }

  start(o: StartOptions): Promise<ServerStatus> {
    return this.exclusive(() => this.doStart(o));
  }

  stop(serverDir?: string, o: { takeOver?: boolean } = {}): Promise<StopResult> {
    return this.exclusive(() => this.doStop(this.resolveDir(serverDir), o.takeOver ?? false));
  }

  /** Stops the server if it runs, calls `between` while it is down, then starts it with the previous options. */
  restart(o: RestartOptions, between?: () => Promise<void>): Promise<ServerStatus> {
    return this.exclusive(async () => {
      const { takeOver, ...start } = o;
      const dir = this.resolveDir(start.serverDir);
      if (this.runningState(dir) !== "none") await this.doStop(dir, takeOver ?? false);
      await between?.();
      return this.doStart({ ...start, serverDir: dir });
    });
  }

  status(serverDir?: string, tail = 20): { servers: ServerStatus[]; external: ExternalServer[] } {
    const keep = (dir: string) => serverDir === undefined || samePath(dir, serverDir);
    return {
      servers: [...this.servers.values()].filter((m) => keep(m.dir)).map((m) => this.snapshot(m, tail)),
      external: this.externals().filter((e) => keep(e.serverDir)),
    };
  }

  runningState(serverDir: string): "managed" | "external" | "none" {
    if (isLive(this.servers.get(pathKey(serverDir)))) return "managed";
    return this.external(serverDir) ? "external" : "none";
  }

  external(serverDir: string): ExternalServer | undefined {
    return this.externals().find((e) => samePath(e.serverDir, serverDir));
  }

  externals(): ExternalServer[] {
    return this.opts.agents.instances()
      .filter((i): i is InstanceInfo & { serverDir: string } => i.kind === "server" && i.serverDir !== undefined)
      .filter((i) => !isLive(this.servers.get(pathKey(i.serverDir))))
      .map((i) => ({ instance: i.id, name: i.name, serverDir: i.serverDir, ...(i.pid !== undefined ? { pid: i.pid } : {}) }));
  }

  /** The explicit folder, else the only running (managed or external) server, else the only one ever started. */
  resolveDir(serverDir?: string): string {
    if (serverDir) return resolve(serverDir);
    const running = [
      ...[...this.servers.values()].filter(isLive).map((m) => m.dir),
      ...this.externals().map((e) => resolve(e.serverDir)),
    ];
    const pool = running.length ? running : [...this.servers.values()].map((m) => m.dir);
    if (pool.length === 1) return pool[0]!;
    if (pool.length === 0) throw new CraftwireError("INVALID_PARAMS", "No server is known yet", "Pass serverDir: the folder that contains the Paper jar.");
    throw new CraftwireError("AMBIGUOUS_SERVER", `${pool.length} servers are known`, `Pass serverDir: one of ${pool.join(", ")}.`);
  }

  /** Stops every server this hub started; called when the hub exits. */
  async shutdown(): Promise<void> {
    const live = [...this.servers.values()].filter(isLive);
    await Promise.all(live.map((m) => this.doStop(m.dir, false).catch(() => undefined)));
  }

  private exclusive<T>(fn: () => Promise<T>): Promise<T> {
    const run = this.queue.then(fn, fn);
    this.queue = run.catch(() => undefined);
    return run;
  }

  private async doStart(o: StartOptions): Promise<ServerStatus> {
    const dir = resolve(o.serverDir);
    const opts: StartOptions = { ...this.servers.get(pathKey(dir))?.options, ...definedOnly(o), serverDir: dir };
    if (!existsSync(dir)) throw new CraftwireError("SERVER_DIR_NOT_FOUND", `${dir} does not exist`, "Pass serverDir: the folder that contains the Paper jar.");
    const state = this.runningState(dir);
    if (state === "managed") throw new CraftwireError("SERVER_ALREADY_RUNNING", `The server in ${dir} is already running`, "Use server_process {action:'status'} or {action:'restart'}.");
    if (state === "external") {
      const ext = this.external(dir)!;
      throw new CraftwireError("SERVER_ALREADY_RUNNING", `The server in ${dir} is already running outside Craftwire (${ext.instance})`,
        "Keep using it as it is, or (after asking the user) server_process {action:'restart', takeOver:true} to run it under the hub.");
    }
    if (!eulaAccepted(dir)) throw new CraftwireError("EULA_NOT_ACCEPTED", `The Minecraft EULA is not accepted in ${join(dir, "eula.txt")}`, EULA_HINT);
    const launch = resolveLaunch(dir, opts);
    const major = await (this.opts.javaMajor ?? probeJavaMajor)(launch.command);
    if (major < MIN_JAVA) throw new CraftwireError("JAVA_TOO_OLD", `${launch.command} is Java ${major}; Paper 26.x needs Java ${MIN_JAVA}+`, `Pass java: the path to a Java ${MIN_JAVA}+ executable.`);
    const expectAgent = pluginJarsNamed(join(dir, "plugins"), "Craftwire").length > 0;

    const m: Managed = { dir, options: opts, state: "starting", console: new RingBuffer(2000), startedAt: Date.now(), launch, agent: null };
    this.servers.set(pathKey(dir), m);
    const agentSeen = new Promise<"agent">((r) => { m.onAgent = () => r("agent"); });
    let sawDone!: () => void;
    const done = new Promise<"done">((r) => { sawDone = () => r("done"); });

    const child = spawn(launch.command, launch.args, {
      cwd: dir,
      env: { ...process.env, CRAFTWIRE_HOME: this.opts.home },
      stdio: ["pipe", "pipe", "pipe"],
      windowsHide: true,
      // POSIX: own process group so a forced stop can kill launcher and JVM together.
      detached: process.platform !== "win32",
    });
    m.child = child;
    child.stdin.on("error", () => {});
    for (const stream of [child.stdout, child.stderr]) {
      createInterface({ input: stream, crlfDelay: Infinity }).on("line", (line) => {
        m.console.push(line);
        if (DONE.test(line)) sawDone();
      });
    }
    m.exited = new Promise((res) => {
      let settled = false;
      const settle = (code: number | null) => {
        if (settled) return;
        settled = true;
        m.exitCode = code;
        m.state = m.state === "stopping" || (m.state === "running" && code === 0) ? "stopped" : "crashed";
        res(code);
      };
      child.once("error", (e) => { m.console.push(`[craftwire] cannot run ${launch.command}: ${e.message}`); settle(null); });
      // "close" (not "exit") so every console line is read before the crash is diagnosed.
      child.once("close", (code) => settle(code));
    });
    const exit = m.exited.then(() => "exit" as const);

    const first = await race<"done" | "exit" | "timeout">([done, exit], opts.timeoutMs ?? 300_000, "timeout");
    if (first === "exit") throw this.crashError(m);
    if (first === "timeout") {
      throw new CraftwireError("TIMEOUT", `The server did not finish starting within ${opts.timeoutMs ?? 300_000} ms`,
        "It is still starting: watch server_process {action:'status'} (consoleTail), or stop it.", { consoleTail: m.console.toArray().slice(-40) });
    }
    if (expectAgent && !m.agent) {
      const r = await race<"agent" | "exit" | "timeout">([agentSeen, exit], this.opts.agentWaitMs ?? 20_000, "timeout");
      if (r === "exit") throw this.crashError(m);
      if (r === "timeout") m.warning = "The Craftwire plugin is in plugins/ but did not connect to this hub; check logs/latest.log for Craftwire errors.";
    }
    if (!expectAgent) m.warning = "The Craftwire plugin is not in plugins/, so server tools and logs are unavailable here. Install it with plugin_deploy {jar:'<craftwire-paper jar>'}.";
    m.state = "running";
    m.readyMs = Date.now() - m.startedAt!;
    return this.snapshot(m, 10);
  }

  private crashError(m: Managed): CraftwireError {
    const lines = m.console.toArray();
    const { code, hint } = diagnoseCrash(lines);
    return new CraftwireError(code, `The server exited during startup (exit code ${m.exitCode})`, hint, { exitCode: m.exitCode, consoleTail: lines.slice(-40) });
  }

  private async doStop(dir: string, takeOver: boolean): Promise<StopResult> {
    const m = this.servers.get(pathKey(dir));
    if (isLive(m) && m.child && m.exited) {
      m.state = "stopping";
      m.child.stdin?.write("stop\n");
      let forced = false;
      if ((await race<"exit" | "timeout">([m.exited.then(() => "exit" as const)], this.opts.stopTimeoutMs ?? 90_000, "timeout")) === "timeout") {
        forced = true;
        // `java` may be a launcher (Oracle's javapath java.exe) whose child is the real JVM: kill the tree.
        killTree(m.child.pid);
        await m.exited;
      }
      return { serverDir: dir, stopped: true, external: false, forced, exitCode: m.exitCode ?? null };
    }
    const ext = this.external(dir);
    if (!ext) throw new CraftwireError("SERVER_NOT_RUNNING", `No server is running in ${dir}`, "Start it with server_process {action:'start'}.");
    if (!takeOver) throw notManagedError(ext);
    await this.stopExternal(ext);
    return { serverDir: dir, stopped: true, external: true, forced: false };
  }

  private async stopExternal(ext: ExternalServer): Promise<void> {
    await this.opts.agents.request(ext.instance, "server.command", { command: "stop" }, 10_000).catch(() => undefined);
    const ms = this.opts.stopTimeoutMs ?? 90_000;
    const still = "The server may still be saving; check it, then retry.";
    if (ext.pid !== undefined) {
      const pid = ext.pid;
      if (!(await waitUntil(() => !pidAlive(pid), ms))) throw new CraftwireError("TIMEOUT", `pid ${pid} is still running ${ms} ms after stop`, still);
      return;
    }
    if (!(await waitUntil(() => !this.opts.agents.instances().some((i) => i.id === ext.instance), ms))) {
      throw new CraftwireError("TIMEOUT", `${ext.instance} is still connected ${ms} ms after stop`, still);
    }
    await sleep(5000); // agents before 0.3.0 send no pid: give the JVM time to release the world and plugin jars
  }

  private snapshot(m: Managed, tail: number): ServerStatus {
    const s: ServerStatus = { serverDir: m.dir, state: m.state, consoleTail: tail > 0 ? m.console.toArray().slice(-tail) : [] };
    // The agent reports the JVM's own pid; the spawned process may only be a java launcher.
    const jvmPid = m.agent ? this.opts.agents.instances().find((i) => i.id === m.agent)?.pid : undefined;
    if (isLive(m) && (jvmPid ?? m.child?.pid) !== undefined) s.pid = jvmPid ?? m.child!.pid;
    if (m.startedAt !== undefined) s.startedAt = m.startedAt;
    if (m.readyMs !== undefined) s.readyMs = m.readyMs;
    if (m.exitCode !== undefined) s.exitCode = m.exitCode;
    if (m.agent !== undefined) s.agent = m.agent;
    if (m.launch) s.launch = m.launch;
    if (m.warning) s.warning = m.warning;
    return s;
  }
}
