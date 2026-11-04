// Headless Bedrock players, for checking what a Geyser server sends the Bedrock edition: chat, titles, forms,
// the scoreboard, boss bars, the tab list and the resource packs. The bots speak the Bedrock protocol through
// the optional `bedrock-protocol` package, so a hub without it still runs and says how to add it.
import { CraftwireError } from "../errors.js";
import { RingBuffer } from "../ringbuffer.js";

/** What the bot keeps of a client connection; `bedrock-protocol` has no types of its own. */
interface BedrockClient {
  on(event: string, listener: (...args: any[]) => void): void;
  queue(name: string, params: Record<string, unknown>): void;
  disconnect(reason?: string): void;
  entityId?: bigint | number;
}

export interface SpawnOptions {
  name: string;
  host: string;
  port: number;
  version?: string;
  transport?: "raknet" | "nethernet";
  timeoutMs: number;
}

interface Line { at: number; type: string; source?: string; message: string; parameters?: string[] }
interface Form { id: number; at: number; form: unknown }
interface Objective { name: string; display: string; slot: string }
interface Score { objective: string; id: string; name: string; score: number }
interface BossBar { id: string; title: string; progress: number }

class BedrockBot {
  readonly lines = new RingBuffer<Line>(500);
  readonly forms = new Map<number, Form>();
  readonly titles = { title: "", subtitle: "", actionBar: "" };
  readonly objectives = new Map<string, Objective>();
  readonly scores = new Map<string, Score>();
  readonly bossBars = new Map<string, BossBar>();
  readonly players = new Map<string, string>();
  readonly commands: string[] = [];
  packs: { uuid: string; version: string; size: number }[] = [];
  spawned = false;
  closed: string | undefined;
  position: { x: number; y: number; z: number } | undefined;
  runtimeId: bigint | undefined;

  constructor(readonly name: string, readonly client: BedrockClient) {}
}

/** Loads `bedrock-protocol` on first use; it pulls a native RakNet library, so it is an optional dependency. */
let backend: "raknet-native" | "jsp-raknet" | undefined;

async function raknetBackend(): Promise<"raknet-native" | "jsp-raknet"> {
  if (backend === undefined) {
    try {
      const name = "raknet-native";
      await import(name);
      backend = "raknet-native";
    } catch {
      backend = "jsp-raknet";
    }
  }
  return backend;
}

async function protocol(): Promise<{ createClient(options: Record<string, unknown>): BedrockClient }> {
  try {
    const name = "bedrock-protocol";
    return (await import(name)) as { createClient(options: Record<string, unknown>): BedrockClient };
  } catch {
    throw new CraftwireError(
      "bedrock_unavailable",
      "Bedrock bots need the optional bedrock-protocol package",
      "Run `npm install bedrock-protocol` in the hub folder, then try again.",
    );
  }
}

function text(value: unknown): string {
  return typeof value === "string" ? value : "";
}

export class BedrockBots {
  private readonly bots = new Map<string, BedrockBot>();

  list(): { name: string; spawned: boolean; closed?: string }[] {
    return [...this.bots.values()].map((b) => ({ name: b.name, spawned: b.spawned, ...(b.closed ? { closed: b.closed } : {}) }));
  }

  async spawn(o: SpawnOptions): Promise<Record<string, unknown>> {
    if (this.bots.has(o.name)) throw new CraftwireError("bot_exists", `A Bedrock bot named ${o.name} is already here`, "Remove it first or pick another name.");
    const bp = await protocol();
    const client = bp.createClient({
      host: o.host,
      port: o.port,
      username: o.name,
      offline: true,
      // The native RakNet when its binary is built: Geyser's RakNet does not finish the handshake with the pure
      // JavaScript one, which is kept only for an install where the native build did not run.
      raknetBackend: await raknetBackend(),
      useRaknetWorkers: false,
      ...(o.version ? { version: o.version } : {}),
      ...(o.transport ? { transport: o.transport } : {}),
      connectTimeout: o.timeoutMs,
    });
    const bot = new BedrockBot(o.name, client);
    this.bots.set(o.name, bot);
    this.listen(bot);
    try {
      await new Promise<void>((resolve, reject) => {
        const timer = setTimeout(() => reject(new CraftwireError("bot_timeout", `${o.name} did not spawn within ${o.timeoutMs} ms`, "Check that Geyser listens on that host and port and accepts offline Bedrock logins on this development server.")), o.timeoutMs);
        client.on("spawn", () => { clearTimeout(timer); resolve(); });
        client.on("kick", (p: { message?: string }) => { clearTimeout(timer); reject(new CraftwireError("bot_kicked", `${o.name} was kicked: ${text(p?.message)}`, "Read the Geyser console for the reason.")); });
        client.on("error", (e: Error) => { clearTimeout(timer); reject(new CraftwireError("bot_error", `${o.name} could not connect: ${e.message}`, "Check the host, the port and the Bedrock version Geyser accepts.")); });
      });
    } catch (e) {
      this.remove(o.name);
      throw e;
    }
    return this.state(bot);
  }

  remove(name: string): boolean {
    const bot = this.bots.get(name);
    if (!bot) return false;
    this.bots.delete(name);
    try { bot.client.disconnect("removed"); } catch { /* already closed */ }
    return true;
  }

  removeAll(): number {
    const names = [...this.bots.keys()];
    for (const n of names) this.remove(n);
    return names.length;
  }

  action(name: string, action: string, args: Record<string, unknown>): Record<string, unknown> {
    const bot = this.bots.get(name);
    if (!bot) throw new CraftwireError("bot_not_found", `No Bedrock bot named ${name}`, "List them with bedrock_bot_action action:list or spawn one.");
    if (bot.closed && action !== "state") throw new CraftwireError("bot_closed", `${name} is disconnected: ${bot.closed}`, "Remove it and spawn it again.");
    switch (action) {
      case "state": return this.state(bot);
      case "chat": return this.chat(bot, String(args.text ?? ""));
      case "command": return this.command(bot, String(args.command ?? ""));
      case "messages": {
        const since = typeof args.since === "number" ? args.since : 0;
        const limit = typeof args.limit === "number" ? args.limit : 50;
        const lines = bot.lines.toArray().filter((l) => l.at > since).slice(-limit);
        return { lines, now: Date.now() };
      }
      case "titles": return { ...bot.titles };
      case "forms": return { forms: [...bot.forms.values()] };
      case "form_answer": return this.answer(bot, args);
      case "scoreboard": return this.scoreboard(bot);
      case "bossbars": return { bossBars: [...bot.bossBars.values()] };
      case "players": return { players: [...bot.players.values()] };
      case "packs": return { packs: bot.packs };
      case "commands": return { count: bot.commands.length, commands: bot.commands.slice(0, typeof args.limit === "number" ? args.limit : 200) };
      default: throw new CraftwireError("bad_action", `Unknown Bedrock bot action ${action}`, "See the tool description for the actions.");
    }
  }

  private state(bot: BedrockBot): Record<string, unknown> {
    return {
      name: bot.name,
      spawned: bot.spawned,
      ...(bot.closed ? { closed: bot.closed } : {}),
      ...(bot.position ? { position: bot.position } : {}),
      packs: bot.packs.length,
      openForms: bot.forms.size,
      lines: bot.lines.size,
    };
  }

  private chat(bot: BedrockBot, message: string): Record<string, unknown> {
    if (!message) throw new CraftwireError("bad_args", "chat needs text", "Pass text.");
    bot.client.queue("text", {
      needs_translation: false, category: "authored", type: "chat", source_name: bot.name, message,
      xuid: "", platform_chat_id: "", has_filtered_message: false,
    });
    return { sent: message };
  }

  private command(bot: BedrockBot, command: string): Record<string, unknown> {
    const line = command.startsWith("/") ? command : `/${command}`;
    if (line.length < 2) throw new CraftwireError("bad_args", "command needs a command", "Pass command, with or without the slash.");
    bot.client.queue("command_request", {
      command: line,
      origin: { type: "player", uuid: "00000000-0000-0000-0000-000000000000", request_id: "", player_entity_id: 0n },
      internal: false, version: "latest",
    });
    return { sent: line };
  }

  private answer(bot: BedrockBot, args: Record<string, unknown>): Record<string, unknown> {
    const id = typeof args.formId === "number" ? args.formId : [...bot.forms.keys()].at(-1);
    if (id === undefined || !bot.forms.has(id)) throw new CraftwireError("no_form", "No open form to answer", "Read the forms first; a form arrives a moment after the action that opens it.");
    bot.forms.delete(id);
    if (args.close === true) {
      bot.client.queue("modal_form_response", { form_id: id, has_response_data: false, has_cancel_reason: true, cancel_reason: "closed" });
      return { formId: id, closed: true };
    }
    // A simple form takes the button index, a modal form true or false, a custom form an array of values.
    const value = args.button ?? args.values ?? args.choice;
    if (value === undefined) throw new CraftwireError("bad_args", "form_answer needs button, values, choice or close", "button: index for a simple form; choice: true or false for a modal; values: array for a custom form.");
    bot.client.queue("modal_form_response", { form_id: id, has_response_data: true, data: JSON.stringify(value), has_cancel_reason: false });
    return { formId: id, answered: value };
  }

  private scoreboard(bot: BedrockBot): Record<string, unknown> {
    const objectives = [...bot.objectives.values()].map((o) => ({
      ...o,
      lines: [...bot.scores.values()].filter((s) => s.objective === o.name).sort((a, b) => b.score - a.score),
    }));
    return { objectives };
  }

  private listen(bot: BedrockBot): void {
    const c = bot.client;
    c.on("spawn", () => { bot.spawned = true; });
    c.on("close", () => { bot.closed ??= "connection closed"; });
    c.on("kick", (p: { message?: string }) => { bot.closed = `kicked: ${text(p?.message)}`; });
    c.on("start_game", (p: { player_position?: { x: number; y: number; z: number }; runtime_entity_id?: bigint }) => {
      if (p.player_position) bot.position = { ...p.player_position };
      if (p.runtime_entity_id !== undefined) bot.runtimeId = BigInt(p.runtime_entity_id);
    });
    c.on("move_player", (p: { runtime_id?: bigint | number; position?: { x: number; y: number; z: number } }) => {
      if (p.position && bot.runtimeId !== undefined && p.runtime_id !== undefined && BigInt(p.runtime_id) === bot.runtimeId) bot.position = { ...p.position };
    });
    c.on("text", (p: { type?: string; source_name?: string; message?: string; parameters?: string[] }) => {
      bot.lines.push({ at: Date.now(), type: text(p.type), ...(p.source_name ? { source: p.source_name } : {}), message: text(p.message), ...(p.parameters?.length ? { parameters: p.parameters } : {}) });
    });
    c.on("set_title", (p: { type?: string; text?: string }) => {
      switch (p.type) {
        case "set_title": case "set_title_json": bot.titles.title = text(p.text); break;
        case "set_subtitle": case "set_subtitle_json": bot.titles.subtitle = text(p.text); break;
        case "action_bar_message": case "action_bar_message_json": bot.titles.actionBar = text(p.text); break;
        case "clear": case "reset": bot.titles.title = ""; bot.titles.subtitle = ""; break;
        default: break;
      }
    });
    c.on("modal_form_request", (p: { form_id: number; data: string }) => {
      let form: unknown = p.data;
      try { form = JSON.parse(p.data); } catch { /* keep the raw text */ }
      bot.forms.set(p.form_id, { id: p.form_id, at: Date.now(), form });
    });
    c.on("set_display_objective", (p: { display_slot: string; objective_name: string; display_name: string }) => {
      bot.objectives.set(p.objective_name, { name: p.objective_name, display: p.display_name, slot: p.display_slot });
    });
    c.on("remove_objective", (p: { objective_name: string }) => {
      bot.objectives.delete(p.objective_name);
      for (const [k, s] of bot.scores) if (s.objective === p.objective_name) bot.scores.delete(k);
    });
    c.on("set_score", (p: { action?: string; entries?: { scoreboard_id: bigint | number; objective_name?: string; score?: number; custom_name?: string; entry_type?: string; entity_unique_id?: bigint }[] }) => {
      for (const e of p.entries ?? []) {
        const id = String(e.scoreboard_id);
        if (p.action === "remove" || e.entry_type === "remove") { bot.scores.delete(id); continue; }
        bot.scores.set(id, { objective: text(e.objective_name), id, name: text(e.custom_name), score: e.score ?? 0 });
      }
    });
    c.on("boss_event", (p: { boss_entity_id?: bigint; target_entity_id?: bigint; type?: string; title?: string; progress?: number }) => {
      const id = String(p.boss_entity_id ?? p.target_entity_id ?? "");
      if (p.type === "hide_bar") { bot.bossBars.delete(id); return; }
      const bar = bot.bossBars.get(id) ?? { id, title: "", progress: 0 };
      if (p.title !== undefined && p.title !== "") bar.title = p.title;
      if (typeof p.progress === "number") bar.progress = p.progress;
      bot.bossBars.set(id, bar);
    });
    c.on("player_list", (p: { records?: { type?: string; records_count?: number; records?: { uuid: string; username?: string }[] } }) => {
      const r = p.records;
      for (const e of r?.records ?? []) {
        if (r?.type === "remove") bot.players.delete(e.uuid);
        else bot.players.set(e.uuid, text(e.username));
      }
    });
    c.on("resource_packs_info", (p: { texture_packs?: { uuid: string; version: string; size: bigint | number }[] }) => {
      bot.packs = (p.texture_packs ?? []).map((t) => ({ uuid: t.uuid, version: t.version, size: Number(t.size) }));
    });
    c.on("available_commands", (p: { command_data?: { name: string }[] }) => {
      bot.commands.length = 0;
      for (const d of p.command_data ?? []) bot.commands.push(d.name);
    });
  }
}
