import { connect } from "node:net";

/** Minecraft's VarInt: 7 bits per byte, low group first, high bit = more bytes follow; negatives take 5 bytes. */
export function varInt(n: number): Buffer {
  const out: number[] = [];
  let v = n >>> 0;
  do {
    let b = v & 0x7f;
    v >>>= 7;
    if (v !== 0) b |= 0x80;
    out.push(b);
  } while (v !== 0);
  return Buffer.from(out);
}

/** The VarInt at `offset`, or undefined when `buf` ends before it does. */
export function readVarInt(buf: Buffer, offset: number): { value: number; size: number } | undefined {
  let value = 0;
  for (let i = 0; i < 5; i++) {
    if (offset + i >= buf.length) return undefined;
    const b = buf[offset + i]!;
    value |= (b & 0x7f) << (7 * i);
    if ((b & 0x80) === 0) return { value: value >>> 0, size: i + 1 };
  }
  throw new Error("VarInt longer than 5 bytes");
}

function packet(id: number, ...fields: Buffer[]): Buffer {
  const body = Buffer.concat([varInt(id), ...fields]);
  return Buffer.concat([varInt(body.length), body]);
}

function mcString(s: string): Buffer {
  const b = Buffer.from(s, "utf8");
  return Buffer.concat([varInt(b.length), b]);
}

/**
 * Asks a Minecraft server for its version with the status handshake every client does for the server list
 * ("Server List Ping"). Undefined when nothing answers within `timeoutMs` or the reply is not a status.
 */
export function pingServer(host: string, port: number, timeoutMs = 3000): Promise<{ name: string; protocol: number } | undefined> {
  return new Promise((resolve) => {
    let done = false;
    let got = Buffer.alloc(0);
    const sock = connect({ host, port });
    const finish = (v: { name: string; protocol: number } | undefined) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      sock.destroy();
      resolve(v);
    };
    const timer = setTimeout(() => finish(undefined), timeoutMs);
    sock.on("error", () => finish(undefined));
    sock.on("close", () => finish(undefined));
    sock.on("connect", () => {
      const port16 = Buffer.alloc(2);
      port16.writeUInt16BE(port);
      // Handshake (protocol -1 = "just asking", next state 1 = status), then the status request.
      sock.write(Buffer.concat([packet(0x00, varInt(-1), mcString(host), port16, varInt(1)), packet(0x00)]));
    });
    sock.on("data", (d) => {
      got = Buffer.concat([got, d]);
      try {
        const len = readVarInt(got, 0);
        if (len === undefined || got.length < len.size + len.value) return;
        const id = readVarInt(got, len.size);
        if (id === undefined || id.value !== 0x00) return finish(undefined);
        const strLen = readVarInt(got, len.size + id.size);
        if (strLen === undefined) return finish(undefined);
        const start = len.size + id.size + strLen.size;
        const json = JSON.parse(got.subarray(start, start + strLen.value).toString("utf8")) as { version?: { name?: unknown; protocol?: unknown } };
        const v = json.version;
        finish(typeof v?.name === "string" && typeof v.protocol === "number" ? { name: v.name, protocol: v.protocol } : undefined);
      } catch {
        finish(undefined);
      }
    });
  });
}
