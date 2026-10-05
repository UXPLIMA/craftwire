import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { registerClientTools } from "./tools/client-tools.js";
import { registerHubTools } from "./tools/hub-tools.js";
import { registerLogTools } from "./tools/log-tools.js";
import type { ToolContext } from "./tools/registry.js";
import { registerServerTools } from "./tools/server-tools.js";
import { HUB_VERSION } from "./version.js";

const INSTRUCTIONS = [
  "Craftwire lets you see and drive Minecraft.",
  "Start with list_instances. Client tools (screenshot, camera, gui_*, input, chat, hud_read, player_state, client_settings) act on a game client.",
  "Server tools (server_command, server_eval, world_query, world_edit, server_info, plugin_manage) act on a Paper server running the Craftwire plugin; logs reads either.",
  "After an action that opens a menu (e.g. chat {action:'command'}), call wait_for {condition:'screen_open'} before gui_read.",
  "Errors carry a `hint` with the next step. Pass operationId on actions you might retry.",
].join(" ");

export function createCraftwireServer(ctx: ToolContext): McpServer {
  const server = new McpServer({ name: "craftwire", version: HUB_VERSION }, { instructions: INSTRUCTIONS });
  registerHubTools(server, ctx);
  registerClientTools(server, ctx);
  registerServerTools(server, ctx);
  registerLogTools(server, ctx);
  return server;
}
