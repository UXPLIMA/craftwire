/** botrunner_process: start, stop or list the bot runners this hub runs on this machine (java and bedrock bots). */
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { definedOnly } from "../../dev/server-manager.js";
import { CraftwireError } from "../../errors.js";
import { defineTool, ok, type ToolContext } from "../registry.js";

export function registerRunnerTools(server: McpServer, ctx: ToolContext): void {
  defineTool(server, ctx, "botrunner_process",
    "Start, stop or status of a bot runner on this machine: the process that runs java and bedrock bots (bot_spawn kind java|bedrock). "
      + "Runners on other machines connect by themselves with the hub token. start returns the runner's instance.",
    {
      action: z.enum(["start", "stop", "status"]),
      name: z.string().regex(/^[A-Za-z0-9_-]{1,32}$/).optional(),
      kinds: z.array(z.enum(["java", "bedrock"])).min(1).optional(),
      maxBots: z.number().int().min(1).max(10_000).optional(),
      memoryMb: z.number().int().min(256).max(65_536).optional(),
      java: z.string().optional(),
      timeoutMs: z.number().int().min(5000).max(300_000).default(60_000),
      tail: z.number().int().min(0).max(500).default(20),
    },
    async (a, c) => {
      const runners = c.runners;
      if (runners === undefined) {
        throw new CraftwireError("NOT_AVAILABLE", "This hub does not start bot runners", "Start one yourself: java -jar craftwire-botrunner.jar.");
      }
      switch (a.action) {
        case "status":
          return ok({ ...runners.status(a.tail), connected: c.agents.instances().filter((i) => i.kind === "botrunner") });
        case "stop":
          return ok(await runners.stop(a.name));
        case "start":
          return ok(await runners.start(definedOnly({
            name: a.name, kinds: a.kinds, maxBots: a.maxBots, memoryMb: a.memoryMb, java: a.java, timeoutMs: a.timeoutMs,
          })));
      }
    });
}
