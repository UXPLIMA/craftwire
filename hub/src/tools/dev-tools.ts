import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { deploy } from "../dev/deploy.js";
import { definedOnly } from "../dev/server-manager.js";
import { defineTool, ok, type ToolContext } from "./registry.js";

export function registerDevTools(server: McpServer, ctx: ToolContext): void {
  defineTool(server, ctx, "server_process",
    "Start, stop, restart or status of a local Paper server run by the hub. JVM flags and jar come from start.bat/start.sh unless given. Never accept the EULA for the user. A server started elsewhere needs takeOver:true (ask first).",
    {
      action: z.enum(["start", "stop", "restart", "status"]),
      serverDir: z.string().optional().describe("Server folder; optional with one known server."),
      jvmArgs: z.array(z.string()).optional(),
      jar: z.string().optional(),
      java: z.string().optional(),
      takeOver: z.boolean().default(false).describe("Stop a server started outside Craftwire (ask the user first)."),
      timeoutMs: z.number().int().min(1000).max(900_000).default(300_000),
      tail: z.number().int().min(0).max(500).default(20),
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

  defineTool(server, ctx, "plugin_deploy",
    "Build a plugin project (Gradle or Maven) or take a jar, install it, restart the server and report loaded + problems (its WARN/ERROR lines). BUILD_FAILED lists errors as file:line.",
    {
      projectDir: z.string().optional(),
      jar: z.string().optional(),
      buildCommand: z.string().optional().describe("Command run in projectDir instead of the detected build."),
      jarGlob: z.string().optional().describe("Which built jar, e.g. build/libs/*-all.jar."),
      javaHome: z.string().optional(),
      serverDir: z.string().optional().describe("Server folder; optional with one known server."),
      restart: z.boolean().default(true),
      takeOver: z.boolean().default(false).describe("Stop a server started outside Craftwire (ask the user first)."),
      buildTimeoutMs: z.number().int().min(10_000).max(1_800_000).default(600_000),
      timeoutMs: z.number().int().min(1000).max(900_000).default(300_000),
    },
    async (a, c) => ok(await deploy(a, c.servers, c.agents)));
}
