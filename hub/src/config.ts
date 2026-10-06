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

/** The hub.json a hub wrote, if it is there and readable. */
export function readHubConfig(home: string = craftwireHome()): HubConfig | undefined {
  try {
    const o = JSON.parse(readFileSync(join(home, "hub.json"), "utf8")) as Partial<HubConfig>;
    return typeof o.port === "number" && typeof o.token === "string" ? { port: o.port, token: o.token } : undefined;
  } catch {
    return undefined;
  }
}

/**
 * The token HTTP clients of `craftwire serve` send (`<home>/http.json`), separate from the agents' hub token so it
 * can be handed to other machines. `rotate` replaces it.
 */
export function loadOrCreateHttpToken(home: string = craftwireHome(), rotate = false): { token: string; file: string; created: boolean } {
  const file = join(home, "http.json");
  if (!rotate && existsSync(file)) {
    try {
      const parsed = JSON.parse(readFileSync(file, "utf8")) as { token?: unknown };
      if (typeof parsed.token === "string" && /^[0-9a-f]{64}$/.test(parsed.token)) return { token: parsed.token, file, created: false };
    } catch {
      // corrupt file: mint a new token
    }
  }
  const token = randomBytes(32).toString("hex");
  mkdirSync(home, { recursive: true });
  writeFileSync(file, JSON.stringify({ token }, null, 2), { mode: 0o600 });
  try {
    chmodSync(file, 0o600);
  } catch {
    // Windows relies on the user-profile ACL
  }
  return { token, file, created: true };
}

/** The HTTP token if `craftwire serve` made one. */
export function readHttpToken(home: string = craftwireHome()): string | undefined {
  try {
    const t = (JSON.parse(readFileSync(join(home, "http.json"), "utf8")) as { token?: unknown }).token;
    return typeof t === "string" && /^[0-9a-f]{64}$/.test(t) ? t : undefined;
  } catch {
    return undefined;
  }
}

export function tokensEqual(a: string, b: string): boolean {
  const ba = Buffer.from(a);
  const bb = Buffer.from(b);
  return ba.length === bb.length && timingSafeEqual(ba, bb);
}

/** A setup mistake the user has to fix; the CLI prints only its message. */
export class ConfigError extends Error {}

/** The hub's port: CRAFTWIRE_PORT, or 47821. 0 picks a free port (tests). */
export function hubPort(value: string | undefined = process.env.CRAFTWIRE_PORT): number {
  if (value === undefined || value.trim() === "") return 47821;
  const port = /^\d+$/.test(value.trim()) ? Number(value.trim()) : NaN;
  if (!Number.isInteger(port) || port > 65535) {
    throw new ConfigError(`CRAFTWIRE_PORT must be a port number (0-65535), got "${value}". Fix or unset it in the MCP server's env.`);
  }
  return port;
}
