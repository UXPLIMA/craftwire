import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { deploy } from "../dev/deploy.js";
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

  defineTool(server, ctx, "plugin_deploy",
    "Build a Paper plugin project (Gradle wrapper `build -x test` or Maven `package -DskipTests`, auto-detected) or take a ready jar, install it into the server's plugins/ (older jars of the same plugin move to plugins/.craftwire-backup/), restart the server (starting it if it is down) and report `loaded` (enabled, version) plus `problems` (its WARN/ERROR log lines). A failed build returns BUILD_FAILED with details.errors as file:line. restart:false on a running server stages the jar in plugins/update/ for the next start.",
    {
      projectDir: z.string().optional().describe("Plugin project root to build."),
      jar: z.string().optional().describe("A ready plugin jar to install instead of building."),
      buildCommand: z.string().optional().describe("Shell command run in projectDir instead of the detected build, e.g. '.\\gradlew.bat shadowJar' on Windows (a bare gradlew.bat is not found from Claude Code's shell)."),
      jarGlob: z.string().optional().describe("Which built jar, relative to projectDir, e.g. 'build/libs/*-all.jar'. Needed when several plugins are built."),
      javaHome: z.string().optional().describe("JAVA_HOME for the build."),
      serverDir: z.string().optional().describe("Server folder. Optional when only one server is known."),
      restart: z.boolean().default(true),
      takeOver: z.boolean().default(false).describe("Allow stopping a server that was started outside Craftwire (ask the user first)."),
      buildTimeoutMs: z.number().int().min(10_000).max(1_800_000).default(600_000),
      timeoutMs: z.number().int().min(1000).max(900_000).default(300_000).describe("How long the restart waits for the server."),
    },
    async (a, c) => ok(await deploy(a, c.servers, c.agents)));
}
