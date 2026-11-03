/** client_process: hidden clients the hub runs (start, stop, status), Microsoft sign-in and mod profiles for them. */
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { MAX_CLIENTS } from "../client/client-manager.js";
import { type McVersion, SUPPORTED_VERSIONS } from "../client/pins.js";
import { definedOnly } from "../dev/server-manager.js";
import { CraftwireError } from "../errors.js";
import { defineTool, defineToolWithUnlisted, ok, type ToolContext } from "./registry.js";

export function registerClientProcessTools(server: McpServer, ctx: ToolContext): void {
  defineToolWithUnlisted(server, ctx, "client_process",
    `Hidden clients run by the hub (up to ${MAX_CLIENTS}): start joins server (host:port; default: the server_process one) or opens `
      + "world {name, create?} and returns its instance; stop (username, or all); status. lite: small and frameless, for several "
      + "clients. Online servers: sign_in (Microsoft device code), then start {account}. mod_profile {name, mods} for start "
      + "{modProfile}. More (mods, windowSize, java, …): craftwire://docs/tools. First start downloads ~150 MB.",
    {
      action: z.enum(["start", "stop", "status", "sign_in", "sign_out", "mod_profile"]),
      server: z.string().regex(/^[^\s:]+(:\d{1,5})?$/).optional(),
      version: z.enum(SUPPORTED_VERSIONS as [string, ...string[]]).optional(),
      username: z.string().regex(/^[A-Za-z0-9_]{3,16}$/).optional(),
      account: z.string().regex(/^[A-Za-z0-9_]{3,16}$/).optional(),
      lite: z.boolean().optional(),
      all: z.boolean().optional(),
    },
    {
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
      mods: z.array(z.string()).max(50).optional().describe("start: absolute mod jar paths; mod_profile: jar paths or modrinth:<slug>."),
      modProfile: z.string().optional(),
      name: z.string().optional().describe("mod_profile name; sign_out account."),
      resourcePacks: z.enum(["accept", "decline", "prompt"]).optional().describe("Server resource packs (default prompt: vanilla's screen)."),
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
          return ok({ ...c.clients.status(a.tail), accounts: c.clients.accounts.list(), signIn: c.clients.accounts.signInState() ?? null,
            modProfiles: c.clients.modProfiles.list() });
        case "stop":
          return ok(a.all ? await c.clients.stopAll() : await c.clients.stop(a.username));
        case "sign_in":
          return ok({ ...(await c.clients.accounts.startSignIn()),
            next: "Open verificationUri, enter userCode and sign in; status shows signIn.state, then start {account}." });
        case "sign_out": {
          if (a.name === undefined) throw new CraftwireError("INVALID_PARAMS", "sign_out needs name (the account)", "status lists accounts.");
          return ok({ removed: c.clients.accounts.remove(a.name) });
        }
        case "mod_profile": {
          if (a.name === undefined) throw new CraftwireError("INVALID_PARAMS", "mod_profile needs name", "e.g. name:'shaders'.");
          return ok({ modProfiles: c.clients.modProfiles.save(a.name, a.mods ?? []) });
        }
        case "start":
          return ok(await c.clients.start(definedOnly({
            server: a.server, world: a.world, version: a.version as McVersion | undefined, username: a.username, mods: a.mods,
            visible: a.visible, windowSize: a.windowSize, java: a.java, sounds: a.sounds, timeoutMs: a.timeoutMs, lite: a.lite,
            resourcePacks: a.resourcePacks, account: a.account, modProfile: a.modProfile,
          })));
      }
    });
}
