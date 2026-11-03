/**
 * The click sequences dupe_hunt tries on a menu: the orders and timings that have duplicated or lost items in GUI
 * plugins and containers before (clicks with no settle time, clicks racing a close, number-key and off-hand swaps
 * out of a menu, drag splits, double-click collects, creative middle clicks in survival). Each builds bot_action
 * calls from what the menu holds.
 */

/** What the menu holds right before a sequence: raw slots with items in the menu and the player part, empty player slots. */
export interface MenuLayout {
  menu: number[];
  player: number[];
  empty: number[];
  topSize: number;
}

/** A bot_action call (without bot), a pause, or "reopen the menu now". */
export type SequenceStep = Record<string, unknown> | { pause: number } | { reopen: true };

const click = (slot: number, kind: string, extra: Record<string, unknown> = {}): SequenceStep =>
  ({ action: "gui_click", slot, click: kind, settleMs: 0, ...extra });
const close: SequenceStep = { action: "gui_close" };

export const SEQUENCES: Record<string, (m: MenuLayout) => SequenceStep[]> = {
  shift_spam: (m) => Array.from({ length: 8 }, () => click(menuSlot(m), "shift")),
  number_keys: (m) => Array.from({ length: 9 }, (_, i) => click(menuSlot(m), "number", { key: i + 1 })),
  offhand_swap: (m) => [click(menuSlot(m), "offhand"), click(menuSlot(m), "offhand"), click(menuSlot(m), "offhand")],
  double_collect: (m) => [click(playerSlot(m), "left"), click(playerSlot(m), "double"), click(emptySlot(m, 0), "left")],
  drag_split: (m) => [
    click(playerSlot(m), "left"),
    // A drag visits each slot once: the fallbacks for a full inventory can repeat a slot.
    { action: "gui_click", click: "drag", button: "left", slots: [...new Set([menuSlot(m), emptySlot(m, 0), emptySlot(m, 1)])], settleMs: 0 },
    click(emptySlot(m, 2), "left"),
  ],
  click_then_close: (m) => [click(menuSlot(m), "left"), close, { reopen: true }],
  close_race: (m) => [click(menuSlot(m), "shift"), close, { reopen: true }, click(menuSlot(m), "shift")],
  drop_from_menu: (m) => [click(menuSlot(m), "drop"), click(menuSlot(m), "drop_stack")],
  middle_clone: (m) => [click(menuSlot(m), "middle"), click(emptySlot(m, 0), "left")],
  outside_drop: (m) => [click(playerSlot(m), "left"), { action: "gui_click", click: "outside", settleMs: 0 }],
  right_split: (m) => [click(menuSlot(m), "right"), click(emptySlot(m, 0), "right"), click(menuSlot(m), "right"), click(emptySlot(m, 1), "left")],
  swap_into_menu: (m) => [click(playerSlot(m), "left"), click(menuSlot(m), "left"), click(playerSlot(m), "left"), click(emptySlot(m, 0), "left")],
  spam_with_pause: (m) => [click(menuSlot(m), "left"), { pause: 50 }, click(menuSlot(m), "left"), { pause: 50 }, click(menuSlot(m), "shift")],
};

export const SEQUENCE_NAMES = Object.keys(SEQUENCES);

/** The menu slot to attack: one that holds an item (a plugin's button or display item), else the first. */
function menuSlot(m: MenuLayout): number {
  return m.menu[0] ?? 0;
}

function playerSlot(m: MenuLayout): number {
  return m.player[0] ?? m.topSize;
}

function emptySlot(m: MenuLayout, n: number): number {
  return m.empty[n] ?? m.empty[0] ?? m.topSize + 26 - n;
}
