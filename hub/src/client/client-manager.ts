import { type ChildProcess, spawn } from "node:child_process";
import { join } from "node:path";
import { createInterface } from "node:readline";
import { setTimeout as sleep } from "node:timers/promises";
import type { InstanceInfo } from "../agents.js";
import { Accounts } from "../auth/accounts.js";
import { CraftwireError } from "../errors.js";
import { serverAddress } from "../dev/launch.js";
import { samePath } from "../dev/paths.js";
import { killTree } from "../dev/server-manager.js";
import { RingBuffer } from "../ringbuffer.js";
import { newestCrashReport, removeSave } from "./client-files.js";
import type {
  ClientManagerOptions, ClientStartOptions, ClientState, ClientStatus, LaunchSession, PrepareClient, WorldCreate,
} from "./client-types.js";
import { type DisconnectScreen, joinFailure } from "./join-failure.js";
import { ModProfiles } from "./mod-profiles.js";
import { type McVersion, NEWEST_VERSION, SUPPORTED_VERSIONS, supportedVersion } from "./pins.js";
import { pingServer } from "./server-ping.js";

export type {
  ClientManagerOptions, ClientStartOptions, ClientState, ClientStatus, PrepareClient, PrepareInput, ResourcePackPolicy, ServerLister,
  WorldCreate,
} from "./client-types.js";
export { joinFailure } from "./join-failure.js";

interface Managed {
  username: string;
  state: ClientState;
  version?: McVersion;
  log: RingBuffer<string>;
  server?: string;
  world?: { name: string; create?: WorldCreate };
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
  lite?: boolean;
  account?: string;
}

const USERNAME = /^[A-Za-z0-9_]{3,16}$/;
/** A save folder name: no path separators or dots-only names, so it always stays inside saves/. */
const WORLD_NAME = /^(?!\.+$)[A-Za-z0-9 _.-]{1,64}$/;

const LIVE: readonly ClientState[] = ["downloading", "starting", "running", "stopping"];
const isLive = (m: Managed | undefined): m is Managed => m !== undefined && LIVE.includes(m.state);
const KEEP_STOPPED = 10;
/** Hidden clients at once: each is a full game process, and the design aims at 2-4 on one machine. */
export const MAX_CLIENTS = 4;

/** Runs headless Minecraft clients for the hub and maps each one to its agent by game directory. */
export class ClientManager {
  private readonly clients = new Map<string, Managed>();
  private readonly root: string;
  readonly accounts: Accounts;
  readonly modProfiles: ModProfiles;

  constructor(private readonly opts: ClientManagerOptions) {
    this.root = opts.root ?? join(opts.home, "client");
    this.accounts = opts.accounts ?? new Accounts(opts.home);
    this.modProfiles = opts.modProfiles ?? new ModProfiles(this.root);
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
    const session = o.account !== undefined ? await this.accounts.session(o.account) : undefined;
    const username = this.checkStart(o, session);
    const server = o.world !== undefined ? undefined : o.server ?? this.defaultServer(session !== undefined);
    const version = o.version ?? await this.serverVersion(server);
    const m: Managed = { username, version, state: "downloading", log: new RingBuffer(2000), startedAt: Date.now(), agent: null };
    if (server !== undefined) m.server = server;
    if (o.world !== undefined) m.world = o.world;
    if (o.lite) m.lite = true;
    if (session !== undefined) m.account = session.name;
    this.clients.set(username, m);
    this.pruneStopped();

    let prepared: Awaited<ReturnType<PrepareClient>>;
    try {
      const profileMods = o.modProfile !== undefined ? await this.modProfiles.resolve(o.modProfile, version) : [];
      prepared = await this.opts.prepare({
        root: this.root, version, username, mods: [...(o.mods ?? []), ...profileMods], visible: (o.visible ?? false) && !o.lite,
        width: o.windowSize?.width ?? 1280, height: o.windowSize?.height ?? 720, sounds: (o.sounds ?? false) && !o.lite,
        lite: o.lite ?? false, resourcePacks: o.resourcePacks ?? "prompt",
        ...(server !== undefined ? { server } : {}), ...(o.java !== undefined ? { java: o.java } : {}),
        ...(session !== undefined ? { session: launchSession(session) } : {}),
        onProgress: (done, total) => { m.progress = { done, total }; },
      });
    } catch (e) {
      this.clients.delete(username);
      throw e;
    }
    m.gameDir = prepared.gameDir;
    m.downloadedBytes = prepared.downloadedBytes;
    if (o.world?.create?.replace) removeSave(prepared.gameDir, o.world.name);
    m.state = "starting";
    m.startedAt = Date.now();
    this.spawn(m, prepared.command, prepared.args);

    const timeoutMs = o.timeoutMs ?? 300_000;
    let timer: NodeJS.Timeout | undefined;
    const timeout = new Promise<"timeout">((r) => { timer = setTimeout(() => r("timeout"), timeoutMs); });
    const exit = m.exited!.then(() => "exit" as const);
    const ready = this.ready(m);
    let first: "ready" | "exit" | "timeout";
    try {
      first = await Promise.race([ready, exit, timeout]);
    } catch (e) {
      // JOIN_FAILED: the game runs but is stuck on the disconnect screen; it is of no use.
      await this.doStop(m).catch(() => undefined);
      throw e;
    } finally {
      clearTimeout(timer);
    }
    // The ready loop also ends when the process is gone; whichever branch won, a dead client is a crash.
    if (first === "exit" || (m.state as ClientState) === "crashed") throw this.crashError(m);
    if (first === "timeout") {
      // The process keeps running; status shows it as running once it gets there.
      ready.then(() => this.markRunning(m), () => undefined);
      throw new CraftwireError("TIMEOUT", `The client was not ready within ${timeoutMs} ms`,
        "It may still be loading: watch client_process {action:'status'}, or stop it.", { logTail: m.log.toArray().slice(-40) });
    }
    // A hidden window from 26.3 (SDL3) reports the asked size but keeps drawing at its first framebuffer size
    // until it is resized once; screenshots and videos would come out at 1280x720.
    if (o.windowSize && m.agent) {
      await this.opts.agents.request(m.agent, "client.settings", { windowSize: o.windowSize }, 5000).catch(() => undefined);
    }
    this.markRunning(m);
    return this.snapshot(m, 10);
  }

  /** Checks a start request before anything is downloaded; returns the player name the client plays as. */
  private checkStart(o: ClientStartOptions, session: LaunchSession | undefined): string {
    if (session !== undefined && o.username !== undefined && o.username !== session.name) {
      throw new CraftwireError("INVALID_PARAMS", `The account plays as ${session.name}, not ${o.username}`,
        "Leave out username when passing account.");
    }
    if (o.lite && o.visible) throw new CraftwireError("INVALID_PARAMS", "A lite client is always hidden", "Leave out visible, or lite.");
    const username = session?.name ?? o.username ?? "Craftwire";
    if (!USERNAME.test(username)) throw new CraftwireError("INVALID_PARAMS", `"${username}" is not a valid player name`,
      "Use 3-16 letters, digits or _.");
    if (isLive(this.clients.get(username))) {
      throw new CraftwireError("ALREADY_RUNNING", `A client named ${username} is already running`,
        "Use it, stop it first, or pass another username.");
    }
    const live = [...this.clients.values()].filter(isLive);
    if (live.length >= MAX_CLIENTS) {
      throw new CraftwireError("TOO_MANY_CLIENTS", `${live.length} clients are running (at most ${MAX_CLIENTS})`,
        `Stop one first: ${live.map((c) => c.username).join(", ")}. For many players use bot_spawn instead.`);
    }
    if (o.world !== undefined) {
      if (o.server !== undefined) throw new CraftwireError("INVALID_PARAMS", "Pass either server or world, not both",
        "world opens a singleplayer world; server joins a server.");
      if (!WORLD_NAME.test(o.world.name)) {
        throw new CraftwireError("INVALID_PARAMS", `"${o.world.name}" is not a usable world name`,
          "Use 1-64 letters, digits, spaces, '.', '-' and '_'.");
      }
    }
    return username;
  }

  private markRunning(m: Managed): void {
    if (m.state !== "starting") return;
    m.state = "running";
    m.readyMs = Date.now() - m.startedAt;
  }

  /** Stops every live client; what each one did. */
  async stopAll(): Promise<{ stopped: { username: string; forced: boolean }[] }> {
    const live = [...this.clients.values()].filter(isLive);
    const forced = await Promise.all(live.map((m) => this.doStop(m).catch(() => true)));
    return { stopped: live.map((m, i) => ({ username: m.username, forced: forced[i]! })) };
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

  /** The supported version matching what `server` reports; the newest when there is no server or it does not answer. */
  private async serverVersion(server: string | undefined): Promise<McVersion> {
    if (server === undefined) return NEWEST_VERSION;
    const colon = server.lastIndexOf(":");
    const [host, port] = colon >= 0 ? [server.slice(0, colon), Number(server.slice(colon + 1))] : [server, 25565];
    const reported = await (this.opts.ping ?? pingServer)(host, port);
    if (reported === undefined) return NEWEST_VERSION;
    const v = supportedVersion(reported.name);
    if (v === undefined) {
      throw new CraftwireError("VERSION_UNSUPPORTED",
        `${server} runs ${reported.name}; client_process runs Minecraft ${SUPPORTED_VERSIONS.join(", ")}`,
        "Use a server on one of those versions, or start the client without a server.");
    }
    return v;
  }

  /** The one running dev server; with no signed-in account it must be offline-mode. */
  private defaultServer(online: boolean): string | undefined {
    const running = this.opts.servers.status().servers.filter((s) => s.state === "running");
    if (running.length === 0) return undefined;
    if (running.length > 1) {
      throw new CraftwireError("NO_SERVER", `${running.length} servers are running`,
        `Pass server as host:port. Running: ${running.map((s) => s.serverDir).join(", ")}.`);
    }
    const a = serverAddress(running[0]!.serverDir);
    if (a.onlineMode && !online) {
      throw new CraftwireError("ONLINE_MODE_SERVER", `The server in ${running[0]!.serverDir} runs online-mode=true`,
        "Sign in (client_process {action:'sign_in'}) and pass account, or ask the user to set online-mode=false on this dev server.");
    }
    return `${a.host}:${a.port}`;
  }

  private resolve(username?: string): Managed {
    if (username !== undefined) {
      const m = this.clients.get(username);
      if (!isLive(m)) throw new CraftwireError("CLIENT_NOT_RUNNING", `No client named ${username} is running`,
        "client_process {action:'status'} lists them.");
      return m;
    }
    const live = [...this.clients.values()].filter(isLive);
    if (live.length === 1) return live[0]!;
    if (live.length === 0) throw new CraftwireError("CLIENT_NOT_RUNNING", "No client is running", "Start one with client_process {action:'start'}.");
    throw new CraftwireError("INVALID_PARAMS", `${live.length} clients are running`,
      `Pass username: one of ${live.map((m) => m.username).join(", ")}.`);
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
    if (m.world !== undefined) {
      const { replace: _replace, ...create } = m.world.create ?? {};
      const open = m.world.create !== undefined ? { name: m.world.name, create } : { name: m.world.name };
      await this.openWorld(m, open);
    } else if (m.server === undefined) {
      return "ready";
    }
    const poll = this.opts.readyPollMs ?? 500;
    for (;;) {
      if (!isLive(m)) return "ready"; // the exit branch of the race reports it
      const agent = m.agent;
      if (agent) {
        try {
          await this.opts.agents.request(agent, "player.state", {}, 5000);
          return "ready";
        } catch (e) {
          if (e instanceof CraftwireError && e.code === "NOT_IN_WORLD") await this.checkDisconnected(agent, m.username);
        }
      }
      await sleep(poll);
    }
  }

  /** world.open, retried while the client is still starting (NOT_READY) or a reply got lost (TIMEOUT). */
  private async openWorld(m: Managed, params: Record<string, unknown>): Promise<void> {
    const poll = this.opts.readyPollMs ?? 500;
    for (;;) {
      if (!isLive(m) || !m.agent) return;
      try {
        await this.opts.agents.request(m.agent, "world.open", params, Math.max(2000, poll * 10));
        return;
      } catch (e) {
        if (!(e instanceof CraftwireError) || (e.code !== "NOT_READY" && e.code !== "TIMEOUT")) throw e;
      }
      await sleep(poll);
    }
  }

  private async checkDisconnected(agent: string, username: string): Promise<void> {
    const screen = (await this.opts.agents.request(agent, "gui.read", {}, 5000).catch(() => undefined)) as DisconnectScreen | undefined;
    if (screen?.type && /Disconnected/i.test(screen.type)) {
      const { reason, hint } = joinFailure(screen, username);
      throw new CraftwireError("JOIN_FAILED", `The client could not join the server: ${reason}`, hint, { screen });
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
    if (m.version !== undefined) s.version = m.version;
    if (m.agent !== undefined) s.instance = m.agent;
    if (m.server !== undefined) s.server = m.server;
    if (m.world !== undefined) s.world = m.world.name;
    if (isLive(m) && m.child?.pid !== undefined) s.pid = m.child.pid;
    if (m.gameDir !== undefined) s.gameDir = m.gameDir;
    if (m.state === "downloading" && m.progress) s.progress = m.progress;
    if (m.downloadedBytes !== undefined) s.downloadedBytes = m.downloadedBytes;
    s.startedAt = m.startedAt;
    if (m.readyMs !== undefined) s.readyMs = m.readyMs;
    if (m.exitCode !== undefined) s.exitCode = m.exitCode;
    if (m.lite) s.lite = true;
    if (m.account !== undefined) s.account = m.account;
    return s;
  }
}

/** Only what the launch arguments carry; the session's expiry stays with Accounts. */
function launchSession(s: LaunchSession): LaunchSession {
  return { name: s.name, uuid: s.uuid, accessToken: s.accessToken, ...(s.xuid !== undefined ? { xuid: s.xuid } : {}) };
}
