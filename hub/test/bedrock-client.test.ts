/** bedrock_client: the minecraft:// link, key names, screenshot-to-screen coordinates, errors, and who may use it. */
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { afterEach, describe, expect, it, vi } from "vitest";
import { BedrockClient, connectUrl, type Helper } from "../src/bedrock-client/bedrock-client.js";
import { virtualKey } from "../src/bedrock-client/keys.js";
import type { HelperRequest } from "../src/bedrock-client/win32-helper.js";
import { personServer } from "../src/http/serve-command.js";
import { policyFor, type Person } from "../src/team/tokens.js";
import { json, startHub } from "./helpers/hub.js";

/** A game window at (100, 50), 2560 x 1440, and a log of what the client asked the helper to do. */
function fakeHelper(answers: Partial<Record<string, Record<string, unknown>>> = {}) {
  const sent: HelperRequest[] = [];
  const window = [100, 50, 2560, 1440];
  const helper: Helper = {
    request: async (req) => {
      sent.push(req);
      if (answers[req.op] !== undefined) return answers[req.op]!;
      if (req.op === "find") return { running: true, focused: true, client: window };
      if (req.op === "capture") return { png: "iVBORw0KGgo=", method: "printwindow", client: window };
      return {};
    },
    close: () => undefined,
  };
  return { helper, sent };
}

describe("connectUrl", () => {
  it("names the host and port as Bedrock's connect link takes them", () => {
    expect(connectUrl("80.91.71.38:19132")).toBe("minecraft://connect/?serverUrl=80.91.71.38&serverPort=19132");
    expect(connectUrl("play.example.com")).toBe("minecraft://connect/?serverUrl=play.example.com&serverPort=19132");
    expect(connectUrl("[::1]:19133")).toBe("minecraft://connect/?serverUrl=%3A%3A1&serverPort=19133");
  });
});

describe("virtualKey", () => {
  it("maps names to Windows virtual-key codes", () => {
    expect(virtualKey("T")).toEqual({ vk: 0x54, extended: false });
    expect(virtualKey("7")).toEqual({ vk: 0x37, extended: false });
    expect(virtualKey("f12")).toEqual({ vk: 0x7b, extended: false });
    expect(virtualKey("up")).toEqual({ vk: 0x26, extended: true });
    expect(() => virtualKey("hyper")).toThrow(/not a key name/);
  });
});

describe("BedrockClient", () => {
  it("clicks in the last screenshot's pixels, scaled back to the window on screen", async () => {
    const { helper, sent } = fakeHelper();
    const c = new BedrockClient("unused", "linux", helper);
    const shot = await c.screenshot(1280);
    expect([shot.width, shot.height]).toEqual([1280, 720]);
    await c.click(640, 360, "left");
    expect(sent.at(-1)).toMatchObject({ op: "click", x: 100 + 1280, y: 50 + 720, button: "left" });
  });

  it("refuses a click outside the window", async () => {
    const c = new BedrockClient("unused", "linux", fakeHelper().helper);
    await c.screenshot(1280);
    await expect(c.click(1280, 10, "left")).rejects.toMatchObject({ code: "INVALID_PARAMS" });
  });

  it("sends a chat command as T, the text with its slash, then Enter", async () => {
    const { helper, sent } = fakeHelper();
    await new BedrockClient("unused", "win32", helper).command("daily");
    expect(sent.map((r) => r.op === "text" ? r.text : r.vk)).toEqual([0x54, "/daily", 0x0d]);
  });

  it("names a missing game window as not running", async () => {
    const { helper } = fakeHelper({ capture: { error: "Exception calling \"Capture\": \"NOT_RUNNING\"" } });
    await expect(new BedrockClient("unused", "win32", helper).screenshot()).rejects.toMatchObject({ code: "BEDROCK_CLIENT_NOT_RUNNING" });
  });

  it("reports a window Windows kept in the back when the last-resort screen copy needs it in front", async () => {
    const { helper } = fakeHelper({ capture: { error: "Exception calling \"Capture\": \"NOT_FOCUSED\"" } });
    const c = new BedrockClient("unused", "win32", helper);
    await expect(c.screenshot()).rejects.toMatchObject({ code: "BEDROCK_CLIENT_NOT_FOCUSED" });
  });

  it("points Windows clicks and scrolls to keys, without touching the mouse", async () => {
    const { helper, sent } = fakeHelper();
    const c = new BedrockClient("unused", "win32", helper);
    await expect(c.click(10, 10, "left")).rejects.toMatchObject({ code: "BEDROCK_CLICK_UNSUPPORTED" });
    await expect(c.scroll(10, 10, -3)).rejects.toMatchObject({ code: "BEDROCK_CLICK_UNSUPPORTED" });
    expect(sent).toEqual([]);
  });

  /** A Windows game that is running or not: open starts it, close ends it. */
  function gameHelper(running: boolean, park: Record<string, unknown> = { parked: true }) {
    const sent: HelperRequest[] = [];
    let up = running;
    const helper: Helper = {
      request: async (req) => {
        sent.push(req);
        if (req.op === "open") up = true;
        if (req.op === "close") up = false;
        if (req.op === "park") return park;
        if (req.op === "find") return up ? { running: true, focused: false, client: [5000, 100, 1578, 884] } : { running: false };
        return {};
      },
      close: () => undefined,
    };
    return { helper, sent };
  }

  async function run<T>(p: () => Promise<T>): Promise<T> {
    vi.useFakeTimers();
    try {
      const started = p();
      await vi.advanceTimersByTimeAsync(10_000);
      return await started;
    } finally {
      vi.useRealTimers();
    }
  }

  it("moves the game it opened off the screen, again once it has loaded", async () => {
    const { helper, sent } = gameHelper(false);
    const r = await run(() => new BedrockClient("unused", "win32", helper).start("play.example.com"));
    expect(r).toMatchObject({ running: true, connecting: "play.example.com" });
    expect(r.restarted).toBeUndefined();
    expect(sent.map((q) => q.op)).toEqual(["find", "open", "find", "park", "park", "find"]);
  });

  it("leaves a game the user had in front where it is", async () => {
    const { helper, sent } = gameHelper(false, { parked: false });
    await run(() => new BedrockClient("unused", "win32", helper).start(undefined));
    expect(sent.map((q) => q.op)).toEqual(["find", "open", "find", "park", "find"]);
  });

  it("restarts a running Windows game to send it to a server, as a link would only bring it to the front", async () => {
    const { helper, sent } = gameHelper(true);
    const r = await run(() => new BedrockClient("unused", "win32", helper).start("play.example.com"));
    expect(r).toMatchObject({ running: true, restarted: true });
    expect(sent.map((q) => q.op)).toEqual(["find", "close", "find", "open", "find", "park", "park", "find"]);
  });

  it("does nothing to a running Windows game started without an address", async () => {
    const { helper, sent } = gameHelper(true);
    expect(await run(() => new BedrockClient("unused", "win32", helper).start(undefined))).toMatchObject({ running: true });
    expect(sent.map((q) => q.op)).toEqual(["find", "find"]);
  });

  it("sends a running Linux game the link, which its IPC hands over", async () => {
    const { helper, sent } = fakeHelper();
    await run(() => new BedrockClient("unused", "linux", helper).start("play.example.com"));
    expect(sent[0]).toMatchObject({ op: "open", url: "minecraft://connect/?serverUrl=play.example.com&serverPort=19132" });
  });

  it("brings the game back for the user only on Windows", async () => {
    const { helper, sent } = fakeHelper();
    await new BedrockClient("unused", "win32", helper).show();
    expect(sent.at(-1)).toMatchObject({ op: "show" });
    await expect(new BedrockClient("unused", "linux", helper).show()).rejects.toMatchObject({ code: "UNSUPPORTED" });
  });

  it("does not start a helper off Windows and Linux", async () => {
    const { helper, sent } = fakeHelper();
    await expect(new BedrockClient("unused", "darwin", helper).status()).rejects.toMatchObject({ code: "UNSUPPORTED_PLATFORM" });
    expect(sent).toEqual([]);
  });
});

describe("bedrock_client over craftwire serve", () => {
  let hub: Awaited<ReturnType<typeof startHub>> | undefined;
  const clients: Client[] = [];
  afterEach(async () => {
    for (const c of clients.splice(0)) await c.close();
    await hub?.close();
    hub = undefined;
  });

  async function session(person: Person) {
    hub ??= await startHub();
    const ctx = { ...hub.ctx, bedrockClient: new BedrockClient("unused", "win32", fakeHelper().helper) };
    const server = personServer(ctx, undefined, person, `s-${person.name}`);
    const [a, b] = InMemoryTransport.createLinkedPair();
    await server.connect(b);
    const client = new Client({ name: "test", version: "0" });
    await client.connect(a);
    clients.push(client);
    const call = (name: string, args: Record<string, unknown>) => client.callTool({ name, arguments: args }) as Promise<CallToolResult>;
    await call("more_tools", { enable: ["bedrock_client"] });
    return call;
  }

  it("lets the dev role drive the hub machine's game", async () => {
    const call = await session({ name: "dana", role: "dev", ...withPolicy("dev") });
    const r = await call("bedrock_client", { action: "status" });
    expect(r.isError).toBeFalsy();
    expect(json(r)).toMatchObject({ running: true });
  });

  it("keeps it from the bots role, which may use the other extras", async () => {
    const call = await session({ name: "bo", role: "bots", ...withPolicy("bots") });
    const r = await call("bedrock_client", { action: "status" });
    expect(r.isError).toBe(true);
    expect(json(r)).toMatchObject({ code: "UNSUPPORTED" });
  });
});

function withPolicy(role: "dev" | "bots") {
  const p = policyFor(role);
  return p === undefined ? {} : { policy: p };
}
