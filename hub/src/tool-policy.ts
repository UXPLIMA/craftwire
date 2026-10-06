/** The tool groups `--tools` takes. Tools not listed here (added by plugins and mods) are the "extensions" group. */
export const TOOL_GROUPS: Record<string, readonly string[]> = {
  hub: ["list_instances", "get_request_status", "wait_for", "logs", "exceptions"],
  client: ["screenshot", "camera", "gui_read", "gui_action", "input", "chat", "hud_read", "player_state", "client_settings"],
  server: ["server_command", "server_eval", "world_query", "world_edit", "world_render", "server_info", "plugin_manage", "events"],
  dev: ["server_process", "plugin_deploy", "client_process", "scenario_run"],
  bots: ["bot_spawn", "bot_action", "bot_remove"],
  debug: ["profile", "trace", "client_eval"],
  extensions: [],
};

const BUILT_IN = new Set(Object.values(TOOL_GROUPS).flat());

/** Tools that only read. Extension tools are not here: what they do is unknown. */
const READS = new Set([
  "list_instances", "get_request_status", "wait_for", "logs", "exceptions",
  "screenshot", "gui_read", "hud_read", "player_state",
  "world_query", "world_render", "server_info", "profile", "trace",
]);

/** Tools that read for some actions only: the actions that change nothing. */
const READING_ACTIONS: Record<string, readonly string[]> = {
  server_process: ["status"],
  client_process: ["status"],
  plugin_manage: ["list", "info"],
  chat: ["read"],
  bot_action: ["messages", "state", "gui_read", "hud_read"],
  // watch only starts recording more event types.
  events: ["summary", "query", "listeners", "watch"],
};

/** Whether a call only reads (or waits): it changes nothing in the game. */
export function isReadingCall(tool: string, args: Record<string, unknown>): boolean {
  if (READS.has(tool)) return true;
  const reading = READING_ACTIONS[tool];
  if (reading !== undefined) return reading.includes(String(args.action));
  return tool === "client_settings" && Object.keys(args).every((k) => k === "instance" || k === "operationId");
}

export function parseToolGroups(text: string): Set<string> {
  const groups = new Set(text.split(",").map((g) => g.trim()).filter(Boolean));
  for (const g of groups) {
    if (!(g in TOOL_GROUPS)) throw new Error(`Unknown tool group ${g} (groups: ${Object.keys(TOOL_GROUPS).join(", ")})`);
  }
  return groups;
}

/** Which tools a hub offers and which calls it refuses: `--read-only` and `--tools` of `craftwire serve`. */
export class ToolPolicy {
  constructor(private readonly o: { readOnly?: boolean; groups?: Set<string> }) {}

  /** Whether the tool is offered at all. */
  lists(tool: string): boolean {
    if (this.o.groups !== undefined) {
      const group = BUILT_IN.has(tool) ? Object.keys(TOOL_GROUPS).find((g) => TOOL_GROUPS[g]!.includes(tool)) : "extensions";
      if (!this.o.groups.has(group!)) return false;
    }
    if (this.o.readOnly) return READS.has(tool) || tool in READING_ACTIONS || tool === "client_settings";
    return true;
  }

  /** Why this call is not allowed, or undefined when it is. */
  refusal(tool: string, args: Record<string, unknown>): string | undefined {
    if (!this.o.readOnly) return undefined;
    const reading = READING_ACTIONS[tool];
    if (reading !== undefined && !reading.includes(String(args.action))) {
      return `This hub is read-only: ${tool} ${String(args.action)} would change something (allowed: ${reading.join(", ")})`;
    }
    if (tool === "client_settings" && Object.keys(args).some((k) => k !== "instance" && k !== "operationId")) {
      return "This hub is read-only: client_settings can only read the settings (call it without values)";
    }
    return undefined;
  }
}
