import { createHash } from "node:crypto";
import type { EventEmitter } from "node:events";
import type { AgentEvent } from "./protocol.js";

/** One throwable of a chain: "pkg.Type: message" and its frames ("pkg.Class.method(File.java:12)"). */
export interface ThrowablePart {
  type: string;
  message: string;
  frames: string[];
}

export interface ParsedThrowable extends ThrowablePart {
  /** The deepest "Caused by" (the throwable itself when there is none): where the bug actually is. */
  root: ThrowablePart;
}

const HEADER = /^(?:Caused by: |Suppressed: )?([a-zA-Z_$][\w$]*(?:\.[\w$]+)+)(?::\s?(.*))?$/;
const FRAME = /^\s*at\s+(.+)$/;
const MORE = /^\s*\.\.\. \d+ more$/;

/** A printed stack trace (Throwable.printStackTrace), or undefined when the text is not one. */
export function parseThrown(text: string): ParsedThrowable | undefined {
  const lines = text.split(/\r?\n/).filter((l) => l.trim() !== "");
  const first = HEADER.exec(lines[0]?.trim() ?? "");
  if (!first || lines.length < 2 || !FRAME.test(lines[1]!)) return undefined;
  const parts: ThrowablePart[] = [{ type: first[1]!, message: first[2] ?? "", frames: [] }];
  for (const line of lines.slice(1)) {
    const frame = FRAME.exec(line);
    if (frame) {
      parts[parts.length - 1]!.frames.push(frame[1]!.trim());
      continue;
    }
    if (MORE.test(line)) continue;
    const t = line.trim();
    if (t.startsWith("Caused by: ")) {
      const h = HEADER.exec(t);
      if (h) parts.push({ type: h[1]!, message: h[2] ?? "", frames: [] });
    }
    // "Suppressed:" sections and anything else stay with the part before them.
  }
  return { ...parts[0]!, root: parts[parts.length - 1]! };
}

// Frames of the game, the server, the loaders and common libraries: not where a plugin or mod bug is.
const PLATFORM = /^(java|javax|jdk|sun|com\.sun|net\.minecraft|com\.mojang|org\.bukkit|org\.spigotmc|io\.papermc|com\.destroystokyo|ca\.spottedleaf|net\.fabricmc|org\.spongepowered|io\.netty|it\.unimi|com\.google|org\.apache|org\.slf4j|org\.graalvm|org\.lwjgl|org\.objectweb)\./;

const withoutLine = (frame: string) => frame.replace(/\(.*\)$/, "");
const appFrames = (frames: string[]) => frames.filter((f) => !PLATFORM.test(f));

/** What identifies a bug: the root cause type and its first own frames, without line numbers (stable across rebuilds). */
export function signature(p: ParsedThrowable): string {
  const own = appFrames(p.root.frames);
  const frames = (own.length > 0 ? own : p.root.frames).slice(0, 3).map(withoutLine);
  return [p.root.type, ...frames].join("|");
}

/** The plugin a log line blames: Paper's dispatch messages name it; else a plugin's own logger. */
export function pluginOf(logger: string, message: string): string | undefined {
  const m = /(?:to|while enabling|while disabling|Plugin|loading) (\S+) v\S+/.exec(message);
  if (m) return m[1];
  if (/^[A-Za-z0-9_-]+$/.test(logger) && !["Minecraft", "STDERR", "STDOUT", "MinecraftServer", "root"].includes(logger)) return logger;
  return undefined;
}

/** A Folia thread violation: plugin code touched something its region thread does not own. */
export interface FoliaViolation {
  /** The first plugin/mod frame: the code that ran on the wrong thread. */
  owner?: string;
  touched: "block" | "chunk" | "entity" | "player" | "world" | "scheduler" | "unknown";
  fix: string;
}

const FOLIA_FIX: Record<FoliaViolation["touched"], string> = {
  block: "Change the block from its region: Bukkit.getRegionScheduler().run(plugin, location, task -> ...).",
  chunk: "Work on the chunk from its region: Bukkit.getRegionScheduler().run(plugin, world, chunkX, chunkZ, task -> ...).",
  entity: "Touch the entity from its own scheduler: entity.getScheduler().run(plugin, task -> ..., null).",
  player: "Touch the player from its own scheduler: player.getScheduler().run(plugin, task -> ..., null).",
  world: "Run world changes on the region that owns the location (Bukkit.getRegionScheduler()); server-wide state on Bukkit.getGlobalRegionScheduler().",
  scheduler: "Folia has no Bukkit scheduler: use Bukkit.getGlobalRegionScheduler(), Bukkit.getRegionScheduler(), entity.getScheduler() or Bukkit.getAsyncScheduler().",
  unknown: "Run the code on the thread that owns the data: the region scheduler for a location, entity.getScheduler() for an entity or player.",
};

/** Whether a trace is one of Folia's thread checks (or the Bukkit scheduler Folia lacks), and what it touched. */
export function foliaViolation(p: ParsedThrowable): FoliaViolation | undefined {
  const parts = p.root === p ? [p] : [p, p.root];
  const check = parts.find((t) => t.message.includes("Thread failed main thread check")
    || t.frames.some((f) => f.startsWith("ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread")));
  const scheduler = parts.find((t) => t.type === "java.lang.UnsupportedOperationException"
    && t.frames.some((f) => f.startsWith("org.bukkit.craftbukkit.scheduler.CraftScheduler.")));
  const hit = check ?? scheduler;
  if (!hit) return undefined;
  const touched: FoliaViolation["touched"] = check === undefined ? "scheduler" : touchedBy(hit.message);
  const v: FoliaViolation = { touched, fix: FOLIA_FIX[touched] };
  const owner = appFrames(hit.frames)[0];
  if (owner !== undefined) v.owner = owner;
  return v;
}

function touchedBy(message: string): FoliaViolation["touched"] {
  if (/entity=\S*Player|player/i.test(message)) return "player";
  if (/entity=|\bentity\b/i.test(message)) return "entity";
  if (/block_pos=|\bblock\b/i.test(message)) return "block";
  if (/chunk/i.test(message)) return "chunk";
  if (/\bworld\b/i.test(message)) return "world";
  return "unknown";
}

export interface ExceptionGroup {
  id: string;
  type: string;
  message: string;
  count: number;
  firstSeen: number;
  lastSeen: number;
  /** First frame outside the game/server/loaders: where to look. */
  origin?: string;
  plugin?: string;
  /** Set when the exception is a Folia thread violation. */
  folia?: FoliaViolation;
  /** Instance names (stable across reconnects) that logged it. */
  instances: string[];
}

export interface ExceptionDetail extends ExceptionGroup {
  /** The latest full stack trace. */
  stack: string;
  /** The log line that carried it. */
  logMessage: string;
  /** Up to 5 distinct root-cause messages seen for this bug. */
  messages: string[];
}

interface Group extends Omit<ExceptionDetail, "instances"> {
  instances: Set<string>;
}

interface Pending {
  name: string;
  time: number;
  logger: string;
  lines: string[];
}

const WARN_OR_WORSE = new Set(["WARN", "ERROR", "FATAL"]);
const CONTINUATION = /^(\s+at |\s*Caused by: |\s*Suppressed: |\s+\.\.\. \d+ more)/;

/**
 * Groups the exceptions agents log (client and server alike) into distinct bugs with counts, as log events arrive,
 * so counts survive the log ring buffer. Traces come either attached to a log line (`thrown`) or printed line by
 * line to stderr; both are recognised. A backlog an agent replays after reconnecting is not counted again.
 */
export class ExceptionTracker {
  private readonly groups = new Map<string, Group>();
  private readonly pending = new Map<string, Pending>();
  /** Newest log time seen per instance name, and what it was when each connection opened (its replay cut-off). */
  private readonly newest = new Map<string, number>();
  private readonly replayedUpTo = new Map<string, number>();

  constructor(agents: EventEmitter, private readonly nameOf: (instanceId: string) => string | undefined) {
    agents.on("connected", (i: { id: string; name: string }) => {
      const before = this.newest.get(i.name);
      if (before !== undefined) this.replayedUpTo.set(i.id, before);
    });
    agents.on("event", (id: string, ev: AgentEvent) => this.onEvent(id, ev));
  }

  list(o: { since?: number; instance?: string } = {}): ExceptionGroup[] {
    this.flushAll();
    return [...this.groups.values()]
      .filter((g) => o.since === undefined || g.lastSeen >= o.since)
      .filter((g) => o.instance === undefined || g.instances.has(o.instance))
      .sort((a, b) => b.lastSeen - a.lastSeen)
      .map((g) => this.summary(g));
  }

  get(id: string): ExceptionDetail | undefined {
    this.flushAll();
    const g = [...this.groups.values()].find((x) => x.id === id);
    return g === undefined ? undefined : { ...g, ...this.summary(g) };
  }

  private summary(g: Group): ExceptionGroup {
    const s: ExceptionGroup = {
      id: g.id, type: g.type, message: g.message, count: g.count, firstSeen: g.firstSeen, lastSeen: g.lastSeen,
      instances: [...g.instances],
    };
    if (g.origin !== undefined) s.origin = g.origin;
    if (g.plugin !== undefined) s.plugin = g.plugin;
    if (g.folia !== undefined) s.folia = g.folia;
    return s;
  }

  private onEvent(id: string, ev: AgentEvent): void {
    if (ev.type !== "log") return;
    const name = this.nameOf(id) ?? id;
    const d = ev.data as Record<string, unknown>;
    const level = String(d.level ?? "INFO");
    const logger = String(d.logger ?? "");
    const message = String(d.message ?? "");

    // A reconnecting agent first replays lines this hub already has: skip up to what was seen before.
    if (ev.time <= (this.replayedUpTo.get(id) ?? -Infinity)) return;
    this.newest.set(name, Math.max(this.newest.get(name) ?? -Infinity, ev.time));

    // A trace printed line by line: collect until the first line that does not continue it.
    const open = this.pending.get(name);
    if (open && CONTINUATION.test(message)) {
      open.lines.push(message);
      return;
    }
    if (open) this.flush(name);
    if (!WARN_OR_WORSE.has(level)) return;

    if (d.thrown !== undefined) {
      this.record(name, ev.time, logger, message, String(d.thrown));
    } else if (HEADER.test(message.trim())) {
      this.pending.set(name, { name, time: ev.time, logger, lines: [message] });
    }
  }

  private flushAll(): void {
    for (const name of [...this.pending.keys()]) this.flush(name);
  }

  private flush(name: string): void {
    const p = this.pending.get(name);
    this.pending.delete(name);
    if (p) this.record(p.name, p.time, p.logger, p.lines[0]!, p.lines.join("\n"));
  }

  private record(name: string, time: number, logger: string, logMessage: string, stack: string): void {
    const parsed = parseThrown(stack);
    if (!parsed) return;
    const sig = signature(parsed);
    const origin = appFrames(parsed.root.frames)[0] ?? appFrames(parsed.frames)[0];
    const plugin = pluginOf(logger, logMessage);
    let g = this.groups.get(sig);
    if (!g) {
      g = {
        id: createHash("sha1").update(sig).digest("hex").slice(0, 8), type: parsed.root.type, message: parsed.root.message,
        count: 0, firstSeen: time, lastSeen: time, instances: new Set(), stack, logMessage, messages: [],
      };
      this.groups.set(sig, g);
    }
    g.count++;
    g.firstSeen = Math.min(g.firstSeen, time);
    if (time >= g.lastSeen) {
      g.lastSeen = time;
      g.message = parsed.root.message;
      g.stack = stack;
      g.logMessage = logMessage;
    }
    g.instances.add(name);
    if (origin !== undefined) g.origin = origin;
    if (plugin !== undefined) g.plugin = plugin;
    const folia = foliaViolation(parsed);
    if (folia !== undefined) g.folia = folia;
    if (!g.messages.includes(parsed.root.message) && g.messages.length < 5) g.messages.push(parsed.root.message);
  }
}

/** The tracker for a hub: instance ids are mapped to their names (stable across reconnects) when events arrive. */
export function trackExceptions(agents: EventEmitter & { instances(): { id: string; name: string }[] }): ExceptionTracker {
  return new ExceptionTracker(agents, (id) => agents.instances().find((i) => i.id === id)?.name);
}
