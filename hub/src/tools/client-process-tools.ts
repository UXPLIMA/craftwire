import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { type McVersion, SUPPORTED_VERSIONS } from "../client/pins.js";
import { definedOnly } from "../dev/server-manager.js";
import { defineTool, ok, type ToolContext } from "./registry.js";

export function registerClientProcessTools(server: McpServer, ctx: ToolContext): void {
  defineTool(server, ctx, "client_process",
    "Start, stop or inspect a headless Minecraft client run by the hub (offline mode, hidden window), so client tools work without the user opening the game. start downloads Minecraft + Fabric on first use (about 150 MB per version, cached in ~/.craftwire/client; status shows progress). The version is the one `server` reports (asked with the server-list ping), else the newest supported; `version` overrides, starts the client with the Craftwire agent, joins `server` (default: the server started by server_process, which must run online-mode=false) or opens a singleplayer `world` (creating it with `create`), and returns once the player is in the world; `instance` in the result is the client for screenshot, gui_read, input and the rest. mods adds extra mod jars (the mod you are developing, Sodium, …). Several clients can run with different usernames. Clients stop when the hub exits.",
    {
      action: z.enum(["start", "stop", "status"]),
      server: z.string().regex(/^[^\s:]+(:\d{1,5})?$/).optional().describe("host:port to join. Default: the single server started by server_process; none → title screen."),
      world: z.object({
        name: z.string().describe("Save folder name in the client's saves/ (letters, digits, spaces, . - _)."),
        create: z.object({
          type: z.enum(["normal", "flat", "void"]).optional().describe("Default flat."),
          seed: z.string().optional().describe("A number, or text that is hashed like the Create World screen does; default random."),
          gameMode: z.enum(["survival", "creative", "adventure", "spectator"]).optional().describe("Default creative."),
          difficulty: z.enum(["peaceful", "easy", "normal", "hard"]).optional().describe("Default peaceful (normal for type normal)."),
          cheats: z.boolean().optional().describe("Allow commands. Default true."),
          replace: z.boolean().optional().describe("Delete an existing save with this name first (a fresh world every run)."),
        }).optional().describe("Create the world. Without it, an existing save is opened."),
      }).optional().describe("Open a singleplayer world instead of joining a server: the client tools then work in it, with no server needed."),
      version: z.enum(SUPPORTED_VERSIONS as [string, ...string[]]).optional().describe(`Minecraft version (${SUPPORTED_VERSIONS.join(", ")}). Default: the server's, else the newest.`),
      username: z.string().regex(/^[A-Za-z0-9_]{3,16}$/).optional().describe("Offline player name (default Craftwire). stop: which client, when several run."),
      mods: z.array(z.string()).max(50).optional().describe("Absolute paths of extra mod jars, loaded next to Fabric API and the agent."),
      visible: z.boolean().optional().describe("Show the game window (default hidden)."),
      windowSize: z.object({ width: z.number().int().min(320).max(7680), height: z.number().int().min(240).max(4320) }).optional()
        .describe("Window and screenshot size (default 1280x720)."),
      java: z.string().optional().describe("Java executable; Minecraft 26.x needs Java 25+. Default: JAVA_HOME, then java on PATH."),
      sounds: z.boolean().optional().describe("Also download the sound assets (~360 MB). Off by default; the game runs fine without them."),
      timeoutMs: z.number().int().min(1000).max(900_000).default(300_000).describe("How long start waits for the player to be in the world (downloads not counted)."),
      tail: z.number().int().min(0).max(500).default(20).describe("status: log lines per client."),
    },
    async (a, c) => {
      switch (a.action) {
        case "status":
          return ok(c.clients.status(a.tail));
        case "stop":
          return ok(await c.clients.stop(a.username));
        case "start":
          return ok(await c.clients.start(definedOnly({
            server: a.server, world: a.world, version: a.version as McVersion | undefined, username: a.username, mods: a.mods, visible: a.visible, windowSize: a.windowSize,
            java: a.java, sounds: a.sounds, timeoutMs: a.timeoutMs,
          })));
      }
    });
}
