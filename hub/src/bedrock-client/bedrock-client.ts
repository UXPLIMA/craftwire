/**
 * The user's own Bedrock game, seen and driven from outside: started with a minecraft://connect link, captured as a
 * picture of its window, and given keys, text and clicks. On Windows it is Minecraft for Windows (win32-helper.ts): the
 * game opens behind the user's windows and pictures, keys and text reach it there, so the user keeps their desktop;
 * it reads the mouse from the device itself (GameInput), which no program can click for, so its menus are driven with
 * keys. On Linux it is mcpelauncher's game on its own X screen (linux-helper.ts), where clicks work too and all input
 * stays off the user's desktop. Nothing is read from inside the game, so the agent learns what the window shows and
 * nothing more. One game at a time, as the game itself.
 */
import { CraftwireError } from "../errors.js";
import { virtualKey } from "./keys.js";
import { LinuxHelper } from "./linux-helper.js";
import { type HelperAnswer, type HelperRequest, Win32Helper } from "./win32-helper.js";

const PROCESS = "Minecraft.Windows";
const DEFAULT_PORT = 19132;
const START_MS = 120_000;
const DEFAULT_WIDTH = 1280;
/** How long after its window appears the game is put behind the user's windows: it takes the front while it starts. */
const PARK_MS = [1500, 4000];

export interface ClientArea {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface Shot {
  data: string;
  width: number;
  height: number;
  method: string;
}

/** What the client needs of the helper; tests give a fake. */
export type Helper = Pick<Win32Helper, "request" | "close">;

export class BedrockClient {
  private readonly helper: Helper;
  /** Screenshot pixels per window pixel of the last screenshot, so clicks can be given in screenshot pixels. */
  private scale = 1;

  constructor(home: string, private readonly platform: NodeJS.Platform = process.platform, helper?: Helper) {
    this.helper = helper ?? (platform === "linux" ? new LinuxHelper() : new Win32Helper(home));
  }

  /** Starts the game (or reuses the running one) and, with an address, sends it to that server. */
  async start(address: string | undefined, timeoutMs = START_MS): Promise<Record<string, unknown>> {
    const url = address === undefined ? "minecraft://" : connectUrl(address);
    await this.call({ op: "open", url });
    const end = Date.now() + timeoutMs;
    for (;;) {
      const s = await this.status();
      if (s.exited !== undefined) throw exitedEarly(s.exited);
      if (s.running === true) {
        await this.park();
        return { ...(await this.status()), ...(address !== undefined ? { connecting: address } : {}) };
      }
      if (Date.now() > end) {
        throw new CraftwireError("BEDROCK_CLIENT_NOT_FOUND", `The game did not open a window in ${timeoutMs / 1000} s`,
          this.platform === "linux"
            ? "bedrock_client {action:'status'} shows whether it is still starting; the first start of a version can take minutes."
            : "Install Minecraft from the Microsoft Store or the Xbox app and start it once yourself to sign in.");
      }
      await new Promise((r) => setTimeout(r, 1000));
    }
  }

  async status(): Promise<Record<string, unknown>> {
    const a = await this.call({ op: "find", process: PROCESS });
    if (a.running !== true) return a.exited === undefined ? { running: false } : { running: false, exited: a.exited };
    return { running: true, focused: a.focused === true, window: area(a.client) };
  }

  /** The game window's client area as a PNG, at most `maxWidth` wide. */
  async screenshot(maxWidth = DEFAULT_WIDTH): Promise<Shot> {
    const a = await this.call({ op: "capture", process: PROCESS, maxWidth });
    const win = area(a.client);
    const width = Math.min(win.width, maxWidth);
    this.scale = win.width > 0 ? width / win.width : 1;
    return { data: String(a.png), width, height: Math.round(win.height * this.scale), method: String(a.method) };
  }

  async key(name: string, holdMs = 50): Promise<void> {
    const k = virtualKey(name);
    await this.call({ op: "key", process: PROCESS, key: name, vk: k.vk, extended: k.extended, holdMs }, holdMs + 10_000);
  }

  async type(text: string, enter: boolean): Promise<void> {
    await this.call({ op: "text", process: PROCESS, text }, 10_000 + text.length * 50);
    if (enter) await this.key("enter");
  }

  /** A chat command: T opens the chat, then the text and Enter. */
  async command(command: string): Promise<void> {
    await this.key("t");
    await new Promise((r) => setTimeout(r, 300));
    await this.type(command.startsWith("/") ? command : `/${command}`, true);
  }

  /** A click at (x, y) in the last screenshot's pixels (window pixels before any screenshot); Linux only. */
  async click(x: number, y: number, button: "left" | "right" | "middle"): Promise<void> {
    this.needMouse();
    const p = await this.screen(x, y);
    await this.call({ op: "click", process: PROCESS, x: p.x, y: p.y, button });
  }

  async scroll(x: number, y: number, clicks: number): Promise<void> {
    this.needMouse();
    const p = await this.screen(x, y);
    await this.call({ op: "scroll", process: PROCESS, x: p.x, y: p.y, clicks });
  }

  /** Asks the game to close, as its close button does. */
  async stop(): Promise<{ closed: boolean }> {
    const a = await this.call({ op: "close", process: PROCESS });
    return { closed: a.closed === true };
  }

  shutdown(): void {
    this.helper.close();
  }

  /** Gives the user's window the front back after the game took it; twice, as the game may take it again while loading. */
  private async park(): Promise<void> {
    for (const ms of PARK_MS) {
      await new Promise((r) => setTimeout(r, ms));
      const a = await this.call({ op: "park", process: PROCESS });
      if (a.parked === false) return;
    }
  }

  /**
   * Minecraft for Windows reads the mouse from the device through GameInput: posted mouse messages and SendInput
   * (even with the game in front) leave its buttons and wheel untouched (measured on 26.63), while posted keys work.
   */
  private needMouse(): void {
    if (this.platform !== "win32") return;
    throw new CraftwireError("BEDROCK_CLICK_UNSUPPORTED", "Minecraft for Windows takes clicks and the wheel only from a real mouse",
      "Use keys: up/down/left/right move the selection (lists scroll with it), enter presses it, escape goes back.");
  }

  private async screen(x: number, y: number): Promise<{ x: number; y: number }> {
    const s = await this.status();
    if (s.running !== true) throw notRunning();
    const win = s.window as ClientArea;
    const wx = Math.round(x / this.scale);
    const wy = Math.round(y / this.scale);
    if (wx < 0 || wy < 0 || wx >= win.width || wy >= win.height) {
      throw new CraftwireError("INVALID_PARAMS", `(${x}, ${y}) is outside the game window`,
        "Give x and y in the last screenshot's pixels.");
    }
    return { x: win.x + wx, y: win.y + wy };
  }

  private async call(req: HelperRequest, timeoutMs?: number): Promise<HelperAnswer> {
    if (this.platform !== "win32" && this.platform !== "linux") {
      throw new CraftwireError("UNSUPPORTED_PLATFORM", "The Bedrock client runs on Windows and Linux",
        "Bedrock bots (bot_spawn kind:'bedrock') read menus and forms on any system.");
    }
    const a = await this.helper.request(req, timeoutMs);
    if (typeof a.error === "string") {
      if (a.error.includes("NOT_RUNNING")) throw notRunning();
      if (a.error.includes("NOT_FOCUSED")) {
        throw new CraftwireError("BEDROCK_CLIENT_NOT_FOCUSED", "Windows did not let the game window come to the front, so nothing was sent",
          "Click the game window once yourself (or close what sits in front of it), then try again.");
      }
      if (a.error.includes("NO_AREA")) {
        throw new CraftwireError("BEDROCK_CLIENT_HIDDEN", "The game window has no area",
          "It is minimized; bedrock_client {action:'key'} brings it back.");
      }
      throw new CraftwireError("HELPER_FAILED", a.error, "Try again; bedrock_client {action:'status'} shows the window.");
    }
    return a;
  }
}

/** minecraft://connect/?serverUrl=<host>&serverPort=<port>, the link Bedrock opens a server with. */
export function connectUrl(address: string): string {
  const m = /^\[?([^\]]+?)\]?(?::(\d+))?$/.exec(address.trim());
  if (m === null || m[1] === undefined) {
    throw new CraftwireError("INVALID_PARAMS", `"${address}" is not host[:port]`, "For example play.example.com:19132.");
  }
  const port = m[2] === undefined ? DEFAULT_PORT : Number(m[2]);
  return `minecraft://connect/?serverUrl=${encodeURIComponent(m[1])}&serverPort=${port}`;
}

function area(v: unknown): ClientArea {
  const a = Array.isArray(v) ? v.map(Number) : [];
  return { x: a[0] ?? 0, y: a[1] ?? 0, width: a[2] ?? 0, height: a[3] ?? 0 };
}

function exitedEarly(v: unknown): CraftwireError {
  const e = (v ?? {}) as { code?: unknown; log?: unknown };
  const log = Array.isArray(e.log) ? e.log.map(String) : [];
  return new CraftwireError("BEDROCK_CLIENT_EXITED", `The game stopped while starting (exit ${String(e.code ?? "unknown")})`,
    "Its last log lines are in details; start the same version once from the mcpelauncher launcher to see it run.", { log });
}

function notRunning(): CraftwireError {
  return new CraftwireError("BEDROCK_CLIENT_NOT_RUNNING", "The Bedrock game is not running",
    "bedrock_client {action:'start', address} starts it.");
}
