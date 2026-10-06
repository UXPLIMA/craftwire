import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { CraftwireError } from "../errors.js";

/** One file to fetch: where from, its published sha1 (and size), where to keep it. */
export interface FileSpec {
  url: string;
  sha1: string;
  size?: number;
  dest: string;
}

export interface FetchOptions {
  fetchImpl?: typeof fetch;
  /** Extra attempts after the first (default 3). */
  retries?: number;
  /** Base delay between attempts, doubled each time (default 500 ms). */
  retryDelayMs?: number;
}

const USER_AGENT = "craftwire (https://github.com/uxplima/craftwire)";
const RETRY_HINT = "Check the network connection, then run client_process start again (finished files are kept).";

export function sha1Of(data: Buffer): string {
  return createHash("sha1").update(data).digest("hex");
}

export function sha1File(path: string): string {
  return sha1Of(readFileSync(path));
}

/**
 * Downloads `f.url` to `f.dest` unless a file with the right sha1 is already there. The body goes to `dest.part`
 * and is renamed only after its size and sha1 match, so a cache entry is never partial or corrupt. Returns the
 * bytes fetched (0 when cached).
 */
export async function fetchVerified(f: FileSpec, o: FetchOptions = {}): Promise<number> {
  if (existsSync(f.dest) && sha1File(f.dest) === f.sha1) return 0;
  const retries = o.retries ?? 3;
  const doFetch = o.fetchImpl ?? fetch;
  let last: CraftwireError | undefined;
  for (let attempt = 0; attempt <= retries; attempt++) {
    if (attempt > 0) await sleep((o.retryDelayMs ?? 500) * 2 ** (attempt - 1));
    let body: Buffer;
    try {
      const res = await doFetch(f.url, { headers: { "User-Agent": USER_AGENT } });
      if (!res.ok) {
        last = new CraftwireError("DOWNLOAD_FAILED", `${f.url}: HTTP ${res.status}`, RETRY_HINT);
        continue;
      }
      body = Buffer.from(await res.arrayBuffer());
    } catch (e) {
      last = new CraftwireError("DOWNLOAD_FAILED", `${f.url}: ${(e as Error).message}`, RETRY_HINT);
      continue;
    }
    const sum = sha1Of(body);
    if (sum !== f.sha1 || (f.size !== undefined && body.length !== f.size)) {
      last = new CraftwireError("CHECKSUM_MISMATCH", `${f.url}: expected sha1 ${f.sha1} (${f.size ?? "?"} bytes), got ${sum} (${body.length} bytes)`,
        "The download was corrupted or the source changed; run client_process start again.");
      continue;
    }
    mkdirSync(dirname(f.dest), { recursive: true });
    const part = `${f.dest}.part`;
    try {
      writeFileSync(part, body);
      renameSync(part, f.dest);
    } catch (e) {
      rmSync(part, { force: true });
      throw e;
    }
    return body.length;
  }
  throw last!;
}

/** Fetches many files with a small worker pool; `onProgress(done, total)` after each one. Returns the bytes fetched. */
export async function fetchAll(files: FileSpec[], o: FetchOptions & { concurrency?: number; onProgress?: (done: number, total: number) => void } = {}): Promise<number> {
  let next = 0;
  let done = 0;
  let bytes = 0;
  const worker = async () => {
    while (next < files.length) {
      const f = files[next++]!;
      const n = await fetchVerified(f, o);   // not `bytes += await …`: that reads bytes before the await
      bytes += n;
      done += 1;
      o.onProgress?.(done, files.length);
    }
  };
  await Promise.all(Array.from({ length: Math.min(o.concurrency ?? 8, files.length) }, worker));
  return bytes;
}
