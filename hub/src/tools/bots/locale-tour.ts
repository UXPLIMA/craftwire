/**
 * locale_tour: one bot per locale (its client settings say that locale) opens each of the plugin's menus and runs
 * its commands, and the texts it sees are compared with the base locale's. A text identical to the base one is
 * probably untranslated; a text that looks like a message key or an unfilled placeholder is a missing translation.
 */
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { CraftwireError } from "../../errors.js";
import { defineTool, ok, targetArgs, type ToolContext } from "../registry.js";
import { actionTimeout } from "./bot-tools.js";
import { directoryFor } from "./directory.js";
import { agentForBot } from "./route.js";
import { KINDS, NAME } from "./schemas.js";
import { spawnOn } from "./spawn.js";

const target = z.object({
  name: z.string().max(64).optional(),
  command: z.string().max(256).optional(),
  block: z.object({ x: z.number().int(), y: z.number().int(), z: z.number().int() }).optional(),
});
type Target = z.infer<typeof target>;

/** Texts by where they were seen ("menu title", "slot 12 lore 1", "message 0", "sidebar 2"). */
type Seen = Map<string, string>;

/** A dotted message key (menu.shop.title), a MiniMessage or lang tag, or a placeholder left unfilled. */
const RAW_KEY = /^[a-z0-9_-]+(\.[a-z0-9_-]+){1,}$|<lang:|<\/?[a-z_]+>|\{\d+\}|%[sd]|\$\{[^}]*\}|\{[a-z_]+\}/;
/** Texts with no letters (numbers, symbols, item counts) say nothing about a translation. */
const WORDY = /\p{L}{2,}/u;

export function registerLocaleTools(server: McpServer, ctx: ToolContext): void {
  defineTool(server, ctx, "locale_tour",
    "Find untranslated texts: per locale a bot (client locale set) opens each target (command or block) and reads menu titles, item "
      + "names and lore, chat replies and the HUD. Texts equal to the base locale's, or looking like keys/placeholders, are listed.",
    {
      ...targetArgs,
      locales: z.array(z.string().regex(/^[a-z]{2,3}_[a-z]{2,3}$/)).min(1).max(30),
      base: z.string().regex(/^[a-z]{2,3}_[a-z]{2,3}$/).default("en_us"),
      targets: z.array(target).min(1).max(50),
      kind: z.enum(KINDS).default("plugin"),
      address: z.string().optional(),
      location: z.object({ world: z.string().optional(), x: z.number(), y: z.number(), z: z.number() }).optional(),
      hud: z.boolean().default(true),
      waitMs: z.number().int().min(100).max(10_000).default(800),
    },
    async (args, c) => ok(await tour(c, args)));
}

interface TourArgs {
  instance?: string | undefined;
  locales: string[];
  base: string;
  targets: Target[];
  kind: (typeof KINDS)[number];
  address?: string | undefined;
  location?: { world?: string | undefined; x: number; y: number; z: number } | undefined;
  hud: boolean;
  waitMs: number;
}

async function tour(c: ToolContext, a: TourArgs) {
  for (const t of a.targets) {
    if ((t.command === undefined) === (t.block === undefined)) {
      throw new CraftwireError("INVALID_PARAMS", "Each target needs command or block (one of them)", "e.g. {name: \"shop\", command: \"shop\"}.");
    }
  }
  const locales = [a.base, ...a.locales.filter((l) => l !== a.base)];
  const seen = new Map<string, Seen>();
  for (const locale of locales) seen.set(locale, await visit(c, a, locale));
  const baseSeen = seen.get(a.base)!;
  const results = locales.filter((l) => l !== a.base).map((locale) => compare(locale, seen.get(locale)!, baseSeen));
  const rawInBase = [...baseSeen].filter(([, text]) => RAW_KEY.test(text)).map(([where, text]) => ({ where, text }));
  return { base: a.base, texts: baseSeen.size, ...(rawInBase.length > 0 ? { rawKeysInBase: rawInBase.slice(0, 50) } : {}), locales: results };
}

function compare(locale: string, mine: Seen, base: Seen) {
  const untranslated: { where: string; text: string }[] = [];
  const rawKeys: { where: string; text: string }[] = [];
  const missing: string[] = [];
  for (const [where, text] of base) {
    const t = mine.get(where);
    if (t === undefined) missing.push(where);
    else if (t === text && WORDY.test(t)) untranslated.push({ where, text: t });
  }
  for (const [where, text] of mine) if (RAW_KEY.test(text)) rawKeys.push({ where, text });
  return {
    locale, texts: mine.size, untranslated: untranslated.slice(0, 100), rawKeys: rawKeys.slice(0, 100),
    // A text the base showed that this locale never showed: a menu laid out differently, or a reply that did not come.
    ...(missing.length > 0 ? { notSeen: missing.slice(0, 30) } : {}),
  };
}

/** Spawns the locale's bot, walks every target with it and removes it. */
async function visit(c: ToolContext, a: TourArgs, locale: string): Promise<Seen> {
  const name = `Tour_${locale}`.replace(/[^A-Za-z0-9_]/g, "").slice(0, 16);
  if (!NAME.test(name)) throw new CraftwireError("INVALID_PARAMS", `Cannot name a bot for locale ${locale}`, "Use locale codes like tr_tr.");
  await spawnOn(c, {
    kind: a.kind, count: 1, namePrefix: "Tour", names: [name], settings: { locale },
    ...(a.address !== undefined ? { address: a.address } : {}), ...(a.location !== undefined ? { location: a.location } : {}),
    ...(a.kind === "plugin" && a.instance !== undefined ? { instance: a.instance } : {}),
  });
  const home = await agentForBot(c, name, a.kind, a.kind === "plugin" ? a.instance : undefined);
  const act = (args: Record<string, unknown>) =>
    c.agents.request(home.id, "bot.action", { bot: name, ...args }, actionTimeout(args as { action: string }));
  const seen: Seen = new Map();
  try {
    // Plugins often read the locale once the client settings arrive, a moment after the join.
    await new Promise<void>((r) => setTimeout(r, 1000));
    for (const [i, t] of a.targets.entries()) await readTarget(act, t, t.name ?? `target ${i + 1}`, a, seen);
  } finally {
    await c.agents.request(home.id, "bot.remove", { bot: name }, 60_000).catch(() => undefined);
    directoryFor(c.agents).forget([name]);
  }
  return seen;
}

type Act = (args: Record<string, unknown>) => Promise<unknown>;
type Slot = { slot: number; name?: string; lore?: string[]; container?: string };

async function readTarget(act: Act, t: Target, label: string, a: TourArgs, seen: Seen): Promise<void> {
  const started = Date.now();
  if (t.command !== undefined) await act({ action: "command", command: t.command, collectMs: 0 });
  else await act({ action: "use", block: t.block });
  await new Promise<void>((r) => setTimeout(r, a.waitMs));
  const gui = (await act({ action: "gui_read" })) as { open?: boolean; title?: string; slots?: Slot[] };
  if (gui.open) {
    put(seen, `${label}: menu title`, gui.title);
    for (const s of gui.slots ?? []) {
      if (s.container !== "menu") continue;
      put(seen, `${label}: slot ${s.slot} name`, s.name);
      (s.lore ?? []).forEach((line, n) => put(seen, `${label}: slot ${s.slot} lore ${n}`, line));
    }
    await act({ action: "gui_close" }).catch(() => undefined);
  }
  const inbox = (await act({ action: "messages", since: started, limit: 50 })) as { messages?: { text?: string }[] };
  (inbox.messages ?? []).forEach((m, n) => put(seen, `${label}: message ${n}`, m.text));
  if (a.hud) readHud((await act({ action: "hud_read" })) as Hud, label, seen);
}

type Hud = {
  title?: string | null; subtitle?: string | null; actionbar?: string | null;
  bossbars?: { name?: string }[]; sidebar?: { title?: string; entries?: { name?: string; shown?: string | null }[] } | null;
};

function readHud(h: Hud, label: string, seen: Seen): void {
  put(seen, `${label}: title`, h.title);
  put(seen, `${label}: subtitle`, h.subtitle);
  put(seen, `${label}: action bar`, h.actionbar);
  (h.bossbars ?? []).forEach((b, n) => put(seen, `${label}: boss bar ${n}`, b.name));
  put(seen, `${label}: sidebar title`, h.sidebar?.title);
  (h.sidebar?.entries ?? []).forEach((e, n) => put(seen, `${label}: sidebar ${n}`, e.shown ?? e.name));
}

function put(seen: Seen, where: string, text: string | null | undefined): void {
  if (typeof text === "string" && text.trim().length > 0) seen.set(where, text.trim());
}
