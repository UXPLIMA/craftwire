/** The zod shapes of the bot tools: selectors, spawn options and the bot_action parameters of every action. */
import { z } from "zod";

export const NAME = /^[A-Za-z0-9_]{3,16}$/;
export const KINDS = ["plugin", "java", "bedrock"] as const;

export const CLICKS = ["left", "right", "shift", "number", "offhand", "drop", "drop_stack", "middle", "double", "drag", "outside"] as const;

export const ACTIONS = [
  "chat", "command", "messages", "look", "move_to", "stop", "state", "give", "select_hotbar", "settings", "resource_packs",
  "gui_read", "gui_click", "gui_close", "move_item", "transfer", "equip", "use", "attack", "hud_read",
  "break_block", "jump", "sneak", "sprint", "drop", "swap_hands",
  "anvil_rename", "sign_edit", "book_edit", "trade_select", "menu_button", "beacon", "recipe_place", "creative_set",
  "dialog_answer", "chat_click",
] as const;

export const BEHAVIORS = ["wander", "follow", "patrol", "chat", "command_loop", "menu_click", "idle", "script"] as const;

const blockPos = z.object({ x: z.number().int(), y: z.number().int(), z: z.number().int() });

/** Which bots: one name, several names or a pattern ("Load*"), or a spawn group. */
export const selector = {
  bot: z.string().min(1).optional(),
  bots: z.union([z.string().min(1).max(32), z.array(z.string().regex(NAME)).max(1000)]).optional(),
  group: z.string().regex(/^[A-Za-z0-9_-]{1,32}$/).optional(),
};

export const settings = z.object({
  locale: z.string().optional(),
  brand: z.string().max(64).optional(),
  ping: z.number().int().min(0).max(10_000).optional(),
  skin: z.string().url().optional(),
  mainHand: z.enum(["left", "right"]).optional(),
  chat: z.enum(["full", "system", "hidden"]).optional(),
  skinParts: z.number().int().min(0).max(127).optional(),
  viewDistance: z.number().int().min(2).max(32).optional(),
});

/** bot_spawn's listed fields. */
export const spawnShape = {
  kind: z.enum(KINDS).default("plugin"),
  count: z.number().int().min(1).max(1000).default(1),
  namePrefix: z.string().regex(/^[A-Za-z0-9_]{1,13}$/).default("Bot"),
  names: z.array(z.string().regex(NAME)).min(1).max(1000).optional(),
  group: z.string().regex(/^[A-Za-z0-9_-]{1,32}$/).optional(),
  location: z.object({
    world: z.string().optional(), x: z.number(), y: z.number(), z: z.number(), yaw: z.number().optional(), pitch: z.number().optional(),
  }).optional(),
  // Protocol bots (kind java or bedrock), from a bot runner:
  address: z.string().min(1).max(255).optional(),
  account: z.string().optional(),
};

/** bot_spawn's other fields, checked but not listed (craftwire://docs/tools). */
export const spawnExtras = {
  profile: z.enum(["light", "realistic"]).optional(),
  perTick: z.number().int().min(1).max(100).optional(),
  spread: z.object({ radius: z.number().int().min(1).max(10_000) }).optional(),
  settings: settings.optional(),
  resourcePack: z.enum(["accept", "decline", "fail_download", "invalid_url", "fail_reload", "discard", "ignore"]).optional(),
  verifyPacks: z.boolean().optional(),
  hideBots: z.boolean().optional(),
  detail: z.boolean().optional(),
  seed: z.number().int().optional(),
  version: z.string().optional(),
  auth: z.enum(["offline", "microsoft"]).optional(),
  worldMode: z.enum(["shared", "none"]).optional(),
  netsim: z.object({ latencyMs: z.number().int().min(0).max(5000).optional(), jitterMs: z.number().int().min(0).max(5000).optional(),
    lossPercent: z.number().min(0).max(100).optional() }).optional(),
  runner: z.string().optional(),
};

/** bot_action's listed fields: the ones most actions use. */
export const actionShape = {
  ...selector,
  kind: z.enum(KINDS).optional(),
  action: z.enum(ACTIONS),
  text: z.string().max(256).optional(),
  command: z.string().max(32_000).optional(),
  x: z.number().optional(), y: z.number().optional(), z: z.number().optional(),
  sprint: z.boolean().optional(),
  timeoutMs: z.number().int().min(500).max(120_000).optional(),
  item: z.string().optional(),
  count: z.number().int().min(1).max(2304).optional(),
  slot: z.number().int().optional(),
  click: z.enum(CLICKS).optional(),
  key: z.number().int().min(1).max(9).optional(),
  button: z.union([z.string(), z.number().int()]).optional(),
  slots: z.array(z.number().int().min(0)).max(64).optional(),
  block: blockPos.optional(),
  entity: z.string().optional(),
};

/** bot_action's other fields, checked but not listed (craftwire://docs/tools has them per action). */
export const actionExtras = {
  collectMs: z.number().int().min(0).max(5000).optional(),
  since: z.number().optional(),
  limit: z.number().int().min(1).max(200).optional(),
  yaw: z.number().optional(), pitch: z.number().optional(),
  tolerance: z.number().min(0.1).max(5).optional(),
  path: z.boolean().optional(),
  maxFall: z.number().int().min(0).max(10).optional(),
  openDoors: z.boolean().optional(),
  partial: z.boolean().optional(),
  on: z.boolean().optional(),
  all: z.boolean().optional(),
  from: z.union([z.number().int().min(0), z.enum(["container", "inventory"])]).optional(),
  to: z.union([z.number().int().min(0), z.enum(["hand", "offhand", "head", "chest", "legs", "feet"])]).optional(),
  inventory: z.boolean().optional(),
  settleMs: z.number().int().min(0).max(5000).optional(),
  hand: z.enum(["main", "off"]).optional(),
  face: z.enum(["up", "down", "north", "south", "east", "west"]).optional(),
  type: z.string().optional(),
  settings: settings.optional(),
};

/** The special screens' fields (anvil, sign, book, merchant, beacon, recipe book, dialogs, chat clicks), bots and clients. */
export const screenFields = {
  lines: z.array(z.string().max(384)).max(4).optional(),
  side: z.enum(["front", "back"]).optional(),
  pages: z.array(z.string().max(1024)).max(100).optional(),
  title: z.string().max(32).optional(),
  index: z.number().int().min(0).max(255).optional(),
  id: z.union([z.number().int().min(0).max(255), z.string()]).optional(),
  primary: z.string().optional(),
  secondary: z.string().optional(),
  recipe: z.number().int().min(0).optional(),
  inputs: z.record(z.string(), z.string()).optional(),
  message: z.number().int().optional(),
  match: z.string().max(256).optional(),
  part: z.number().int().min(0).max(64).optional(),
};

export const behaviorShape = {
  ...selector,
  kind: z.enum(KINDS).optional(),
  action: z.enum(["set", "stop", "status"]).default("set"),
  type: z.enum(BEHAVIORS).optional(),
  params: z.record(z.string(), z.unknown()).optional(),
  seed: z.number().int().optional(),
};
