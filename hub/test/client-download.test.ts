import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { fetchAll, fetchVerified } from "../src/client/download.js";

const HELLO_SHA1 = "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d";
let server: Server;
let base: string;
let flaky = 0;
const dir = mkdtempSync(join(tmpdir(), "cw-dl-"));

beforeAll(async () => {
  server = createServer((req, res) => {
    if (req.url === "/good") return res.end("hello");
    if (req.url === "/bad") return res.end("nope!");
    if (req.url === "/flaky") {
      flaky += 1;
      if (flaky === 1) { res.statusCode = 500; return res.end("oops"); }
      return res.end("hello");
    }
    res.statusCode = 404;
    res.end();
  });
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", () => r()));
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});
afterAll(() => new Promise<void>((r) => server.close(() => r())));

const fast = { retryDelayMs: 1 };

describe("verified downloads", () => {
  it("downloads and verifies", async () => {
    expect(await fetchVerified({ url: `${base}/good`, sha1: HELLO_SHA1, dest: join(dir, "a", "x.bin") }, fast)).toBe(5);
    expect(readFileSync(join(dir, "a", "x.bin"), "utf8")).toBe("hello");
  });

  it("discards a file whose sha1 differs and leaves no .part", async () => {
    await expect(fetchVerified({ url: `${base}/bad`, sha1: HELLO_SHA1, dest: join(dir, "b.bin") }, { ...fast, retries: 0 }))
      .rejects.toMatchObject({ code: "CHECKSUM_MISMATCH" });
    expect(existsSync(join(dir, "b.bin"))).toBe(false);
    expect(existsSync(join(dir, "b.bin.part"))).toBe(false);
  });

  it("retries after a server error", async () => {
    expect(await fetchVerified({ url: `${base}/flaky`, sha1: HELLO_SHA1, dest: join(dir, "c.bin") }, { ...fast, retries: 2 })).toBe(5);
  });

  it("does not refetch a cached file with the right sha1", async () => {
    writeFileSync(join(dir, "d.bin"), "hello");
    expect(await fetchVerified({ url: `${base}/missing`, sha1: HELLO_SHA1, dest: join(dir, "d.bin") }, fast)).toBe(0);
  });

  it("re-fetches a cached file whose sha1 is wrong", async () => {
    writeFileSync(join(dir, "e.bin"), "hel");
    expect(await fetchVerified({ url: `${base}/good`, sha1: HELLO_SHA1, dest: join(dir, "e.bin") }, fast)).toBe(5);
    expect(readFileSync(join(dir, "e.bin"), "utf8")).toBe("hello");
  });

  it("reports DOWNLOAD_FAILED with the url after the retries", async () => {
    await expect(fetchVerified({ url: `${base}/missing`, sha1: HELLO_SHA1, dest: join(dir, "f.bin") }, { ...fast, retries: 1 }))
      .rejects.toMatchObject({ code: "DOWNLOAD_FAILED", message: expect.stringContaining("/missing") });
  });

  it("fetchAll downloads every file and reports progress", async () => {
    const seen: [number, number][] = [];
    const bytes = await fetchAll([1, 2, 3].map((i) => ({ url: `${base}/good`, sha1: HELLO_SHA1, dest: join(dir, `g${i}.bin`) })),
      { ...fast, onProgress: (done, total) => seen.push([done, total]) });
    expect(bytes).toBe(15);
    expect(seen.at(-1)).toEqual([3, 3]);
  });
});
