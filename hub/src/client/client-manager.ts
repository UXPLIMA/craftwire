import { type ChildProcess, spawn } from "node:child_process";
import { existsSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { createInterface } from "node:readline";
import { setTimeout as sleep } from "node:timers/promises";
import type { AgentServer, InstanceInfo } from "../agents.js";
import { CraftwireError } from "../errors.js";
import { serverAddress } from "../dev/launch.js";
import { samePath } from "../dev/paths.js";
import { killTree } from "../dev/server-manager.js";
import { RingBuffer } from "../ringbuffer.js";

export type ClientState = "downloading" | "starting" | "running" | "stopping" | "stopped" | "crashed";

export interface ClientStartOptions {
  /** host:port; default: the single server running under server_process. */
  server?: string;
  username?: string;
  mods?: string[];
  visible?: boolean;
  windowSize?: { width: number; height: number };
  java?: string;
  sounds?: boolean;
  timeoutMs?: number;
}

export interface PrepareInput {
  root: string;
  username: string;
  server?: string;
  mods: string[];
  visible: boolean;
  width: number;
  height: number;
  java?: string;
  sounds: boolean;
  onProgress: (done: number, total: number) => void;
}

/** Downloads what is missing and returns the command to start; the real one is launcher.ts's prepareClient. */
export type PrepareClient = (p: PrepareInput) => Promise<{ command: string; args: string[]; gameDir: string; downloadedBytes: number }>;

export interface ClientStatus {
  username: string;
  state: ClientState;
  instance?: string | null;
  server?: string;
  pid?: number;
  gameDir?: string;
  progress?: { done: number; total: number };
  downloadedBytes?: number;
  startedAt?: number;
  readyMs?: number;
  exitCode?: number | null;
  logTail: string[];
}

/** The part of ServerManager this needs: which managed servers run. */
export interface ServerLister {
  status(): { servers: { serverDir: string; state: string }[] };
}

export interface ClientManagerOptions {
  agents: AgentServer;
  /** CRAFTWIRE_HOME for the client, so its agent finds this hub. */
  home: string;
  /** Cache and instances (default `<home>/client`). */
  root?: string;
  servers: ServerLister;
  prepare: PrepareClient;
  /** How long stop waits for the game to quit before killing it (default 10 s). */
  stopTimeoutMs?: number;
  quitTimeoutMs?: number;
  readyPollMs?: number;
}

interface Managed {
  username: string;
  state: ClientState;
  log: RingBuffer<string>;
  server?: string;
  gameDir?: string;
  child?: ChildProcess;
  exited?: Promise<number | null>;
  agent?: string | null;
  onAgent?: () => void;
  progress?: { done: number; total: number };
  downloadedBytes?: number;
  startedAt: number;
  readyMs?: number;
  exitCode?: number | null;
}

const USERNAME = /^[A-Za-z0-9_]{3,16}$/;
const LIVE: readonly ClientState[] = ["downloading", "starting", "running", "stopping"];
const isLive = (m: Managed | undefined): m is Managed => m !== undefined && LIVE.includes(m.state);
const KEEP_STOPPED = 10;

/** Runs headless Minecraft clients for the hub and maps each one to its agent by game directory. */
export class ClientManager {
  private readonly clients = new Map<string, Managed>();
  private readonly root: string;

  constructor(private readonly opts: ClientManagerOptions) {
    this.root = opts.root ?? join(opts.home, "client");
    opts.agents.on("connected", (i: InstanceInfo) => {
      if (i.kind !== "client" || i.gameDir === undefined) return;
      for (const m of this.clients.values()) {
        if (!isLive(m) || m.gameDir === undefined || !samePath(m.gameDir, i.gameDir)) continue;
        m.agent = i.id;
        m.onAgent?.();
      }
    });
    opts.agents.on("disconnected", (i: InstanceInfo) => {
      for (const m of this.clients.values()) if (m.agent === i.id) m.agent = null;
    });
  }

  async start(o: ClientStartOptions): Promise<ClientStatus> {
    const username = o.username ?? "Craftwire";
    if (!USERNAME.test(username)) throw new CraftwireError("INVALID_PARAMS", `"${username}" is not a valid player name`, "Use 3-16 letters, digits or _.");
    if (isLive(this.clients.get(username))) {
      throw new CraftwireError("ALREADY_RUNNING", `A client named ${username} is already running`, "Use it, stop it first, or pass another username.");
    }
    const server = o.server ?? this.defaultServer();
    const m: Managed = { username, state: "downloading", log: new RingBuffer(2000), startedAt: Date.now(), agent: null };
    if (server !== undefined) m.server = server;
    this.clients.set(username, m);
    this.pruneStopped();

    let prepared: Awaited<ReturnType<PrepareClient>>;
    try {
      prepared = await this.opts.prepare({
        root: this.root, username, mods: o.mods ?? [], visible: o.visible ?? false,
        width: o.windowSize?.width ?? 1280, height: o.windowSize?.height ?? 720, sounds: o.sounds ?? false,
        ...(server !== undefined ? { server } : {}), ...(o.java !== undefined ? { java: o.java } : {}),
        onProgress: (done, total) => { m.progress = { done, total }; },
      });
    } catch (e) {
      this.clients.delete(username);
      throw e;
    }
    m.gameDir = prepared.gameDir;
    m.downloadedBytes = prepared.downloadedBytes;
    m.state = "starting";
    m.startedAt = Date.now();
    this.spawn(m, prepared.command, prepared.args);

    const timeoutMs = o.timeoutMs ?? 300_000;
    let timer: NodeJS.Timeout | undefined;
    const timeout = new Promise<"timeout">((r) => { timer = setTimeout(() => r("timeout"), timeoutMs); });
    const exit = m.exited!.then(() => "exit" as const);
    let first: "ready" | "exit" | "timeout";
    try {
      first = await Promise.race([this.ready(m), exit, timeout]);
    } catch (e) {
      // JOIN_FAILED: the game runs but is stuck on the disconnect screen; it is of no use.
      await this.doStop(m).catch(() => undefined);
      throw e;
    } finally {
      clearTimeout(timer);
    }
    if (first === "exit") throw this.crashError(m);
    if (first === "timeout") {
      throw new CraftwireError("TIMEOUT", `The client was not ready within ${timeoutMs} ms`,
        "It may still be loading: watch client_process {action:'status'}, or stop it.", { logTail: m.log.toArray().slice(-40) });
    }
    m.state = "running";
    m.readyMs = Date.now() - m.startedAt;
    return this.snapshot(m, 10);
  }

  async stop(username?: string): Promise<{ username: string; stopped: true; forced: boolean; exitCode: number | null }> {
    const m = this.resolve(username);
    const forced = await this.doStop(m);
    return { username: m.username, stopped: true, forced, exitCode: m.exitCode ?? null };
  }

  status(tail = 20): { clients: ClientStatus[] } {
    return { clients: [...this.clients.values()].map((m) => this.snapshot(m, tail)) };
  }

  /** Stops every client this hub started; called when the hub exits. */
  async shutdown(): Promise<void> {
    await Promise.all([...this.clients.values()].filter(isLive).map((m) => this.doStop(m).catch(() => undefined)));
  }

  /** Keeps the KEEP_STOPPED most recent stopped/crashed entries for status; live clients always stay. */
  private pruneStopped(): void {
    const stopped = [...this.clients.values()].filter((m) => !LIVE.includes(m.state)).sort((a, b) => b.startedAt - a.startedAt);
    for (const m of stopped.slice(KEEP_STOPPED)) this.clients.delete(m.username);
  }

  private defaultServer(): string | undefined {
    const running = this.opts.servers.status().servers.filter((s) => s.state === "running");
    if (running.length === 0) return undefined;
    if (running.length > 1) {
      throw new CraftwireError("NO_SERVER", `${running.length} servers are running`,
        `Pass server as host:port. Running: ${running.map((s) => s.serverDir).join(", ")}.`);
    }
    const a = serverAddress(running[0]!.serverDir);
    if (a.onlineMode) {
      throw new CraftwireError("ONLINE_MODE_SERVER", `The server in ${running[0]!.serverDir} runs online-mode=true`,
        "client_process clients play offline. Ask the user to set online-mode=false in server.properties of this dev server and restart it.");
    }
    return `${a.host}:${a.port}`;
  }

  private resolve(username?: string): Managed {
    if (username !== undefined) {
      const m = this.clients.get(username);
      if (!isLive(m)) throw new CraftwireError("CLIENT_NOT_RUNNING", `No client named ${username} is running`, "client_process {action:'status'} lists them.");
      return m;
    }
    const live = [...this.clients.values()].filter(isLive);
    if (live.length === 1) return live[0]!;
    if (live.length === 0) throw new CraftwireError("CLIENT_NOT_RUNNING", "No client is running", "Start one with client_process {action:'start'}.");
    throw new CraftwireError("INVALID_PARAMS", `${live.length} clients are running`, `Pass username: one of ${live.map((m) => m.username).join(", ")}.`);
  }

  private spawn(m: Managed, command: string, args: string[]): void {
    const child = spawn(command, args, {
      cwd: m.gameDir,
      env: { ...process.env, CRAFTWIRE_HOME: this.opts.home },
      stdio: ["ignore", "pipe", "pipe"],
      windowsHide: true,
      // POSIX: own process group so a forced stop kills a wrapper (xvfb-run) and the JVM together.
      detached: process.platform !== "win32",
    });
    m.child = child;
    for (const stream of [child.stdout, child.stderr]) createInterface({ input: stream, crlfDelay: Infinity }).on("line", (l) => m.log.push(l));
    m.exited = new Promise((res) => {
      let settled = false;
      const settle = (code: number | null) => {
        if (settled) return;
        settled = true;
        m.exitCode = code;
        m.state = m.state === "stopping" || (m.state === "running" && code === 0) ? "stopped" : "crashed";
        res(code);
      };
      child.once("error", (e) => { m.log.push(`[craftwire] cannot run ${command}: ${e.message}`); settle(null); });
      child.once("close", (code) => settle(code));
    });
  }

  /** Resolves when the agent is connected and, with a server, the player is in the world; throws JOIN_FAILED. */
  private async ready(m: Managed): Promise<"ready"> {
    if (!m.agent) await new Promise<void>((r) => { m.onAgent = r; });
    if (m.server === undefined) return "ready";
    const poll = this.opts.readyPollMs ?? 500;
    for (;;) {
      if (!isLive(m)) return "ready"; // the exit branch of the race reports it
      const agent = m.agent;
      if (agent) {
        try {
          await this.opts.agents.request(agent, "player.state", {}, 5000);
          return "ready";
        } catch (e) {
          if (e instanceof CraftwireError && e.code === "NOT_IN_WORLD") await this.checkDisconnected(agent);
        }
      }
      await sleep(poll);
    }
  }

  private async checkDisconnected(agent: string): Promise<void> {
    const screen = (await this.opts.agents.request(agent, "gui.read", {}, 5000).catch(() => undefined)) as { type?: string; title?: string } | undefined;
    if (screen?.type && /Disconnected/i.test(screen.type)) {
      throw new CraftwireError("JOIN_FAILED", `The client could not join the server: ${screen.title ?? screen.type}`,
        "Check that the server runs, the address is right and online-mode=false.", { screen });
    }
  }

  private crashError(m: Managed): CraftwireError {
    const details: Record<string, unknown> = { exitCode: m.exitCode, logTail: m.log.toArray().slice(-40) };
    const report = m.gameDir ? newestCrashReport(m.gameDir, m.startedAt) : undefined;
    if (report) details.crashReport = report;
    return new CraftwireError("CLIENT_CRASHED", `The client exited before it was ready (exit code ${m.exitCode})`,
      "Read logTail (and the crash report) for the cause; a mod passed in mods is a common one.", details);
  }

  /** client.quit through the agent, then a kill if the game does not exit in time. Returns whether it was forced. */
  private async doStop(m: Managed): Promise<boolean> {
    if (!isLive(m) || !m.exited) return false;
    m.state = "stopping";
    if (m.agent) await this.opts.agents.request(m.agent, "client.quit", {}, this.opts.quitTimeoutMs ?? 5000).catch(() => undefined);
    let timer: NodeJS.Timeout | undefined;
    const timedOut = await Promise.race([
      m.exited.then(() => false),
      new Promise<boolean>((r) => { timer = setTimeout(() => r(true), this.opts.stopTimeoutMs ?? 10_000); }),
    ]);
    clearTimeout(timer);
    if (timedOut) {
      killTree(m.child?.pid);
      await m.exited;
    }
    return timedOut;
  }

  private snapshot(m: Managed, tail: number): ClientStatus {
    const s: ClientStatus = { username: m.username, state: m.state, logTail: tail > 0 ? m.log.toArray().slice(-tail) : [] };
    if (m.agent !== undefined) s.instance = m.agent;
    if (m.server !== undefined) s.server = m.server;
    if (isLive(m) && m.child?.pid !== undefined) s.pid = m.child.pid;
    if (m.gameDir !== undefined) s.gameDir = m.gameDir;
    if (m.state === "downloading" && m.progress) s.progress = m.progress;
    if (m.downloadedBytes !== undefined) s.downloadedBytes = m.downloadedBytes;
    s.startedAt = m.startedAt;
    if (m.readyMs !== undefined) s.readyMs = m.readyMs;
    if (m.exitCode !== undefined) s.exitCode = m.exitCode;
    return s;
  }
}

function newestCrashReport(gameDir: string, since: number): string | undefined {
  const dir = join(gameDir, "crash-reports");
  if (!existsSync(dir)) return undefined;
  const files = readdirSync(dir).map((f) => join(dir, f)).filter((f) => statSync(f).mtimeMs >= since - 1000);
  return files.sort((a, b) => statSync(b).mtimeMs - statSync(a).mtimeMs)[0];
}
