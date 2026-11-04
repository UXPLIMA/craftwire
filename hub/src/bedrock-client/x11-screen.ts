/**
 * The X screen the Linux Bedrock game runs on: an Xvfb the hub starts and stops, or the display named in
 * CRAFTWIRE_BEDROCK_DISPLAY when the user wants to watch the game. On a screen of its own the game holds the only
 * keyboard focus and pointer there, so nothing sent to it reaches the user's desktop.
 */
import { type ChildProcess, spawn, type SpawnOptions } from "node:child_process";
import { accessSync, constants, existsSync } from "node:fs";
import { createInterface } from "node:readline";
import { CraftwireError } from "../errors.js";
import { RingBuffer } from "../ringbuffer.js";
import { missingTool } from "./x11-tools.js";

export const SCREEN_WIDTH = 1280;
export const SCREEN_HEIGHT = 720;
const START_MS = 10_000;
/** Display numbers tried, from 99 up as xvfb-run does, clear of the low numbers desktops and WSLg use. */
const FIRST_DISPLAY = 99;
const DISPLAYS_TRIED = 20;

export type Spawn = (command: string, args: string[], o: SpawnOptions) => ChildProcess;

export interface ScreenProbe {
  /** Whether a display number is taken: its lock file or its socket file exists. */
  inUse: (n: number) => boolean;
  /** Whether X servers can make their socket files (WSL mounts /tmp/.X11-unix read-only for WSLg). */
  socketFiles: () => boolean;
}

const probe: ScreenProbe = {
  inUse: (n) => existsSync(`/tmp/.X${n}-lock`) || existsSync(`/tmp/.X11-unix/X${n}`),
  socketFiles: () => {
    try {
      accessSync("/tmp/.X11-unix", constants.W_OK);
      return true;
    } catch (e) {
      return (e as NodeJS.ErrnoException).code === "ENOENT";
    }
  },
};

class DisplayTaken extends Error {}

export class X11Screen {
  private xvfb: ChildProcess | undefined;
  private ready: Promise<string> | undefined;

  constructor(private readonly env: NodeJS.ProcessEnv, private readonly spawnFn: Spawn = spawn, private readonly p: ScreenProbe = probe) {}

  /** Whether the screen is the hub's own Xvfb rather than a display the user named. */
  get own(): boolean {
    return this.named() === undefined;
  }

  /** The DISPLAY value to run the game and the tools with; starts the Xvfb on first use. */
  display(): Promise<string> {
    const named = this.named();
    if (named !== undefined) return Promise.resolve(named);
    this.ready ??= this.start();
    return this.ready;
  }

  /** The environment for the game and the tools: this display, and X11 rather than Wayland. */
  async environment(): Promise<NodeJS.ProcessEnv> {
    const env: NodeJS.ProcessEnv = { ...this.env, DISPLAY: await this.display() };
    // On a Wayland desktop the game would open its window there instead of on this screen.
    if (this.own) delete env.WAYLAND_DISPLAY;
    return env;
  }

  stop(): void {
    const x = this.xvfb;
    this.forget(x);
    x?.kill();
  }

  private named(): string | undefined {
    const d = this.env.CRAFTWIRE_BEDROCK_DISPLAY;
    return d === undefined || d === "" ? undefined : d;
  }

  private async start(): Promise<string> {
    // Without socket files the server listens on the abstract socket only, which X clients on Linux try first.
    const listen = this.p.socketFiles() ? ["-nolisten", "tcp"] : ["-nolisten", "tcp", "-nolisten", "unix"];
    for (let n = FIRST_DISPLAY; n < FIRST_DISPLAY + DISPLAYS_TRIED; n++) {
      if (this.p.inUse(n)) continue;
      try {
        return await this.launch(n, listen);
      } catch (e) {
        if (!(e instanceof DisplayTaken)) throw e;
      }
    }
    throw new CraftwireError("HELPER_FAILED", `Displays :${FIRST_DISPLAY} to :${FIRST_DISPLAY + DISPLAYS_TRIED - 1} are all taken`,
      "Stop Xvfb servers left over from earlier runs (pkill Xvfb), or set CRAFTWIRE_BEDROCK_DISPLAY to a display to use.");
  }

  private launch(n: number, listen: string[]): Promise<string> {
    return new Promise<string>((resolve, reject) => {
      // -displayfd 1: Xvfb writes the display number once it accepts clients, so readiness is not guessed.
      const x = this.spawnFn("Xvfb", [`:${n}`, "-displayfd", "1", "-screen", "0", `${SCREEN_WIDTH}x${SCREEN_HEIGHT}x24`, ...listen],
        { stdio: ["ignore", "pipe", "pipe"] });
      this.xvfb = x;
      const said = new RingBuffer<string>(5);
      const fail = (e: Error): void => {
        clearTimeout(timer);
        this.forget(x);
        reject(e);
      };
      const timer = setTimeout(() => {
        x.kill();
        fail(new CraftwireError("HELPER_FAILED", `Xvfb did not start in ${START_MS / 1000} s`, `Xvfb said: ${said.toArray().join(" | ") || "nothing"}`));
      }, START_MS);
      if (x.stderr) createInterface({ input: x.stderr, crlfDelay: Infinity }).on("line", (l) => said.push(l));
      if (x.stdout) {
        createInterface({ input: x.stdout, crlfDelay: Infinity }).once("line", (l) => {
          clearTimeout(timer);
          resolve(`:${l.trim()}`);
        });
      }
      x.once("error", (e) => fail((e as NodeJS.ErrnoException).code === "ENOENT" ? missingTool("Xvfb") : e));
      x.once("exit", (code) => {
        const log = said.toArray().join(" | ");
        // Another server took the number between the check and the start.
        if (/already active|in use/i.test(log)) fail(new DisplayTaken(log));
        else fail(new CraftwireError("HELPER_FAILED", `Xvfb stopped (exit ${code})`, `Xvfb said: ${log || "nothing"}`));
      });
    });
  }

  /** Drops a stopped Xvfb, so the next display() starts a new one. */
  private forget(x: ChildProcess | undefined): void {
    if (x !== undefined && this.xvfb === x) {
      this.xvfb = undefined;
      this.ready = undefined;
    }
  }
}
