import { randomBytes, timingSafeEqual } from "node:crypto";
import { chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

export interface HubConfig {
  port: number;
  token: string;
}

export function craftwireHome(): string {
  const env = process.env.CRAFTWIRE_HOME;
  return env && env.trim() !== "" ? env : join(homedir(), ".craftwire");
}

export function loadOrCreateToken(home: string = craftwireHome()): string {
  const file = join(home, "hub.json");
  if (existsSync(file)) {
    try {
      const parsed = JSON.parse(readFileSync(file, "utf8")) as Partial<HubConfig>;
      if (typeof parsed.token === "string" && /^[0-9a-f]{64}$/.test(parsed.token)) return parsed.token;
    } catch {
      // corrupt file: fall through and mint a new token
    }
  }
  return randomBytes(32).toString("hex");
}

export function writeHubConfig(config: HubConfig, home: string = craftwireHome()): string {
  mkdirSync(home, { recursive: true });
  const file = join(home, "hub.json");
  writeFileSync(file, JSON.stringify({ port: config.port, token: config.token }, null, 2), { mode: 0o600 });
  try {
    chmodSync(file, 0o600);
  } catch {
    // Windows relies on the user-profile ACL
  }
  return file;
}

export function tokensEqual(a: string, b: string): boolean {
  const ba = Buffer.from(a);
  const bb = Buffer.from(b);
  return ba.length === bb.length && timingSafeEqual(ba, bb);
}
