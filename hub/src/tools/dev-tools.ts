import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { definedOnly } from "../dev/server-manager.js";
import { defineTool, ok, type ToolContext } from "./registry.js";

export function registerDevTools(server: McpServer, ctx: ToolContext): void {
  defineTool(server, ctx, "server_process",
    "Start, stop, restart or inspect a local Paper server run by the hub. start waits for the console's `Done (` line and, when the Craftwire plugin is in plugins/, for the plugin to connect (result `agent`). JVM flags and the jar come from the folder's start.bat/start.sh unless jvmArgs/jar are given. The EULA is never accepted for you: on EULA_NOT_ACCEPTED ask the user. A server started outside Craftwire (status → external) is stopped or restarted only with takeOver:true — ask the user first. Servers started here stop when the hub exits.",
    {
      action: z.enum(["start", "stop", "restart", "status"]),
      serverDir: z.string().optional().describe("Server folder (contains the Paper jar). Optional when only one server is known."),
      jvmArgs: z.array(z.string()).optional().describe("JVM flags, replacing the start script's."),
      jar: z.string().optional().describe("Server jar, relative to serverDir."),
      java: z.string().optional().describe("Java executable. Paper 26.x needs Java 25+."),
      takeOver: z.boolean().default(false).describe("Allow stopping a server that was started outside Craftwire."),
      timeoutMs: z.number().int().min(1000).max(900_000).default(300_000).describe("How long start/restart waits for the server to be ready."),
      tail: z.number().int().min(0).max(500).default(20).describe("status: console lines to include per server."),
    },
    async (a, c) => {
      const launch = definedOnly({ java: a.java, jvmArgs: a.jvmArgs, jar: a.jar });
      switch (a.action) {
        case "status":
          return ok(c.servers.status(a.serverDir, a.tail));
        case "start":
          return ok(await c.servers.start({ ...launch, serverDir: c.servers.resolveDir(a.serverDir), timeoutMs: a.timeoutMs }));
        case "stop":
          return ok(await c.servers.stop(a.serverDir, { takeOver: a.takeOver }));
        case "restart":
          return ok(await c.servers.restart({ ...launch, serverDir: a.serverDir, timeoutMs: a.timeoutMs, takeOver: a.takeOver }));
      }
    });
}
