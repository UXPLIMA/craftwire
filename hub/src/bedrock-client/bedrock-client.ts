/**
 * The user's own Minecraft for Windows (Bedrock), seen and driven from outside: started with a minecraft://connect
 * link, captured as a picture of its window, and given keys, text and clicks. Nothing is read from inside the game,
 * so the agent learns what the window shows and nothing more. Windows only; one game at a time, as the game itself.
 */
import { CraftwireError } from "../errors.js";
import { virtualKey } from "./keys.js";
import { type HelperAnswer, type HelperRequest, Win32Helper } from "./win32-helper.js";

const PROCESS = "Minecraft.Windows";
const DEFAULT_PORT = 19132;
const START_MS = 120_000;
const DEFAULT_WIDTH = 1280;

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
    this.helper = helper ?? new Win32Helper(home);
  }

  /** Starts the game (or reuses the running one) and, with an address, sends it to that server. */
  async start(address: string | undefined, timeoutMs = START_MS): Promise<Record<string, unknown>> {
    const url = address === undefined ? "minecraft://" : connectUrl(address);
    await this.call({ op: "open", url });
    const end = Date.now() + timeoutMs;
    for (;;) {
      const s = await this.status();
      if (s.running === true) return { ...s, ...(address !== undefined ? { connecting: address } : {}) };
      if (Date.now() > end) {
        throw new CraftwireError("BEDROCK_CLIENT_NOT_FOUND", `Minecraft for Windows did not open a window in ${timeoutMs / 1000} s`,
          "Install Minecraft from the Microsoft Store or the Xbox app and start it once yourself to sign in.");
      }
      await new Promise((r) => setTimeout(r, 1000));
    }
  }

  async status(): Promise<Record<string, unknown>> {
    const a = await this.call({ op: "find", process: PROCESS });
    if (a.running !== true) return { running: false };
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
    await this.call({ op: "key", process: PROCESS, vk: k.vk, extended: k.extended, holdMs }, holdMs + 10_000);
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

  /** A click at (x, y) in the last screenshot's pixels (window pixels before any screenshot). */
  async click(x: number, y: number, button: "left" | "right" | "middle"): Promise<void> {
    const p = await this.screen(x, y);
    await this.call({ op: "click", process: PROCESS, x: p.x, y: p.y, button });
  }

  async scroll(x: number, y: number, clicks: number): Promise<void> {
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
    if (this.platform !== "win32") {
      throw new CraftwireError("UNSUPPORTED_PLATFORM", "The Bedrock client runs on Windows only",
        "Bedrock bots (bot_spawn kind:'bedrock') read menus and forms on any system.");
    }
    const a = await this.helper.request(req, timeoutMs);
    if (typeof a.error === "string") {
      if (a.error.includes("NOT_RUNNING")) throw notRunning();
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

function notRunning(): CraftwireError {
  return new CraftwireError("BEDROCK_CLIENT_NOT_RUNNING", "Minecraft for Windows is not running",
    "bedrock_client {action:'start', address} starts it.");
}
