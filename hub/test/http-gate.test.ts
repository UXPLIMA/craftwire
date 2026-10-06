import { describe, expect, it } from "vitest";
import { allowedHosts, bearer, hostAllowed, isLoopback, originAllowed, RateLimiter } from "../src/http/gate.js";

describe("HTTP gate", () => {
  it("knows loopback addresses", () => {
    for (const h of ["127.0.0.1", "127.1.2.3", "localhost", "::1", "[::1]"]) expect(isLoopback(h), h).toBe(true);
    for (const h of ["0.0.0.0", "192.168.1.5", "example.com", "::"]) expect(isLoopback(h), h).toBe(false);
  });

  it("accepts only the bound host names as Host", () => {
    const local = allowedHosts("127.0.0.1", 7777);
    expect(hostAllowed("127.0.0.1:7777", local)).toBe(true);
    expect(hostAllowed("localhost:7777", local)).toBe(true);
    expect(hostAllowed("[::1]:7777", local)).toBe(true);
    expect(hostAllowed("evil.example:7777", local)).toBe(false);
    expect(hostAllowed(undefined, local)).toBe(false);
    const lan = allowedHosts("192.168.1.5", 7777, ["hub.lan"]);
    expect(hostAllowed("192.168.1.5:7777", lan)).toBe(true);
    expect(hostAllowed("hub.lan:7777", lan)).toBe(true);
    expect(hostAllowed("localhost:7777", lan)).toBe(false);
    expect(allowedHosts("0.0.0.0", 7777)).toBeUndefined();
  });

  it("rejects browser origins unless listed", () => {
    expect(originAllowed(undefined, [])).toBe(true);
    expect(originAllowed("https://evil.example", [])).toBe(false);
    expect(originAllowed("https://app.example", ["https://app.example"])).toBe(true);
  });

  it("reads only a bearer header", () => {
    expect(bearer("Bearer abc123")).toBe("abc123");
    expect(bearer("bearer abc123 ")).toBe("abc123");
    expect(bearer("Basic abc")).toBeUndefined();
    expect(bearer(undefined)).toBeUndefined();
  });

  it("limits requests per minute and refills over time", () => {
    let t = 0;
    const r = new RateLimiter(3, () => t);
    expect([r.take(), r.take(), r.take()]).toEqual([undefined, undefined, undefined]);
    expect(r.take()).toBe(20);
    t += 20_000;
    expect(r.take()).toBeUndefined();
  });
});
