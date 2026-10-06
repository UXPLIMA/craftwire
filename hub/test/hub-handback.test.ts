import { readFileSync } from "node:fs";
import { mkdtempSync } from "node:fs";
import { createServer, type Server } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { writeHubConfig } from "../src/config.js";
import { startHub } from "../src/hub.js";

const quiet = () => {};
let listening: Server | undefined;
beforeEach(() => { process.env.CRAFTWIRE_PORT = "0"; });
afterEach(() => { delete process.env.CRAFTWIRE_PORT; listening?.close(); listening = undefined; });

const portIn = (home: string) => (JSON.parse(readFileSync(join(home, "hub.json"), "utf8")) as { port: number }).port;

async function aLiveHubPort(): Promise<number> {
  listening = createServer();
  await new Promise<void>((r) => listening!.listen(0, "127.0.0.1", r));
  return (listening.address() as { port: number }).port;
}

describe("hub.json hand-back", () => {
  it("a hub that started while another was running hands the agents back when it closes", async () => {
    const home = mkdtempSync(join(tmpdir(), "cw-home-"));
    const older = await aLiveHubPort();
    writeHubConfig({ port: older, token: "b".repeat(64) }, home);
    const hub = await startHub(home, quiet);
    expect(portIn(home)).toBe(hub.port);
    await hub.close();
    expect(portIn(home)).toBe(older);
    expect(JSON.parse(readFileSync(join(home, "hub.json"), "utf8")).token).toBe("b".repeat(64));
  });

  it("does not point agents at a hub that is gone", async () => {
    const home = mkdtempSync(join(tmpdir(), "cw-home-"));
    const gone = await aLiveHubPort();
    listening!.close();
    listening = undefined;
    writeHubConfig({ port: gone, token: "b".repeat(64) }, home);
    const hub = await startHub(home, quiet);
    await hub.close();
    expect(portIn(home)).toBe(hub.port);
  });
});
