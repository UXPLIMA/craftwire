import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { GetPromptResult } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";
import type { ToolContext } from "./tools/registry.js";

const user = (text: string): GetPromptResult => ({ messages: [{ role: "user", content: { type: "text", text } }] });

/** One line per connected game or server. */
function connected(ctx: ToolContext): string {
  const all = ctx.agents.instances();
  if (all.length === 0) return "Nothing is connected to the Craftwire hub right now.";
  return "Connected now:\n" + all.map((i) => `- ${i.name} (${i.kind}, ${i.id}, Minecraft ${i.mcVersion})`).join("\n");
}

/** The plugins of the connected server, when exactly one is connected (best effort, short timeout). */
async function plugins(ctx: ToolContext): Promise<string> {
  const servers = ctx.agents.instances().filter((i) => i.kind === "server");
  if (servers.length !== 1) return "";
  try {
    const r = await ctx.agents.request(servers[0]!.id, "plugin.manage", { action: "list" }, 3000) as { plugins?: Array<{ name: string; enabled?: boolean }> };
    const names = (r.plugins ?? []).filter((p) => p.name !== "Craftwire").map((p) => p.name + (p.enabled === false ? " (disabled)" : ""));
    return names.length ? `\nPlugins on ${servers[0]!.name}: ${names.join(", ")}.` : "";
  } catch {
    return "";
  }
}

export function registerPrompts(server: McpServer, ctx: ToolContext): void {
  server.registerPrompt("test_plugin", {
    title: "Test a plugin",
    description: "Build and deploy a Paper plugin, write scenario tests for its commands and menus, run them, and check for exceptions.",
    argsSchema: {
      plugin: z.string().optional().describe("The plugin's name (default: the one being developed in this project)."),
      serverDir: z.string().optional().describe("The Paper server folder to use (default: the one server_process runs)."),
    },
  }, async ({ plugin, serverDir }) => user(`Test the Paper plugin ${plugin ?? "in this project"} with Craftwire, end to end.

${connected(ctx)}${await plugins(ctx)}

1. Make sure a dev server runs: server_process status${serverDir ? ` (server folder: ${serverDir})` : ""}; start it if needed (it never accepts the EULA for the user).
2. Build and deploy the plugin with plugin_deploy, and check it reports the plugin enabled with no errors. Fix compile errors (file:line) before going on.
3. Read the plugin's commands, permissions and menus from its plugin.yml and source. Read craftwire://docs/scenarios for the scenario format.
4. Write scenarios under tests/ (*.cwtest.json): spawn bots, run each command as a player, click through each menu (gui_read, gui_click), and check what should follow (expect_message, expect_block, expect_event, inventory waits). Cover the refusals too: no permission, not enough money, wrong arguments.
5. Run them with scenario_run {files: ["tests"]}. For each failure, decide whether the plugin or the scenario is wrong, fix it, and run again.
6. Finish with exceptions (anything new since you started) and logs at WARN, and summarise what passes, what you fixed, and what is left.`));

  server.registerPrompt("debug_lag", {
    title: "Find what makes the game lag",
    description: "Profile a server's ticks or a client's frames, find the plugin or mod and the code responsible, and propose a fix.",
    argsSchema: { instance: z.string().optional().describe("Which server or client (default: the only server).") },
  }, async ({ instance }) => user(`Find out what makes ${instance ?? "the server"} lag, and why.

${connected(ctx)}

1. If the lag needs something to happen (players, a minigame, a farm), set it up first: bots (bot_spawn, bot_action), commands, or ask the user to reproduce it while you record.
2. profile${instance ? ` {instance: "${instance}"}` : ""} for 10-20 s. Read busyPercent, ticks (msptAvg, p95, the slowest ticks) or fps, then owners: which plugin or mod uses the time.
3. Look at that owner's entryPoints (which listener, for which event, or which scheduler task) and the hotMethods.
4. trace the suspicious method (or the listener) for exact call counts, average and maximum time, and its callers.
5. Read that code. Explain the cause in plain words (e.g. a world lookup per PlayerMoveEvent, a database call on the main thread, a task scheduled every tick), and propose a concrete fix. If you can change the code, do it, redeploy, and profile again to show the difference.`));

  server.registerPrompt("write_scenario", {
    title: "Write a scenario test",
    description: "Write and run a scenario (a JSON plugin test) for one feature.",
    argsSchema: { feature: z.string().describe("What to test, e.g. 'buying a diamond in /shop costs 30 emeralds'.") },
  }, async ({ feature }) => user(`Write a Craftwire scenario that tests: ${feature}

${connected(ctx)}${await plugins(ctx)}

Read craftwire://docs/scenarios for the format. Keep it small: bots for the players involved, setup for what must exist first (items, blocks, permissions), the steps a player takes, and a check after each step that matters (expect_message, expect_block, expect_event, expect_hud, waits on inventory). Clean up what the scenario changed. Save it under tests/ as a .cwtest.json file, run it with scenario_run, and fix it until it passes or clearly shows a bug in the plugin — then say which.`));

  server.registerPrompt("setup", {
    title: "Check the Craftwire setup",
    description: "What is connected, what is missing, and the next step.",
  }, async () => {
    const all = ctx.agents.instances();
    const clients = all.filter((i) => i.kind === "client");
    const servers = all.filter((i) => i.kind === "server");
    const lines = [connected(ctx), ""];
    lines.push(clients.length
      ? `Clients: ${clients.map((c) => c.name).join(", ")}.`
      : "No Minecraft client is connected. Either the user starts Minecraft with Fabric and the Craftwire Agent mod (it connects by itself), or use client_process to start a hidden client the hub runs.");
    lines.push(servers.length
      ? `Servers: ${servers.map((s) => s.name).join(", ")}.`
      : "No Paper server is connected. Either the user puts the Craftwire plugin in a dev server's plugins folder, or use server_process to start one (the user accepts the EULA themselves).");
    lines.push("", "Tell the user in plain words what works and what the next step is. If something should be connected but is not, suggest `npx craftwire doctor` (it checks Java, the hub, and the games).");
    return user(lines.join("\n"));
  });
}
