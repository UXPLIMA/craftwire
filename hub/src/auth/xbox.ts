/** Xbox Live tokens from a Microsoft token: the user token, then an XSTS token for one relying party (Java or Bedrock). */
import { CraftwireError } from "../errors.js";

const USER_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate";
const XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
export const JAVA_RELYING_PARTY = "rp://api.minecraftservices.com/";

export interface Xsts {
  token: string;
  userHash: string;
  xuid?: string;
}

type Fetch = typeof fetch;

async function postJson(f: Fetch, url: string, body: unknown): Promise<{ status: number; json: Record<string, unknown> }> {
  const res = await f(url, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json", "x-xbl-contract-version": "1" },
    body: JSON.stringify(body),
  });
  return { status: res.status, json: (await res.json().catch(() => ({}))) as Record<string, unknown> };
}

/** XSTS answers 401 with an XErr code for accounts that cannot play; the common ones get a plain explanation. */
const XERR: Record<number, string> = {
  2148916233: "This Microsoft account has no Xbox profile yet; sign in once at minecraft.net to create one.",
  2148916235: "Xbox Live is not available in this account's country.",
  2148916236: "This account needs adult verification on xbox.com.",
  2148916237: "This account needs adult verification on xbox.com.",
  2148916238: "This is a child account; an adult must add it to a Microsoft family first.",
};

export async function xboxUserToken(liveAccessToken: string, f: Fetch = fetch): Promise<string> {
  const r = await postJson(f, USER_AUTH_URL, {
    Properties: { AuthMethod: "RPS", SiteName: "user.auth.xboxlive.com", RpsTicket: `t=${liveAccessToken}` },
    RelyingParty: "http://auth.xboxlive.com",
    TokenType: "JWT",
  });
  if (typeof r.json.Token !== "string") {
    throw new CraftwireError("SIGN_IN_FAILED", `Xbox Live refused the Microsoft token (HTTP ${r.status})`,
      "Sign in again: client_process {action:'sign_in'}.");
  }
  return r.json.Token;
}

export async function xstsToken(userToken: string, relyingParty: string, f: Fetch = fetch): Promise<Xsts> {
  const r = await postJson(f, XSTS_URL, {
    Properties: { SandboxId: "RETAIL", UserTokens: [userToken] },
    RelyingParty: relyingParty,
    TokenType: "JWT",
  });
  if (typeof r.json.Token !== "string") {
    const xerr = Number(r.json.XErr ?? 0);
    throw new CraftwireError("SIGN_IN_FAILED", XERR[xerr] ?? `Xbox Live refused the account (HTTP ${r.status}, XErr ${xerr})`,
      "Fix the account at xbox.com, then sign in again.");
  }
  const claims = (r.json.DisplayClaims as { xui?: { uhs?: string; xid?: string }[] } | undefined)?.xui?.[0];
  if (claims?.uhs === undefined) throw new CraftwireError("SIGN_IN_FAILED", "Xbox Live sent no user hash", "Sign in again.");
  return { token: r.json.Token, userHash: claims.uhs, ...(claims.xid !== undefined ? { xuid: claims.xid } : {}) };
}
