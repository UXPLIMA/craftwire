import { createServer, type Server } from "node:net";
import { afterEach, describe, expect, it } from "vitest";
import { pingServer, readVarInt, varInt } from "../src/client/server-ping.js";

let server: Server | undefined;
afterEach(() => new Promise<void>((res) => (server ? server.close(() => res()) : res())));

/** A minimal server that answers the status handshake like Minecraft does, or behaves badly. */
function listen(reply: (send: (b: Buffer) => void) => void): Promise<number> {
  server = createServer((sock) => {
    let got = Buffer.alloc(0);
    sock.on("data", (d) => {
      got = Buffer.concat([got, d]);
      // handshake + status request received: both packets have arrived once a 0x01 0x00 request trails
      if (got.length >= 2 && got[got.length - 2] === 1 && got[got.length - 1] === 0) reply((b) => sock.write(b));
    });
    sock.on("error", () => {});
  });
  return new Promise((res) => server!.listen(0, "127.0.0.1", () => res((server!.address() as { port: number }).port)));
}

function statusPacket(json: string): Buffer {
  const body = Buffer.concat([varInt(0), varInt(Buffer.byteLength(json)), Buffer.from(json)]);
  return Buffer.concat([varInt(body.length), body]);
}

describe("server list ping", () => {
  it("encodes and decodes VarInts", () => {
    for (const n of [0, 1, 127, 128, 255, 25565, 2097151, 2147483647]) {
      expect(readVarInt(varInt(n), 0)).toEqual({ value: n, size: varInt(n).length });
    }
    expect(varInt(-1).length).toBe(5);
  });

  it("reads the version a server reports", async () => {
    const port = await listen((send) => send(statusPacket(JSON.stringify({ version: { name: "Paper 26.3", protocol: 780 }, players: { max: 20, online: 0 } }))));
    expect(await pingServer("127.0.0.1", port, 2000)).toEqual({ name: "Paper 26.3", protocol: 780 });
  });

  it("copes with a reply split over several TCP chunks", async () => {
    const port = await listen((send) => {
      const p = statusPacket(JSON.stringify({ version: { name: "26.2", protocol: 775 } }));
      send(p.subarray(0, 3));
      setTimeout(() => send(p.subarray(3)), 20);
    });
    expect((await pingServer("127.0.0.1", port, 2000))?.name).toBe("26.2");
  });

  it("gives undefined when nothing answers in time or the port is closed", async () => {
    const port = await listen(() => {});
    expect(await pingServer("127.0.0.1", port, 200)).toBeUndefined();
    await new Promise<void>((res) => server!.close(() => res()));
    server = undefined;
    expect(await pingServer("127.0.0.1", port, 500)).toBeUndefined();
  });
});
