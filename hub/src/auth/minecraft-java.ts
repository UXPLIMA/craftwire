/** The Minecraft Java session from an XSTS token: login_with_xbox, then the profile (name and UUID) the game plays as. */
import { CraftwireError } from "../errors.js";
import type { Xsts } from "./xbox.js";

const LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
const PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";

export interface JavaSession {
  name: string;
  /** Undashed, as launchers pass ${auth_uuid}. */
  uuid: string;
  accessToken: string;
  xuid?: string;
  expiresAt: number;
}

type Fetch = typeof fetch;

export async function javaSession(xsts: Xsts, f: Fetch = fetch, now = Date.now): Promise<JavaSession> {
  const login = await f(LOGIN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify({ identityToken: `XBL3.0 x=${xsts.userHash};${xsts.token}` }),
  });
  const l = (await login.json().catch(() => ({}))) as { access_token?: string; expires_in?: number };
  if (typeof l.access_token !== "string") {
    throw new CraftwireError("SIGN_IN_FAILED", `Minecraft services refused the Xbox token (HTTP ${login.status})`, "Try again in a minute.");
  }
  const res = await f(PROFILE_URL, { headers: { Authorization: `Bearer ${l.access_token}`, Accept: "application/json" } });
  const p = (await res.json().catch(() => ({}))) as { id?: string; name?: string };
  if (res.status === 404 || typeof p.id !== "string" || typeof p.name !== "string") {
    throw new CraftwireError("NO_MINECRAFT_PROFILE", "This Microsoft account does not own Minecraft Java (or has no profile name yet)",
      "Buy Minecraft Java for the account, or set a profile name at minecraft.net, then sign in again.");
  }
  return {
    name: p.name, uuid: p.id.replace(/-/g, ""), accessToken: l.access_token,
    ...(xsts.xuid !== undefined ? { xuid: xsts.xuid } : {}),
    expiresAt: now() + (l.expires_in ?? 86_400) * 1000,
  };
}
