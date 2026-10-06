import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { bindExtensionTools } from "./extensions.js";
import { registerDebugTools } from "./tools/debug-tools.js";
import { registerBotTools } from "./tools/bot-tools.js";
import { registerClientProcessTools } from "./tools/client-process-tools.js";
import { registerClientTools } from "./tools/client-tools.js";
import { registerDevTools } from "./tools/dev-tools.js";
import { registerHubTools } from "./tools/hub-tools.js";
import { registerLogTools } from "./tools/log-tools.js";
import type { ToolContext } from "./tools/registry.js";
import { registerServerTools } from "./tools/server-tools.js";
import { registerScenarioTools } from "./tools/scenario-tools.js";
import { HUB_VERSION } from "./version.js";

const INSTRUCTIONS = [
  "Craftwire lets you see and drive Minecraft.",
  "Start with list_instances. Client tools (screenshot, camera, gui_*, input, chat, hud_read, player_state, client_settings) act on a game client.",
  "Server tools (server_command, server_eval, world_query, world_edit, world_render, server_info, plugin_manage, events) act on a Paper server running the Craftwire plugin; logs reads either, exceptions groups the stack traces of both into bugs.",
  "Dev loop: server_process starts/stops a local Paper server; plugin_deploy builds a plugin project (or takes a jar), installs it, restarts the server and reports whether it enabled.",
  "scenario_run runs plugin tests written as JSON steps (bots, commands, checks) and reports the failing step with context.",
  "No game open? client_process starts a hidden client the hub runs itself (offline mode, for dev servers); then use the client tools on it.",
  "Bots: bot_spawn puts fake players on a Paper server; bot_action drives them (chat, command, move_to, gui_read/gui_click, use, attack, …) so you can test plugins without a real client; bot_remove when done.",
  "After an action that opens a menu (e.g. chat {action:'command'}), call wait_for {condition:'screen_open'} before gui_read. wait_for also waits on the server (a block, a player arriving, items, an event, a bot's message) instead of sleeping and polling.",
  "Errors carry a `hint` with the next step. Pass operationId on actions you might retry.",
].join(" ");

export function createCraftwireServer(ctx: ToolContext): McpServer {
  const server = new McpServer({ name: "craftwire", version: HUB_VERSION }, { instructions: INSTRUCTIONS });
  registerHubTools(server, ctx);
  registerClientTools(server, ctx);
  registerServerTools(server, ctx);
  registerLogTools(server, ctx);
  registerDevTools(server, ctx);
  registerBotTools(server, ctx);
  registerDebugTools(server, ctx);
  registerScenarioTools(server, ctx, () => createCraftwireServer(ctx));
  // Last, so a plugin's tool can never take a built-in name.
  bindExtensionTools(server, ctx);
  registerClientProcessTools(server, ctx);
  return server;
}
