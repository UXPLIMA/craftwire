/**
 * Sign-in for the web panel: a person pastes their token once, the hub answers with a random cookie (HttpOnly,
 * SameSite=Strict, Path=/panel) that stands for them for 12 hours. The token itself never goes into a URL or a page.
 * API callers may send the token as a bearer header instead.
 */
import { randomBytes } from "node:crypto";
import type { IncomingMessage } from "node:http";
import { bearer } from "../http/gate.js";
import type { Person } from "../team/tokens.js";

const COOKIE = "cw_panel";
const TTL_MS = 12 * 60 * 60_000;
const MAX_SIGN_INS = 256;

export class PanelAuth {
  // The token is kept (in memory only) and checked on every request, so revoking it ends the sign-in at once.
  private readonly signIns = new Map<string, { token: string; until: number }>();

  constructor(
    private readonly authenticate: (token: string) => Person | undefined,
    private readonly secure: boolean,
    private readonly now: () => number = Date.now,
  ) {}

  /** The person behind a request: its bearer token, or its panel cookie. */
  person(req: IncomingMessage): Person | undefined {
    const token = bearer(req.headers.authorization);
    if (token !== undefined) return this.authenticate(token);
    const id = cookie(req.headers.cookie, COOKIE);
    if (id === undefined) return undefined;
    const s = this.signIns.get(id);
    if (s === undefined || s.until < this.now()) {
      this.signIns.delete(id);
      return undefined;
    }
    return this.authenticate(s.token);
  }

  /** Signs in with a token; returns the Set-Cookie header, or undefined for a wrong token. */
  signIn(token: string): string | undefined {
    if (this.authenticate(token) === undefined) return undefined;
    this.sweep();
    const id = randomBytes(24).toString("hex");
    this.signIns.set(id, { token, until: this.now() + TTL_MS });
    return `${COOKIE}=${id}; HttpOnly; SameSite=Strict; Path=/panel; Max-Age=${TTL_MS / 1000}${this.secure ? "; Secure" : ""}`;
  }

  signOut(req: IncomingMessage): string {
    const id = cookie(req.headers.cookie, COOKIE);
    if (id !== undefined) this.signIns.delete(id);
    return `${COOKIE}=; HttpOnly; SameSite=Strict; Path=/panel; Max-Age=0`;
  }

  private sweep(): void {
    const t = this.now();
    for (const [id, s] of this.signIns) if (s.until < t) this.signIns.delete(id);
    while (this.signIns.size >= MAX_SIGN_INS) this.signIns.delete(this.signIns.keys().next().value as string);
  }
}

function cookie(header: string | undefined, name: string): string | undefined {
  for (const part of (header ?? "").split(";")) {
    const [k, ...v] = part.trim().split("=");
    if (k === name) return v.join("=");
  }
  return undefined;
}
