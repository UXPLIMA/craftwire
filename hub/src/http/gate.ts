import { isIP } from "node:net";

/** Loopback addresses: binding one keeps the hub on this machine. */
export function isLoopback(host: string): boolean {
  const h = host.replace(/^\[|\]$/g, "").toLowerCase();
  return h === "localhost" || h === "::1" || (isIP(h) === 4 && h.startsWith("127."));
}

/** True for addresses that listen on every interface; no Host can be predicted for them. */
export function isWildcard(host: string): boolean {
  const h = host.replace(/^\[|\]$/g, "");
  return h === "0.0.0.0" || h === "::";
}

/**
 * The Host headers a request may carry (DNS-rebinding protection: a web page that points its own domain at
 * 127.0.0.1 still sends that domain as Host). Undefined means any (a wildcard bind, where the token is the guard).
 */
export function allowedHosts(host: string, port: number, extra: string[] = []): Set<string> | undefined {
  if (isWildcard(host) && extra.length === 0) return undefined;
  const names = isLoopback(host) ? ["127.0.0.1", "localhost", "[::1]"] : isWildcard(host) ? [] : [host.includes(":") && !host.startsWith("[") ? `[${host}]` : host];
  const out = new Set<string>();
  for (const n of [...names, ...extra]) {
    out.add(n.toLowerCase());
    out.add(`${n}:${port}`.toLowerCase());
  }
  return out;
}

export function hostAllowed(header: string | undefined, allowed: Set<string> | undefined): boolean {
  if (allowed === undefined) return true;
  return header !== undefined && allowed.has(header.toLowerCase());
}

/** Browsers send Origin; MCP clients do not. A request with an Origin passes only when that origin is listed. */
export function originAllowed(origin: string | undefined, allowed: readonly string[]): boolean {
  if (origin === undefined) return true;
  return allowed.some((a) => a.toLowerCase() === origin.toLowerCase());
}

/** A token bucket: `perMinute` requests a minute, bursts up to the same number. */
export class RateLimiter {
  private tokens: number;
  private last: number;

  constructor(private readonly perMinute: number, private readonly now: () => number = Date.now) {
    this.tokens = perMinute;
    this.last = now();
  }

  /** Takes one request; returns the seconds to wait when there is none left. */
  take(): number | undefined {
    const t = this.now();
    this.tokens = Math.min(this.perMinute, this.tokens + ((t - this.last) / 60_000) * this.perMinute);
    this.last = t;
    if (this.tokens >= 1) {
      this.tokens -= 1;
      return undefined;
    }
    return Math.ceil(((1 - this.tokens) * 60) / this.perMinute);
  }
}

/** The token from `Authorization: Bearer <token>`; tokens in the URL are never read. */
export function bearer(header: string | undefined): string | undefined {
  const m = /^Bearer\s+(\S+)\s*$/i.exec(header ?? "");
  return m?.[1];
}
