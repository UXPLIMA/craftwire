/**
 * The long-lived PowerShell helper (win32-script.ts) and its JSON-line protocol: one request per line on stdin, one
 * answer per line on stdout, matched by id. Started on first use; Add-Type compiles the C# part once, so the first
 * request waits a few seconds and the rest take milliseconds.
 */
import { type ChildProcess, spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { createInterface } from "node:readline";
import { CraftwireError } from "../errors.js";
import { RingBuffer } from "../ringbuffer.js";
import { helperScript } from "./win32-script.js";

export type HelperRequest = Record<string, unknown> & { op: string };
export type HelperAnswer = Record<string, unknown>;

const READY_MS = 60_000;
const REQUEST_MS = 30_000;

export class Win32Helper {
  private child: ChildProcess | undefined;
  private ready: Promise<void> | undefined;
  private next = 1;
  private readonly waiting = new Map<number, { resolve: (a: HelperAnswer) => void; reject: (e: Error) => void; timer: NodeJS.Timeout }>();
  private readonly errors = new RingBuffer<string>(50);

  constructor(private readonly home: string) {}

  async request(req: HelperRequest, timeoutMs = REQUEST_MS): Promise<HelperAnswer> {
    await this.start();
    const stdin = this.child?.stdin;
    if (stdin === undefined || stdin === null) throw this.gone();
    const id = this.next++;
    return new Promise<HelperAnswer>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.waiting.delete(id);
        reject(new CraftwireError("TIMEOUT", `The Windows helper did not answer ${req.op} in ${timeoutMs / 1000} s`,
          "bedrock_client {action:'status'} shows whether the game window is there; try again."));
      }, timeoutMs);
      this.waiting.set(id, { resolve, reject, timer });
      stdin.write(`${JSON.stringify({ ...req, id })}\n`);
    });
  }

  close(): void {
    this.child?.kill();
    this.child = undefined;
    this.ready = undefined;
  }

  private start(): Promise<void> {
    if (this.ready !== undefined) return this.ready;
    const script = this.writeScript();
    const child = spawn("powershell.exe", ["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script],
      { stdio: ["pipe", "pipe", "pipe"], windowsHide: true });
    this.child = child;
    createInterface({ input: child.stderr!, crlfDelay: Infinity }).on("line", (l) => this.errors.push(l));
    this.ready = new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => reject(new CraftwireError("HELPER_FAILED", "The Windows helper did not start in time",
        `PowerShell said: ${this.errors.toArray().slice(-5).join(" | ") || "nothing"}`)), READY_MS);
      createInterface({ input: child.stdout!, crlfDelay: Infinity }).on("line", (raw) => {
        // Windows PowerShell may put a UTF-8 byte order mark before its first line.
        const line = raw.replace(/^﻿/, "").trim();
        if (line === "") return;
        let msg: HelperAnswer;
        try {
          msg = JSON.parse(line) as HelperAnswer;
        } catch {
          this.errors.push(line);
          return;
        }
        if (msg.ready === true) {
          clearTimeout(timer);
          resolve();
          return;
        }
        this.answer(msg);
      });
      child.once("exit", (code) => {
        clearTimeout(timer);
        const e = this.gone(code);
        reject(e);
        for (const w of this.waiting.values()) {
          clearTimeout(w.timer);
          w.reject(e);
        }
        this.waiting.clear();
        if (this.child === child) {
          this.child = undefined;
          this.ready = undefined;
        }
      });
    });
    return this.ready;
  }

  private answer(msg: HelperAnswer): void {
    const id = typeof msg.id === "number" ? msg.id : -1;
    const w = this.waiting.get(id);
    if (w === undefined) return;
    this.waiting.delete(id);
    clearTimeout(w.timer);
    w.resolve(msg);
  }

  /** The script goes into the home under its hash, so a hub update never runs an older copy. */
  private writeScript(): string {
    const text = helperScript();
    const dir = join(this.home, "cache", "bedrock-client");
    const file = join(dir, `helper-${createHash("sha256").update(text).digest("hex").slice(0, 12)}.ps1`);
    if (!existsSync(file)) {
      mkdirSync(dir, { recursive: true });
      // A BOM makes Windows PowerShell read the script as UTF-8 rather than the ANSI code page.
      writeFileSync(file, `﻿${text}`, "utf8");
    }
    return file;
  }

  private gone(code?: number | null): CraftwireError {
    return new CraftwireError("HELPER_FAILED", `The Windows helper stopped${code === undefined || code === null ? "" : ` (exit ${code})`}`,
      `PowerShell said: ${this.errors.toArray().slice(-5).join(" | ") || "nothing"}`);
  }
}
