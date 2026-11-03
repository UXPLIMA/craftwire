/**
 * Signed-in Microsoft accounts for client_process: the device-code sign-in running in the background, and the saved
 * refresh tokens (in <home>/auth/accounts.json, readable by the user only) turned into a fresh Minecraft session on
 * every start. Tokens never go into tool results or logs.
 */
import { chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { CraftwireError } from "../errors.js";
import { type DeviceCode, type LiveToken, pollDeviceCode, refreshLive, requestDeviceCode } from "./live.js";
import { javaSession, type JavaSession } from "./minecraft-java.js";
import { BEDROCK_RELYING_PARTY, JAVA_RELYING_PARTY, xboxUserToken, xstsToken } from "./xbox.js";

interface Stored {
  uuid: string;
  refreshToken: string;
  signedInAt: number;
}

export interface SignInState {
  state: "pending" | "done" | "failed";
  userCode?: string;
  verificationUri?: string;
  expiresAt?: number;
  account?: string;
  error?: { code: string; message: string; hint?: string };
}

/** Refresh this long before a session expires, so a client never starts with a token about to lapse. */
const EARLY_MS = 5 * 60_000;

export class Accounts {
  private readonly file: string;
  private signIn: SignInState | undefined;
  private readonly sessions = new Map<string, JavaSession>();

  constructor(home: string, private readonly f: typeof fetch = fetch, private readonly now: () => number = Date.now) {
    this.file = join(home, "auth", "accounts.json");
  }

  /** Starts a device-code sign-in (or returns the one waiting for its code); finishing it saves the account. */
  async startSignIn(): Promise<SignInState> {
    if (this.signIn?.state === "pending" && (this.signIn.expiresAt ?? 0) > this.now()) return this.signIn;
    const code = await requestDeviceCode(this.f, this.now);
    this.signIn = { state: "pending", userCode: code.userCode, verificationUri: code.verificationUri, expiresAt: code.expiresAt };
    void this.poll(code, this.signIn);
    return this.signIn;
  }

  signInState(): SignInState | undefined {
    return this.signIn;
  }

  list(): { name: string; uuid: string; signedInAt: number }[] {
    return Object.entries(this.read()).map(([name, s]) => ({ name, uuid: s.uuid, signedInAt: s.signedInAt }));
  }

  remove(name: string): boolean {
    const all = this.read();
    if (!(name in all)) return false;
    delete all[name];
    this.sessions.delete(name);
    this.write(all);
    return true;
  }

  /** A Minecraft session for a saved account, refreshed when it is near its end. */
  async session(name: string): Promise<JavaSession> {
    const cached = this.sessions.get(name);
    if (cached && cached.expiresAt - EARLY_MS > this.now()) return cached;
    const s = await this.javaFrom(await this.refresh(name));
    this.sessions.set(name, s);
    return s;
  }

  /** An XSTS token for Bedrock's multiplayer service; the Bedrock bot requests its login chain with it. */
  async bedrock(name: string): Promise<{ name: string; userHash: string; xsts: string }> {
    const user = await xboxUserToken((await this.refresh(name)).accessToken, this.f);
    const x = await xstsToken(user, BEDROCK_RELYING_PARTY, this.f);
    return { name, userHash: x.userHash, xsts: x.token };
  }

  /** A fresh Microsoft token for a saved account; the rotated refresh token is saved. */
  private async refresh(name: string): Promise<LiveToken> {
    const all = this.read();
    const stored = all[name];
    if (stored === undefined) {
      const known = Object.keys(all);
      throw new CraftwireError("ACCOUNT_NOT_FOUND", `No signed-in account named ${name}`,
        known.length > 0 ? `Signed in: ${known.join(", ")}.` : "Sign in first: client_process {action:'sign_in'}.");
    }
    const live = await refreshLive(stored.refreshToken, this.f, this.now);
    all[name] = { ...stored, refreshToken: live.refreshToken };
    this.write(all);
    return live;
  }

  private async javaFrom(live: LiveToken): Promise<JavaSession> {
    const user = await xboxUserToken(live.accessToken, this.f);
    return javaSession(await xstsToken(user, JAVA_RELYING_PARTY, this.f), this.f, this.now);
  }

  private async poll(code: DeviceCode, state: SignInState): Promise<void> {
    try {
      for (;;) {
        await new Promise((r) => setTimeout(r, code.intervalMs));
        if (this.now() > code.expiresAt) throw new CraftwireError("SIGN_IN_EXPIRED", "The sign-in code expired", "Start again.");
        const live = await pollDeviceCode(code, this.f, this.now);
        if (live === undefined) continue;
        const s = await this.javaFrom(live);
        const all = this.read();
        all[s.name] = { uuid: s.uuid, refreshToken: live.refreshToken, signedInAt: this.now() };
        this.write(all);
        this.sessions.set(s.name, s);
        state.state = "done";
        state.account = s.name;
        return;
      }
    } catch (e) {
      state.state = "failed";
      state.error = e instanceof CraftwireError
        ? { code: e.code, message: e.message, ...(e.hint !== undefined ? { hint: e.hint } : {}) }
        : { code: "SIGN_IN_FAILED", message: (e as Error).message };
    }
  }

  private read(): Record<string, Stored> {
    if (!existsSync(this.file)) return {};
    try {
      return JSON.parse(readFileSync(this.file, "utf8")) as Record<string, Stored>;
    } catch {
      return {};
    }
  }

  private write(all: Record<string, Stored>): void {
    mkdirSync(join(this.file, ".."), { recursive: true });
    writeFileSync(this.file, JSON.stringify(all, null, 2), { mode: 0o600 });
    // writeFileSync keeps the mode of an existing file; the refresh tokens must stay private either way.
    if (process.platform !== "win32") chmodSync(this.file, 0o600);
  }
}
