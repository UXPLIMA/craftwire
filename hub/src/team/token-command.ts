/** `craftwire token add|list|remove`: the per-person tokens `craftwire serve` accepts besides its own. */
import { parseToolGroups } from "../tool-policy.js";
import { ROLES, TeamTokens } from "./tokens.js";

export const TOKEN_USAGE = `Usage: craftwire token <add|list|remove> ...
  add <name> [--role read-only|bots|dev] [--tools <groups>]
                 make a token for a teammate; it is printed once (default role: read-only)
  list           the teammates with a token, their role and tool groups (never the tokens)
  remove <name>  revoke a teammate's token at once
  Roles: read-only (every tool that changes nothing), bots (hub and bot tools), dev (everything).
  --tools narrows a role to some groups: hub, client, server, build, dev, bots, debug, proxy, extras, extensions (all: every group).`;

export function tokenCommand(args: string[], home: string, out: (s: string) => void): number {
  const tokens = new TeamTokens(home);
  const [sub, name, ...rest] = args;
  switch (sub) {
    case "add": {
      if (name === undefined) throw new Error("token add needs a name");
      let role: (typeof ROLES)[number] = "read-only";
      let groups: string[] | undefined;
      for (let i = 0; i < rest.length; i++) {
        const v = rest[i + 1];
        if (rest[i] === "--role" && v !== undefined) {
          if (!(ROLES as readonly string[]).includes(v)) throw new Error(`--role is one of ${ROLES.join(", ")}`);
          role = v as (typeof ROLES)[number];
          i++;
        } else if (rest[i] === "--tools" && v !== undefined) {
          groups = [...parseToolGroups(v)];
          i++;
        } else {
          throw new Error(`Unknown option ${rest[i]}`);
        }
      }
      const token = tokens.add(name, role, groups);
      out(`token for ${name} (${role}${groups ? `, tools ${groups.join(",")}` : ""}):\n${token}\n`);
      out("It is shown only now. They connect with: --header \"Authorization: Bearer <token>\"; the panel takes it at /panel.\n");
      return 0;
    }
    case "list": {
      const all = tokens.list();
      if (all.length === 0) out("No teammate tokens. craftwire token add <name> makes one.\n");
      for (const t of all) {
        out(`${t.name}\t${t.role}${t.groups ? `\t${t.groups.join(",")}` : ""}\t${new Date(t.createdAt).toISOString()}\n`);
      }
      return 0;
    }
    case "remove": {
      if (name === undefined) throw new Error("token remove needs a name");
      out(tokens.remove(name) ? `removed ${name}; their sessions end at their next request\n` : `no token named ${name}\n`);
      return 0;
    }
    default:
      out(`${TOKEN_USAGE}\n`);
      return sub === undefined || sub === "--help" ? 0 : 1;
  }
}
