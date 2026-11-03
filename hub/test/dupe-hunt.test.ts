/**
 * dupe_hunt against a fake Paper agent that runs a shop menu on a small model of vanilla's click rules: a correct
 * menu must give no findings whatever is clicked (items moved, dropped or put back are still counted), and a menu
 * with a dupe must be reported for exactly the sequences that trigger it.
 */
import { afterEach, describe, expect, it } from "vitest";
import { connectFakeAgent } from "./helpers/fakeAgent.js";
import { json, startHub } from "./helpers/hub.js";

type Stack = { id: string; count: number };
const MENU = 27;
const VIEW = MENU + 36;

/** A plugin shop (27 display slots, every click on them cancelled) over the bot's 36 inventory slots. */
class Shop {
  readonly inv: (Stack | undefined)[] = Array(36).fill(undefined);
  cursor: Stack | undefined;
  readonly drops: Stack[] = [];
  open = false;

  constructor(private readonly dupeOnShift: boolean) {
    this.inv[0] = { id: "minecraft:diamond", count: 32 };
    this.inv[1] = { id: "minecraft:diamond", count: 20 };
    this.inv[5] = { id: "minecraft:bread", count: 3 };
  }

  action(p: Record<string, unknown>): unknown {
    switch (p.action) {
      case "command": this.open = true; return { sent: true };
      case "state": return { world: "world", x: 0.5, y: 64, z: 0.5, inventory: this.inv.filter((s) => s !== undefined) };
      case "gui_read": return this.read();
      case "gui_close": this.close(); return { closed: true };
      case "gui_click":
        if (!this.open) throw new Error("no menu open");
        this.click(String(p.click ?? "left"), Number(p.slot ?? -1), p);
        return { clicked: true };
      default: throw new Error(`unexpected action ${String(p.action)}`);
    }
  }

  private read(): unknown {
    if (!this.open) return { open: false };
    const slots = [{ slot: 13, id: "minecraft:emerald", count: 1, container: "menu" }];
    this.inv.forEach((s, i) => { if (s) slots.push({ slot: MENU + i, ...s, container: "player" }); });
    return { open: true, slotCount: VIEW, slots, carried: this.cursor ?? null };
  }

  private click(kind: string, slot: number, p: Record<string, unknown>): void {
    if (kind === "outside") return this.dropCursor();
    if (kind === "drag") {
      // The plugin cancels any drag that touches its menu, so the stack stays on the cursor.
      if ((p.slots as number[]).some((s) => s < MENU)) return;
      return this.left((p.slots as number[])[0]! - MENU);
    }
    if (slot < MENU) {
      if (kind === "shift" && this.dupeOnShift) this.give({ id: "minecraft:emerald", count: 1 });
      return;
    }
    const i = slot - MENU;
    switch (kind) {
      case "left": return this.left(i);
      case "right": return this.right(i);
      case "double": return this.collect();
      case "drop": return this.throwFrom(i, 1);
      case "drop_stack": return this.throwFrom(i, 64);
      case "number": return this.swap(i, 27 + Number(p.key) - 1);
      default: return; // shift inside the inventory, off hand and middle change nothing countable here
    }
  }

  private left(i: number): void {
    const s = this.inv[i];
    if (this.cursor === undefined) [this.cursor, this.inv[i]] = [s, undefined];
    else if (s === undefined) [this.inv[i], this.cursor] = [this.cursor, undefined];
    else if (s.id === this.cursor.id) {
      const moved = Math.min(this.cursor.count, 64 - s.count);
      s.count += moved;
      this.cursor = this.cursor.count === moved ? undefined : { ...this.cursor, count: this.cursor.count - moved };
    } else [this.inv[i], this.cursor] = [this.cursor, s];
  }

  private right(i: number): void {
    const s = this.inv[i];
    if (this.cursor === undefined) {
      if (s === undefined) return;
      const take = Math.ceil(s.count / 2);
      this.cursor = { id: s.id, count: take };
      this.inv[i] = s.count === take ? undefined : { id: s.id, count: s.count - take };
    } else if (s === undefined || (s.id === this.cursor.id && s.count < 64)) {
      this.inv[i] = { id: this.cursor.id, count: (s?.count ?? 0) + 1 };
      this.cursor = this.cursor.count === 1 ? undefined : { ...this.cursor, count: this.cursor.count - 1 };
    }
  }

  private collect(): void {
    if (this.cursor === undefined) return;
    this.inv.forEach((s, i) => {
      if (s === undefined || s.id !== this.cursor!.id || this.cursor!.count >= 64) return;
      const moved = Math.min(s.count, 64 - this.cursor!.count);
      this.cursor!.count += moved;
      this.inv[i] = s.count === moved ? undefined : { ...s, count: s.count - moved };
    });
  }

  private throwFrom(i: number, n: number): void {
    const s = this.inv[i];
    if (s === undefined) return;
    const k = Math.min(n, s.count);
    this.drops.push({ id: s.id, count: k });
    this.inv[i] = s.count === k ? undefined : { ...s, count: s.count - k };
  }

  private swap(i: number, hotbar: number): void {
    [this.inv[i], this.inv[hotbar]] = [this.inv[hotbar], this.inv[i]];
  }

  private dropCursor(): void {
    if (this.cursor) this.drops.push(this.cursor);
    this.cursor = undefined;
  }

  /** Closing puts the cursor stack back into the inventory, as the server does. */
  private close(): void {
    if (this.cursor) this.give(this.cursor);
    this.cursor = undefined;
    this.open = false;
  }

  private give(st: Stack): void {
    const free = this.inv.findIndex((s) => s === undefined);
    if (free >= 0) this.inv[free] = { ...st };
    else this.drops.push({ ...st });
  }
}

let hub: Awaited<ReturnType<typeof startHub>> | undefined;
afterEach(async () => {
  await hub?.close();
  hub = undefined;
});

async function hunt(shop: Shop, args: Record<string, unknown> = {}) {
  hub = await startHub();
  const agent = await connectFakeAgent(hub.port, { token: hub.token, kind: "server", name: "srv" });
  agent.onRequest("bot.action", (p) => shop.action(p));
  agent.onRequest("world.query", () => ({ entities: shop.drops.map((d) => ({ type: "minecraft:item", item: d.id, count: d.count })) }));
  await hub.call("more_tools", { enable: ["dupe_hunt"] });
  const r = await hub.call("dupe_hunt", { bot: "Hunter", open: { command: "shop" }, rounds: 1, settleMs: 0, ...args });
  if (r.isError) throw new Error(JSON.stringify(r.content));
  type Finding = { sequence: string; gained: Record<string, number>; lost: Record<string, number> };
  return json(r) as { tried: number; suspicious: number; findings: Finding[]; notes: string[] };
}

describe("dupe_hunt", () => {
  it("finds nothing on a correct menu, though items were moved, split, dropped and put back", async () => {
    const shop = new Shop(false);
    const r = await hunt(shop);
    expect(r.tried).toBe(13);
    expect(r.findings).toEqual([]);
    expect(shop.drops.length).toBeGreaterThan(0);   // the drop sequences did throw items out
  });

  it("reports a menu that hands out its display item, for exactly the sequences that shift-click it", async () => {
    const r = await hunt(new Shop(true));
    expect(r.findings.map((f) => f.sequence).sort()).toEqual(["close_race", "shift_spam", "spam_with_pause"]);
    const spam = r.findings.find((f) => f.sequence === "shift_spam")!;
    expect(spam.gained).toEqual({ emerald: 8 });
    expect(spam.lost).toEqual({});
  });

  it("without a server to count dropped items, says so and reports thrown items as lost", async () => {
    const r = await hunt(new Shop(false), { sequences: ["outside_drop"], dropRadius: 0 });
    expect(r.notes[0]).toMatch(/drops count as lost/);
    expect(r.findings[0]!.lost).toEqual({ diamond: 32 });
  });

  it("refuses a sequence it does not know", async () => {
    await expect(hunt(new Shop(false), { sequences: ["telepathy"] })).rejects.toThrow(/Unknown sequence telepathy/);
  });
});
