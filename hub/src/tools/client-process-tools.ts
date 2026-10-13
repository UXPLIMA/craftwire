import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { type McVersion, SUPPORTED_VERSIONS } from "../client/pins.js";
import { definedOnly } from "../dev/server-manager.js";
import { defineTool, ok, type ToolContext } from "./registry.js";

export function registerClientProcessTools(server: McpServer, ctx: ToolContext): void {
  defineTool(server, ctx, "client_process",
    "Start, stop or status of a hidden client run by the hub (offline mode): joins server (default: the server_process one) or opens a singleplayer world; returns its instance. First start downloads ~150 MB.",
    {
      action: z.enum(["start", "stop", "status"]),
      server: z.string().regex(/^[^\s:]+(:\d{1,5})?$/).optional().describe("host:port. Default: the server started by server_process."),
      world: z.object({
        name: z.string().describe("Save folder name."),
        create: z.object({
          type: z.enum(["normal", "flat", "void"]).optional(),
          seed: z.string().optional(),
          gameMode: z.enum(["survival", "creative", "adventure", "spectator"]).optional(),
          difficulty: z.enum(["peaceful", "easy", "normal", "hard"]).optional(),
          cheats: z.boolean().optional(),
          replace: z.boolean().optional().describe("Delete an existing save of this name first."),
        }).optional().describe("Create the world (else an existing save opens)."),
      }).optional().describe("Open a singleplayer world instead of joining a server."),
      version: z.enum(SUPPORTED_VERSIONS as [string, ...string[]]).optional(),
      username: z.string().regex(/^[A-Za-z0-9_]{3,16}$/).optional(),
      mods: z.array(z.string()).max(50).optional().describe("Absolute paths of extra mod jars."),
      visible: z.boolean().optional(),
      windowSize: z.object({ width: z.number().int().min(320).max(7680), height: z.number().int().min(240).max(4320) }).optional(),
      java: z.string().optional(),
      sounds: z.boolean().optional(),
      timeoutMs: z.number().int().min(1000).max(900_000).default(300_000),
      tail: z.number().int().min(0).max(500).default(20),
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
