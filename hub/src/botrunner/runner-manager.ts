/**
 * Bot runners the hub starts on this machine (botrunner_process): a Java process per runner that connects back as
 * agent kind "botrunner"; the hub maps it to its process by pid. Runners on other machines connect on their own.
 */
import { type ChildProcess, spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { createInterface } from "node:readline";
import { fileURLToPath } from "node:url";
import type { AgentServer, InstanceInfo } from "../agents.js";
import { javaCandidates, pickJava } from "../client/launcher.js";
import { probeJavaMajor } from "../dev/launch.js";
import { killTree } from "../dev/server-manager.js";
import { CraftwireError } from "../errors.js";
import { RingBuffer } from "../ringbuffer.js";
import { HUB_VERSION } from "../version.js";

export type RunnerKind = "java" | "bedrock";

export interface RunnerStartOptions {
  name?: string;
  kinds?: RunnerKind[];
  maxBots?: number;
  /** Heap for the runner JVM in MB (default 2048). */
  memoryMb?: number;
  java?: string;
  timeoutMs?: number;
}

interface Runner {
  name: string;
  child: ChildProcess;
  log: RingBuffer<string>;
  startedAt: number;
  agent?: string;
  exited: Promise<number | null>;
  exitCode?: number | null;
}

const NAME = /^[A-Za-z0-9_-]{1,32}$/;
/** The runner is built for Java 25 (options.release in botrunner/build.gradle). */
const JAVA_MAJOR = 25;

/** The runner jar matching this hub: a repo checkout's build output, or the package's agent/ folder. */
export function runnerJar(dirs: string[] = runnerJarDirs()): string {
  const name = `craftwire-botrunner-${HUB_VERSION}.jar`;
  for (const dir of dirs) if (existsSync(join(dir, name))) return join(dir, name);
  throw new CraftwireError("RUNNER_JAR_MISSING", `${name} is not next to the hub`,
    "Reinstall craftwire, or in a repo checkout run ./gradlew :botrunner:jar.");
}

function runnerJarDirs(): string[] {
  const here = dirname(fileURLToPath(import.meta.url));
  return [join(here, "..", "..", "..", "botrunner", "build", "libs"), join(here, "..", "..", "agent")];
}

export class RunnerManager {
  private readonly runners = new Map<string, Runner>();

  constructor(private readonly agents: AgentServer, private readonly home: string) {
    agents.on("connected", (i: InstanceInfo) => {
      if (i.kind !== "botrunner" || i.pid === undefined) return;
      for (const r of this.runners.values()) if (r.child.pid === i.pid) r.agent = i.id;
    });
  }

  async start(o: RunnerStartOptions): Promise<Record<string, unknown>> {
    const name = o.name ?? `runner-${this.runners.size + 1}`;
    if (!NAME.test(name)) throw new CraftwireError("INVALID_PARAMS", `"${name}" is not a runner name`, "Use 1-32 letters, digits, - or _.");
    if (this.runners.has(name)) throw new CraftwireError("ALREADY_RUNNING", `A runner named ${name} is running`, "Stop it or pick another name.");
    const jar = runnerJar();
    const java = await pickJava(o.java !== undefined ? [o.java] : javaCandidates(), probeJavaMajor, JAVA_MAJOR, "The bot runner");
    const args = [`-Xmx${o.memoryMb ?? 2048}M`, "-jar", jar, "--name", name, "--kinds", (o.kinds ?? ["java", "bedrock"]).join(","),
      "--max-bots", String(o.maxBots ?? 1000), "--home", this.home];
    const child = spawn(java, args, { env: { ...process.env, CRAFTWIRE_HOME: this.home }, stdio: ["ignore", "pipe", "pipe"], windowsHide: true });
    const r: Runner = { name, child, log: new RingBuffer(1000), startedAt: Date.now(), exited: new Promise((res) => child.once("close", res)) };
    for (const s of [child.stdout, child.stderr]) createInterface({ input: s, crlfDelay: Infinity }).on("line", (l) => r.log.push(l));
    void r.exited.then((code) => { r.exitCode = code; this.runners.delete(name); });
    this.runners.set(name, r);
    await this.connected(r, o.timeoutMs ?? 60_000);
    return this.snapshot(r, 10);
  }

  /** Resolves once the runner's agent is connected; fails if the process exits or the timeout passes first. */
  private async connected(r: Runner, timeoutMs: number): Promise<void> {
    const deadline = Date.now() + timeoutMs;
    while (r.agent === undefined) {
      if (r.exitCode !== undefined) {
        throw new CraftwireError("RUNNER_CRASHED", `The bot runner exited (code ${r.exitCode}) before it connected`,
          "Read logTail for the cause.", { logTail: r.log.toArray().slice(-30) });
      }
      if (Date.now() > deadline) {
        throw new CraftwireError("TIMEOUT", "The bot runner did not connect in time", "botrunner_process {action:'status'} shows its log.");
      }
      await new Promise((res) => setTimeout(res, 200));
    }
  }

  async stop(name?: string): Promise<{ stopped: string[] }> {
    const targets = name !== undefined ? [this.require(name)] : [...this.runners.values()];
    await Promise.all(targets.map((r) => this.stopOne(r)));
    return { stopped: targets.map((r) => r.name) };
  }

  /** A shutdown hook in the runner removes its bots on SIGTERM; a runner that hangs is killed after 15 s. */
  private async stopOne(r: Runner): Promise<void> {
    r.child.kill();
    let timer: NodeJS.Timeout | undefined;
    const timedOut = await Promise.race([
      r.exited.then(() => false),
      new Promise<boolean>((res) => { timer = setTimeout(() => res(true), 15_000); }),
    ]);
    clearTimeout(timer);
    if (timedOut) {
      killTree(r.child.pid);
      await r.exited;
    }
  }

  status(tail = 20): { runners: Record<string, unknown>[] } {
    return { runners: [...this.runners.values()].map((r) => this.snapshot(r, tail)) };
  }

  async shutdown(): Promise<void> {
    await this.stop().catch(() => undefined);
  }

  private require(name: string): Runner {
    const r = this.runners.get(name);
    if (r === undefined) {
      throw new CraftwireError("RUNNER_NOT_RUNNING", `No runner named ${name}`, `Running: ${[...this.runners.keys()].join(", ") || "none"}.`);
    }
    return r;
  }

  private snapshot(r: Runner, tail: number): Record<string, unknown> {
    return {
      name: r.name, instance: r.agent ?? null, pid: r.child.pid, startedAt: r.startedAt,
      logTail: tail > 0 ? r.log.toArray().slice(-tail) : [],
    };
  }
}
