/**
 * Microsoft account sign-in by device code on login.live.com, as the Minecraft launcher's title does: the user opens
 * the verification page and types a short code; the hub polls until the token arrives and keeps the refresh token.
 */
import { CraftwireError } from "../errors.js";

/** The Minecraft Java title's client id and the Xbox Live scope it signs in with (live.com tokens, RpsTicket "t="). */
export const LIVE_CLIENT_ID = "00000000402b5328";
export const LIVE_SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";
const DEVICE_CODE_URL = "https://login.live.com/oauth20_connect.srf";
const TOKEN_URL = "https://login.live.com/oauth20_token.srf";

export interface DeviceCode {
  userCode: string;
  verificationUri: string;
  deviceCode: string;
  expiresAt: number;
  intervalMs: number;
}

export interface LiveToken {
  accessToken: string;
  refreshToken: string;
  expiresAt: number;
}

type Fetch = typeof fetch;

async function post(f: Fetch, url: string, form: Record<string, string>): Promise<Record<string, unknown>> {
  const res = await f(url, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json" },
    body: new URLSearchParams(form).toString(),
  });
  const body = (await res.json().catch(() => ({}))) as Record<string, unknown>;
  return { ...body, httpStatus: res.status };
}

export async function requestDeviceCode(f: Fetch = fetch, now = Date.now): Promise<DeviceCode> {
  const r = await post(f, DEVICE_CODE_URL, { client_id: LIVE_CLIENT_ID, scope: LIVE_SCOPE, response_type: "device_code" });
  if (typeof r.device_code !== "string" || typeof r.user_code !== "string") {
    throw new CraftwireError("SIGN_IN_FAILED", `Microsoft did not give a device code (HTTP ${String(r.httpStatus)})`,
      "Check the network connection and try client_process {action:'sign_in'} again.");
  }
  return {
    userCode: r.user_code,
    verificationUri: typeof r.verification_uri === "string" ? r.verification_uri : "https://www.microsoft.com/link",
    deviceCode: r.device_code,
    expiresAt: now() + Number(r.expires_in ?? 900) * 1000,
    intervalMs: Math.max(1, Number(r.interval ?? 5)) * 1000,
  };
}

function token(r: Record<string, unknown>, now: number): LiveToken {
  return { accessToken: String(r.access_token), refreshToken: String(r.refresh_token), expiresAt: now + Number(r.expires_in ?? 3600) * 1000 };
}

/** One poll: the token once the user has entered the code, undefined while they have not (authorization_pending). */
export async function pollDeviceCode(code: DeviceCode, f: Fetch = fetch, now = Date.now): Promise<LiveToken | undefined> {
  const r = await post(f, TOKEN_URL, {
    client_id: LIVE_CLIENT_ID, device_code: code.deviceCode, grant_type: "urn:ietf:params:oauth:grant-type:device_code",
  });
  if (typeof r.access_token === "string") return token(r, now());
  if (r.error === "authorization_pending" || r.error === "slow_down") return undefined;
  if (r.error === "expired_token") {
    throw new CraftwireError("SIGN_IN_EXPIRED", "The sign-in code expired before it was entered", "Start again: client_process {action:'sign_in'}.");
  }
  throw new CraftwireError("SIGN_IN_FAILED", `Microsoft refused the sign-in: ${String(r.error_description ?? r.error ?? r.httpStatus)}`,
    "Start again with client_process {action:'sign_in'}; the account needs Minecraft Java.");
}

export async function refreshLive(refreshToken: string, f: Fetch = fetch, now = Date.now): Promise<LiveToken> {
  const r = await post(f, TOKEN_URL, { client_id: LIVE_CLIENT_ID, scope: LIVE_SCOPE, grant_type: "refresh_token", refresh_token: refreshToken });
  if (typeof r.access_token !== "string") {
    throw new CraftwireError("SIGN_IN_EXPIRED", "The saved Microsoft sign-in is no longer valid",
      "Sign in again: client_process {action:'sign_in'}.");
  }
  return token(r, now());
}
