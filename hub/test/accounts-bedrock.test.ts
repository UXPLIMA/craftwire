/**
 * A Bedrock account bot's tokens: the hub asks Xbox Live for one XSTS token per service (the login chain and
 * PlayFab, where the multiplayer token comes from) and hands both to the runner; the refresh token is rotated.
 */
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { Accounts } from "../src/auth/accounts.js";

/** Microsoft and Xbox Live as far as a refresh goes: each XSTS token names its relying party. */
function fakeLive() {
  const parties: string[] = [];
  const f = (async (url: string | URL, init?: RequestInit) => {
    const u = String(url);
    const reply = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
    if (u.startsWith("https://login.live.com/")) return reply({ access_token: "live", refresh_token: "rotated", expires_in: 3600 });
    if (u.startsWith("https://user.auth.xboxlive.com/")) return reply({ Token: "user" });
    const party = (JSON.parse(String(init?.body)) as { RelyingParty: string }).RelyingParty;
    parties.push(party);
    return reply({ Token: `xsts-for-${party}`, DisplayClaims: { xui: [{ uhs: "hash-1" }] } });
  }) as typeof fetch;
  return { f, parties };
}

describe("Accounts.bedrock", () => {
  it("gets an XSTS token for the login chain and one for PlayFab", async () => {
    const home = mkdtempSync(join(tmpdir(), "cw-acc-"));
    mkdirSync(join(home, "auth"), { recursive: true });
    writeFileSync(join(home, "auth", "accounts.json"), JSON.stringify({ Steve: { uuid: "u", refreshToken: "old", signedInAt: 1 } }));
    const { f, parties } = fakeLive();

    const b = await new Accounts(home, f).bedrock("Steve");

    expect(parties.sort()).toEqual(["http://playfab.xboxlive.com/", "https://multiplayer.minecraft.net/"]);
    expect(b.chain).toMatchObject({ token: "xsts-for-https://multiplayer.minecraft.net/", userHash: "hash-1" });
    expect(b.playfab).toMatchObject({ token: "xsts-for-http://playfab.xboxlive.com/", userHash: "hash-1" });
    const saved = JSON.parse(readFileSync(join(home, "auth", "accounts.json"), "utf8")) as Record<string, { refreshToken: string }>;
    expect(saved.Steve!.refreshToken).toBe("rotated");
  });
});
