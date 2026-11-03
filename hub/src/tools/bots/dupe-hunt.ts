/**
 * dupe_hunt: a bot tries odd click sequences on a menu of the user's own server and counts the items it owns before
 * and after each one: its whole inventory, the cursor, the menu (for real containers) and the dropped items around
 * it. Items that appear from nowhere are a possible dupe; items that vanish are a loss bug. Plugin bots count the
 * server's inventory; protocol bots their synced copy, re-read after the menu closes.
 */
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import type { InstanceInfo } from "../../agents.js";
import { CraftwireError } from "../../errors.js";
import { defineTool, ok, targetArgs, type ToolContext } from "../registry.js";
import { actionTimeout } from "./bot-tools.js";
import type { BotKind } from "./directory.js";
import { type MenuLayout, SEQUENCE_NAMES, SEQUENCES } from "./dupe-sequences.js";
import { agentForBot } from "./route.js";
import { KINDS } from "./schemas.js";

export function registerDupeTools(server: McpServer, ctx: ToolContext): void {
  defineTool(server, ctx, "dupe_hunt",
    "On your own test server: a bot opens a menu (open {command} or {block}; none = its inventory) and tries odd click sequences "
      + "(spam, close races, number/off-hand swaps, drags, double clicks, middle clicks), counting its items, cursor, dropped items "
      + "and (countMenu, for real containers) the menu before and after. Reports items gained (possible dupes) or lost. Items stay.",
    {
      ...targetArgs,
      bot: z.string().min(1),
      kind: z.enum(KINDS).optional(),
      open: z.object({
        command: z.string().optional(),
        block: z.object({ x: z.number().int(), y: z.number().int(), z: z.number().int() }).optional(),
      }).optional(),
      sequences: z.array(z.string()).min(1).optional(),
      rounds: z.number().int().min(1).max(20).default(2),
      countMenu: z.boolean().default(false),
      dropRadius: z.number().int().min(0).max(16).default(6),
      settleMs: z.number().int().min(0).max(5000).default(400),
    },
    async (args, c) => {
      const { instance, sequences, kind, open, ...rest } = args;
      const r = await huntDupes(c, {
        ...rest, sequences: sequences ?? SEQUENCE_NAMES,
        ...(kind !== undefined ? { kind } : {}), ...(instance !== undefined ? { instance } : {}),
        ...(open !== undefined ? { open: openOf(open) } : {}),
      });
      return ok({ ...r, suspicious: r.findings.length, sequences: sequences ?? SEQUENCE_NAMES });
    });
}

function openOf(o: { command?: string | undefined; block?: { x: number; y: number; z: number } | undefined }): HuntOptions["open"] {
  if (o.command !== undefined) return { command: o.command };
  if (o.block !== undefined) return { block: o.block };
  throw new CraftwireError("INVALID_PARAMS", "open needs command or block", "e.g. open: {command: \"shop\"} or {block: {x, y, z}}.");
}

export interface HuntOptions {
  bot: string;
  kind?: BotKind;
  instance?: string;
  /** How to open the menu under test: a command, or using a block. Absent: the bot's own inventory. */
  open?: { command?: string; block?: { x: number; y: number; z: number } };
  sequences: string[];
  rounds: number;
  /** Count the menu's own slots too: right for chests and other real containers, wrong for plugin GUIs (display items). */
  countMenu: boolean;
  /** Blocks around the bot searched for dropped items; 0 counts none. */
  dropRadius: number;
  settleMs: number;
}

export interface Finding {
  sequence: string;
  round: number;
  gained: Record<string, number>;
  lost: Record<string, number>;
  errors: string[];
}

type Ledger = Map<string, number>;
type Item = { id?: string; count?: number; container?: string };
type Act = (args: Record<string, unknown>) => Promise<unknown>;

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));
const norm = (id: string) => (id.startsWith("minecraft:") ? id.slice("minecraft:".length) : id);

export async function huntDupes(c: ToolContext, o: HuntOptions): Promise<{ tried: number; findings: Finding[]; notes: string[] }> {
  const unknown = o.sequences.filter((s) => SEQUENCES[s] === undefined);
  if (unknown.length > 0) {
    throw new CraftwireError("INVALID_PARAMS", `Unknown sequence ${unknown.join(", ")}`, `Sequences: ${Object.keys(SEQUENCES).join(", ")}.`);
  }
  const home = await agentForBot(c, o.bot, o.kind, o.instance);
  const act: Act = (args) => c.agents.request(home.id, "bot.action", { bot: o.bot, ...args }, actionTimeout(args as { action: string }));
  const world = dropCounter(c, home, o.dropRadius);
  const notes = world ? [] : ["No Paper server with the Craftwire plugin to count dropped items: drops count as lost."];
  const findings: Finding[] = [];
  let tried = 0;
  for (const name of o.sequences) {
    for (let round = 1; round <= o.rounds; round++) {
      tried++;
      const f = await tryOnce(act, world, o, name, round);
      if (f) findings.push(f);
    }
  }
  return { tried, findings, notes };
}

async function tryOnce(act: Act, world: DropCounter | undefined, o: HuntOptions, name: string, round: number): Promise<Finding | undefined> {
  const errors: string[] = [];
  await open(act, o);
  const layout = await readLayout(act);
  const before = await count(act, world, o.countMenu);
  for (const step of SEQUENCES[name]!(layout)) {
    try {
      if ("pause" in step) await sleep(Number(step.pause));
      else if ("reopen" in step) await open(act, o);
      else await act(step as Record<string, unknown>);
    } catch (e) {
      // A refused click (the plugin closed the menu, a slot vanished) is part of what is being tried.
      errors.push(e instanceof Error ? e.message : String(e));
    }
  }
  await sleep(o.settleMs);
  await act({ action: "gui_close" }).catch(() => undefined);
  await sleep(o.settleMs);
  const after = await count(act, world, false);
  const ledger = o.countMenu ? await withMenuAgain(act, o, after) : after;
  const { gained, lost } = diff(before, ledger);
  if (Object.keys(gained).length === 0 && Object.keys(lost).length === 0) return undefined;
  return { sequence: name, round, gained, lost, errors: errors.slice(0, 5) };
}

/** After the close, a real container's contents are counted by opening it again. */
async function withMenuAgain(act: Act, o: HuntOptions, ledger: Ledger): Promise<Ledger> {
  await open(act, o);
  const gui = (await act({ action: "gui_read" })) as { open?: boolean; slots?: Item[] };
  if (gui.open) for (const s of gui.slots ?? []) if (s.container === "menu") add(ledger, s);
  await act({ action: "gui_close" }).catch(() => undefined);
  return ledger;
}

async function open(act: Act, o: HuntOptions): Promise<void> {
  if (o.open === undefined) return;
  if (o.open.command !== undefined) await act({ action: "command", command: o.open.command, collectMs: 0 });
  else if (o.open.block !== undefined) await act({ action: "use", block: o.open.block });
  for (let i = 0; i < 15; i++) {
    const gui = (await act({ action: "gui_read" })) as { open?: boolean };
    if (gui.open) return;
    await sleep(200);
  }
  throw new CraftwireError("MENU_NOT_OPEN", "The menu did not open within 3 s", "Check open (command or block) with bot_action gui_read first.");
}

async function readLayout(act: Act): Promise<MenuLayout> {
  const gui = (await act({ action: "gui_read", inventory: true })) as { slots?: (Item & { slot: number })[]; slotCount?: number; open?: boolean };
  const slots = gui.slots ?? [];
  const slotCount = gui.slotCount ?? 0;
  const topSize = gui.open ? Math.max(0, slotCount - 36) : 9;
  const filled = new Set(slots.map((s) => s.slot));
  const empty: number[] = [];
  for (let s = topSize; s < slotCount && empty.length < 4; s++) if (!filled.has(s)) empty.push(s);
  return {
    menu: slots.filter((s) => s.container === "menu").map((s) => s.slot),
    player: slots.filter((s) => s.container !== "menu").map((s) => s.slot),
    empty,
    topSize,
  };
}

async function count(act: Act, world: DropCounter | undefined, withMenu: boolean): Promise<Ledger> {
  const ledger: Ledger = new Map();
  const state = (await act({ action: "state" })) as { inventory?: Item[]; world?: string; x?: number; y?: number; z?: number };
  for (const it of state.inventory ?? []) add(ledger, it);
  const gui = (await act({ action: "gui_read" })) as { open?: boolean; slots?: Item[]; carried?: Item | null };
  if (gui.carried) add(ledger, gui.carried);
  if (withMenu && gui.open) for (const s of gui.slots ?? []) if (s.container === "menu") add(ledger, s);
  if (world && state.x !== undefined && state.y !== undefined && state.z !== undefined) {
    for (const it of await world(state.world, state.x, state.y, state.z)) add(ledger, it);
  }
  return ledger;
}

function add(ledger: Ledger, it: Item): void {
  if (typeof it.id !== "string" || typeof it.count !== "number" || norm(it.id) === "air") return;
  ledger.set(norm(it.id), (ledger.get(norm(it.id)) ?? 0) + it.count);
}

export function diff(before: Ledger, after: Ledger): { gained: Record<string, number>; lost: Record<string, number> } {
  const gained: Record<string, number> = {};
  const lost: Record<string, number> = {};
  for (const id of new Set([...before.keys(), ...after.keys()])) {
    const d = (after.get(id) ?? 0) - (before.get(id) ?? 0);
    if (d > 0) gained[id] = d;
    if (d < 0) lost[id] = -d;
  }
  return { gained, lost };
}

type DropCounter = (world: string | undefined, x: number, y: number, z: number) => Promise<Item[]>;

/** Dropped items near the bot, from the server's world (its own agent for plugin bots, else the one Paper agent). */
function dropCounter(c: ToolContext, home: InstanceInfo, radius: number): DropCounter | undefined {
  if (radius <= 0) return undefined;
  const servers = c.agents.instances().filter((i) => i.kind === "server");
  const server = home.kind === "server" ? home : servers.length === 1 ? servers[0] : undefined;
  if (server === undefined) return undefined;
  return async (world, x, y, z) => {
    const box = (d: number) => ({ x: Math.floor(x) + d, y: Math.floor(y) + d, z: Math.floor(z) + d });
    const r = (await c.agents.request(server.id, "world.query", {
      action: "entities", type: "minecraft:item", min: box(-radius), max: box(radius), limit: 1000, ...(world ? { world } : {}),
    }, 30_000)) as { entities?: { item?: string; count?: number }[] };
    return (r.entities ?? []).map((e) => ({ ...(e.item !== undefined ? { id: e.item } : {}), ...(e.count !== undefined ? { count: e.count } : {}) }));
  };
}
