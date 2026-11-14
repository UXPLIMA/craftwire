/**
 * The Linux side of bedrock_client, answering the same requests as the Windows helper: it runs mcpelauncher's game
 * on its own X screen (x11-screen.ts), captures the window with ImageMagick's import (or ffmpeg's x11grab) and sends
 * keys, text, clicks and scrolls with xdotool (XTEST). On the hub's own Xvfb all of it, clicks included, stays off the
 * user's desktop. Runs on the hub's event loop; the game and the tools are child processes.
 */
import { type ChildProcess, spawn } from "node:child_process";
import { createInterface } from "node:readline";
import { CraftwireError } from "../errors.js";
import { RingBuffer } from "../ringbuffer.js";
import { findLauncher, type Launcher } from "./mcpelauncher.js";
import type { HelperAnswer, HelperRequest } from "./win32-helper.js";
import { keysym } from "./x11-keys.js";
import { SCREEN_HEIGHT, SCREEN_WIDTH, type ScreenProbe, type Spawn, X11Screen } from "./x11-screen.js";
import { type Run, runTool } from "./x11-tools.js";

/** The game's window title: mcpelauncher names it so (its main.cpp, createWindow); the game may rename it. */
const TITLE = "^(mcpelauncher|Minecraft)";
const BUTTONS: Record<string, string> = { left: "1", middle: "2", right: "3" };
const REQUEST_MS = 30_000;
const STOP_MS = 10_000;

export interface LinuxDeps {
  env: NodeJS.ProcessEnv;
  run: Run;
  spawn: Spawn;
  find: () => Launcher;
  /** Which display numbers are free; tests give a fake. */
  probe?: ScreenProbe;
}

interface GameWindow {
  id: string;
  x: number;
  y: number;
  width: number;
  height: number;
}

export class LinuxHelper {
  private readonly d: LinuxDeps;
  private readonly screen: X11Screen;
  private game: ChildProcess | undefined;
  private launcher: Launcher | undefined;
  /** How the game ended when it ended by itself; reported once, by the next find. */
  private exited: { code: number | null; log: string[] } | undefined;

  constructor(deps: Partial<LinuxDeps> = {}) {
    const env = deps.env ?? process.env;
    this.d = { env, run: runTool, spawn, find: () => findLauncher({ env }), ...deps };
    this.screen = new X11Screen(env, this.d.spawn, deps.probe);
  }

  async request(req: HelperRequest, timeoutMs = REQUEST_MS): Promise<HelperAnswer> {
    switch (req.op) {
      case "open":
        return this.open(String(req.url));
      case "find":
        return this.find();
      case "park":
        // The game has a screen of its own; nothing to move out of the user's way.
        return { parked: false };
      case "capture":
        return this.capture(Number(req.maxWidth));
      case "key": {
        const k = keysym(String(req.key));
        return this.input(["keydown", k, "sleep", (Number(req.holdMs ?? 50) / 1000).toFixed(3), "keyup", k], timeoutMs);
      }
      case "text":
        return this.input(["type", "--delay", "30", "--", String(req.text)], timeoutMs);
      case "click":
        return this.input(["mousemove", String(req.x), String(req.y), "click", BUTTONS[String(req.button)] ?? "1"], timeoutMs);
      case "scroll": {
        const n = Number(req.clicks);
        if (n === 0) return {};
        // X buttons 4 and 5 are the wheel; positive clicks scroll up, as on Windows.
        return this.input(["mousemove", String(req.x), String(req.y), "click", "--repeat", String(Math.abs(n)), "--delay", "30",
          n > 0 ? "4" : "5"], timeoutMs);
      }
      case "close":
        return this.stop();
      default:
        return { error: `Unknown request ${req.op}` };
    }
  }

  close(): void {
    const game = this.game;
    this.game = undefined;
    if (game !== undefined) signal(game, "SIGTERM");
    this.screen.stop();
  }

  private async open(url: string): Promise<HelperAnswer> {
    const connect = url !== "minecraft://";
    const env = await this.screen.environment();
    if (this.game !== undefined && this.launcher !== undefined) {
      // The client given just a link hands it to the running game (mcpelauncher's sendfile IPC) and exits.
      if (connect) await this.d.run(this.launcher.command, [...this.launcher.args, url], { env, timeoutMs: REQUEST_MS });
      return {};
    }
    const l = this.d.find();
    this.launcher = l;
    const args = [...l.args, "-dg", l.gameDir, "-ww", String(SCREEN_WIDTH), "-wh", String(SCREEN_HEIGHT), ...(connect ? ["--uri", url] : [])];
    this.exited = undefined;
    const log = new RingBuffer<string>(40);
    // Its own process group, so stopping a Flatpak wrapper stops the game under it too.
    const game = this.d.spawn(l.command, args, { env, stdio: ["ignore", "pipe", "pipe"], detached: true });
    this.game = game;
    for (const s of [game.stdout, game.stderr]) if (s) createInterface({ input: s, crlfDelay: Infinity }).on("line", (line) => log.push(line));
    const ended = (code: number | null, why?: string): void => {
      if (why !== undefined) log.push(why);
      if (this.game !== game) return;
      this.game = undefined;
      this.exited = { code, log: log.toArray().slice(-15) };
    };
    game.once("error", (e) => ended(null, String(e)));
    game.once("exit", (code) => ended(code));
    return {};
  }

  private async find(): Promise<HelperAnswer> {
    if (this.game === undefined) {
      const exited = this.exited;
      this.exited = undefined;
      return exited === undefined ? { running: false } : { running: false, exited };
    }
    const w = await this.window();
    if (w === undefined) return { running: false };
    const focus = await this.xdo(["getwindowfocus"]).then((b) => b.toString("utf8").trim(), () => "");
    return { running: true, focused: focus === w.id, client: [w.x, w.y, w.width, w.height] };
  }

  private async capture(maxWidth: number): Promise<HelperAnswer> {
    const w = await this.window();
    if (w === undefined) return { error: "NOT_RUNNING" };
    const env = await this.screen.environment();
    const client = [w.x, w.y, w.width, w.height];
    try {
      const png = await this.d.run("import", ["-window", w.id, "-resize", `${maxWidth}x>`, "png:-"], { env, timeoutMs: REQUEST_MS });
      return { png: png.toString("base64"), method: "import", client };
    } catch (e) {
      if (!(e instanceof CraftwireError && e.code === "MISSING_TOOL")) throw e;
    }
    const args = ["-loglevel", "error", "-f", "x11grab", "-video_size", `${w.width}x${w.height}`, "-i", `${env.DISPLAY}+${w.x},${w.y}`,
      "-frames:v", "1", "-vf", `scale='min(iw,${maxWidth})':-2`, "-f", "image2pipe", "-vcodec", "png", "-"];
    try {
      const png = await this.d.run("ffmpeg", args, { env, timeoutMs: REQUEST_MS });
      return { png: png.toString("base64"), method: "x11grab", client };
    } catch (e) {
      if (!(e instanceof CraftwireError && e.code === "MISSING_TOOL")) throw e;
      throw new CraftwireError("MISSING_TOOL", "Neither ImageMagick's import nor ffmpeg is installed, so the screen cannot be captured",
        "Install one (Debian/Ubuntu: apt install imagemagick).");
    }
  }

  /** Focuses the game window, then sends the xdotool commands (XTEST input goes to the focused window). */
  private async input(commands: string[], timeoutMs: number): Promise<HelperAnswer> {
    const w = await this.window();
    if (w === undefined) return { error: "NOT_RUNNING" };
    await this.xdo(["windowfocus", w.id, ...commands], timeoutMs);
    return {};
  }

  private async window(): Promise<GameWindow | undefined> {
    if (this.game === undefined) return undefined;
    let id = (await this.search(["--onlyvisible", "--name", TITLE])).at(-1);
    // On the hub's own screen any window is the game's (a sign-in page, say), so take the top one.
    if (id === undefined && this.screen.own) id = (await this.search(["--onlyvisible", "--maxdepth", "1", "--class", "."])).at(-1);
    if (id === undefined) return undefined;
    const shell = (await this.xdo(["getwindowgeometry", "--shell", id])).toString("utf8");
    const num = (k: string): number => Number(new RegExp(`^${k}=(-?\\d+)`, "m").exec(shell)?.[1] ?? 0);
    return { id, x: num("X"), y: num("Y"), width: num("WIDTH"), height: num("HEIGHT") };
  }

  /** Window ids, bottom to top; xdotool exits 1 when none match. */
  private async search(args: string[]): Promise<string[]> {
    try {
      return (await this.xdo(["search", ...args])).toString("utf8").split("\n").map((s) => s.trim()).filter((s) => s !== "");
    } catch (e) {
      if (e instanceof CraftwireError && e.code === "HELPER_FAILED") return [];
      throw e;
    }
  }

  private async xdo(args: string[], timeoutMs = REQUEST_MS): Promise<Buffer> {
    return this.d.run("xdotool", args, { env: await this.screen.environment(), timeoutMs });
  }

  private async stop(): Promise<HelperAnswer> {
    const game = this.game;
    this.game = undefined;
    if (game !== undefined) await stopProcess(game);
    this.screen.stop();
    return { closed: game !== undefined };
  }
}

function signal(p: ChildProcess, s: NodeJS.Signals): void {
  try {
    if (p.pid === undefined) throw new Error("no pid");
    process.kill(-p.pid, s);
  } catch {
    p.kill(s);
  }
}

/** SIGTERM to the game's process group, SIGKILL if it is still there after STOP_MS. */
async function stopProcess(p: ChildProcess): Promise<void> {
  if (p.exitCode !== null || p.signalCode !== null) return;
  const exited = new Promise<boolean>((resolve) => p.once("exit", () => resolve(true)));
  signal(p, "SIGTERM");
  let timer: NodeJS.Timeout | undefined;
  const gone = await Promise.race([exited, new Promise<boolean>((resolve) => { timer = setTimeout(() => resolve(false), STOP_MS); })]);
  clearTimeout(timer);
  if (!gone) signal(p, "SIGKILL");
}
