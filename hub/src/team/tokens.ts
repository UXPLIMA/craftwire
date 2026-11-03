/**
 * Per-person tokens for `craftwire serve`: each teammate gets their own token with a role, so the hub knows who made
 * each call (the audit log and session replay show it) and what they may do. Only SHA-256 hashes are stored, in
 * <home>/auth/tokens.json (readable by the user only); a token is shown once, when it is made.
 */
import { createHash, randomBytes, timingSafeEqual } from "node:crypto";
import { chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { ToolPolicy, TOOL_GROUPS } from "../tool-policy.js";

/** read-only: every group, no changes; bots: hub and bot tools; dev: everything (as the owner's token). */
export const ROLES = ["read-only", "bots", "dev"] as const;
export type Role = (typeof ROLES)[number] | "owner";

export interface Person {
  name: string;
  role: Role;
  /** Undefined: every tool, every call. */
  policy?: ToolPolicy;
}

interface Stored {
  name: string;
  role: (typeof ROLES)[number];
  hash: string;
  /** Extra limit to these tool groups, on top of the role. */
  groups?: string[];
  createdAt: number;
}

const OWNER: Person = { name: "owner", role: "owner" };

export function policyFor(role: Role, groups?: string[]): ToolPolicy | undefined {
  const limit = groups !== undefined && groups.length > 0 ? new Set(groups) : undefined;
  switch (role) {
    case "owner":
    case "dev":
      return limit ? new ToolPolicy({ groups: limit }) : undefined;
    case "read-only":
      return new ToolPolicy({ readOnly: true, ...(limit ? { groups: limit } : {}) });
    case "bots":
      return new ToolPolicy({ groups: limit ?? new Set(["hub", "bots"]) });
  }
}

const hash = (token: string) => createHash("sha256").update(token).digest("hex");

export class TeamTokens {
  private readonly file: string;

  constructor(home: string, private readonly now: () => number = Date.now) {
    this.file = join(home, "auth", "tokens.json");
  }

  /** Makes a token for `name`; the plain token is returned only here. */
  add(name: string, role: (typeof ROLES)[number], groups?: string[]): string {
    if (!/^[A-Za-z0-9_.-]{1,40}$/.test(name)) throw new Error("A name is 1-40 letters, digits, '.', '_' or '-'");
    if (name === OWNER.name) throw new Error("owner is the serve token's name; pick another");
    for (const g of groups ?? []) if (!(g in TOOL_GROUPS)) throw new Error(`Unknown tool group ${g}`);
    const all = this.read().filter((t) => t.name !== name);
    const token = `cw_${randomBytes(24).toString("hex")}`;
    all.push({ name, role, hash: hash(token), ...(groups && groups.length > 0 ? { groups } : {}), createdAt: this.now() });
    this.write(all);
    return token;
  }

  remove(name: string): boolean {
    const all = this.read();
    const left = all.filter((t) => t.name !== name);
    if (left.length === all.length) return false;
    this.write(left);
    return true;
  }

  list(): { name: string; role: string; groups?: string[]; createdAt: number }[] {
    return this.read().map(({ name, role, groups, createdAt }) => ({ name, role, ...(groups ? { groups } : {}), createdAt }));
  }

  /** The person a bearer token belongs to: the serve token is the owner, else a teammate's token. */
  identify(token: string, ownerToken: string): Person | undefined {
    if (equal(token, ownerToken)) return OWNER;
    const h = hash(token);
    const t = this.read().find((s) => equal(s.hash, h));
    if (t === undefined) return undefined;
    const policy = policyFor(t.role, t.groups);
    return { name: t.name, role: t.role, ...(policy ? { policy } : {}) };
  }

  private read(): Stored[] {
    if (!existsSync(this.file)) return [];
    try {
      const v = JSON.parse(readFileSync(this.file, "utf8")) as unknown;
      return Array.isArray(v) ? (v as Stored[]).filter((s) => typeof s?.hash === "string" && ROLES.includes(s.role)) : [];
    } catch {
      return [];
    }
  }

  private write(all: Stored[]): void {
    mkdirSync(join(this.file, ".."), { recursive: true });
    writeFileSync(this.file, JSON.stringify(all, null, 2), { mode: 0o600 });
    if (process.platform !== "win32") chmodSync(this.file, 0o600);
  }
}

function equal(a: string, b: string): boolean {
  const x = Buffer.from(a);
  const y = Buffer.from(b);
  return x.length === y.length && timingSafeEqual(x, y);
}
