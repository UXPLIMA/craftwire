/** bedrock_client on Linux: finding mcpelauncher and the game, the X screen, and the xdotool/import commands sent. */
import type { ChildProcess, SpawnOptions } from "node:child_process";
import { EventEmitter } from "node:events";
import { mkdirSync, mkdtempSync, utimesSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { delimiter, join } from "node:path";
import { PassThrough } from "node:stream";
import { describe, expect, it } from "vitest";
import { BedrockClient } from "../src/bedrock-client/bedrock-client.js";
import { virtualKey } from "../src/bedrock-client/keys.js";
import { LinuxHelper } from "../src/bedrock-client/linux-helper.js";
import { findLauncher, FLATPAK_ID } from "../src/bedrock-client/mcpelauncher.js";
import { keysym, X11_NAMED } from "../src/bedrock-client/x11-keys.js";
import { X11Screen } from "../src/bedrock-client/x11-screen.js";
import { CraftwireError } from "../src/errors.js";
import { linuxChecks } from "../src/doctor-linux.js";

type Fake = ChildProcess & { args: string[]; command: string; options: SpawnOptions; stdout: PassThrough; stderr: PassThrough };

function fakeSpawn(onSpawn: (c: Fake) => void = () => undefined) {
  const spawned: Fake[] = [];
  const spawn = (command: string, args: string[], options: SpawnOptions): ChildProcess => {
    const c = Object.assign(new EventEmitter(), {
      command, args, options, stdout: new PassThrough(), stderr: new PassThrough(), pid: undefined, exitCode: null, signalCode: null,
    }) as unknown as Fake;
    (c as unknown as { kill: () => boolean }).kill = () => {
      (c as unknown as { exitCode: number }).exitCode = 0;
      c.emit("exit", 0);
      return true;
    };
    spawned.push(c);
    onSpawn(c);
    return c;
  };
  return { spawn, spawned };
}

/** xdotool and import answers for one game window (id 200) at the origin, 1280 x 720. */
function fakeRun(missing: string[] = []) {
  const ran: { command: string; args: string[]; display: string | undefined; wayland: string | undefined }[] = [];
  const run = async (command: string, args: string[], o: { env: NodeJS.ProcessEnv }): Promise<Buffer> => {
    ran.push({ command, args, display: o.env.DISPLAY, wayland: o.env.WAYLAND_DISPLAY });
    if (missing.includes(command)) throw new CraftwireError("MISSING_TOOL", `${command} is not installed`);
    if (command === "xdotool" && args[0] === "search") return Buffer.from("100\n200\n");
    if (command === "xdotool" && args[0] === "getwindowgeometry") return Buffer.from("WINDOW=200\nX=0\nY=0\nWIDTH=1280\nHEIGHT=720\nSCREEN=0\n");
    if (command === "xdotool" && args[0] === "getwindowfocus") return Buffer.from("200\n");
    if (command === "import" || command === "ffmpeg") return Buffer.from("PNG");
    return Buffer.alloc(0);
  };
  return { run, ran };
}

const launcher = { command: "/usr/bin/mcpelauncher-client", args: [], gameDir: "/home/u/.local/share/mcpelauncher/versions/1.26.60/" };

function helper(o: { missing?: string[]; env?: NodeJS.ProcessEnv } = {}) {
  const s = fakeSpawn();
  const r = fakeRun(o.missing);
  const h = new LinuxHelper({ env: o.env ?? { CRAFTWIRE_BEDROCK_DISPLAY: ":7" }, run: r.run, spawn: s.spawn, find: () => launcher });
  return { h, ...s, ...r };
}

describe("x11 keys", () => {
  it("names the same keys as the Windows table, as X keysyms", () => {
    expect([keysym("T"), keysym("7"), keysym("f11"), keysym("enter"), keysym("pageup"), keysym("equals")])
      .toEqual(["t", "7", "F11", "Return", "Prior", "equal"]);
    for (const name of Object.keys(X11_NAMED)) expect(() => virtualKey(name)).not.toThrow();
    expect(() => keysym("hyper")).toThrow(/not a key name/);
  });
});

describe("findLauncher", () => {
  const versions = (root: string): string => {
    const v = join(root, "mcpelauncher", "versions");
    for (const [name, age] of [["1.26.50", 100], ["1.26.60", 10], ["broken", 0]] as const) {
      mkdirSync(join(v, name), { recursive: true });
      if (name !== "broken") writeFileSync(join(v, name, "AndroidManifest.xml"), "");
      const t = Date.now() / 1000 - age;
      utimesSync(join(v, name), t, t);
    }
    return v;
  };

  it("takes the client on PATH and the newest downloaded version", () => {
    const data = mkdtempSync(join(tmpdir(), "cw-mcpe-"));
    const v = versions(data);
    const bin = mkdtempSync(join(tmpdir(), "cw-mcpe-bin-"));
    writeFileSync(join(bin, "mcpelauncher-client"), "");
    const l = findLauncher({ env: { PATH: ["", bin].join(delimiter), XDG_DATA_HOME: data }, home: "/nowhere", flatpakInstalled: () => false });
    expect(l).toEqual({ command: join(bin, "mcpelauncher-client"), args: [], gameDir: `${join(v, "1.26.60")}/` });
  });

  it("falls back to the Flatpak and its own data folder", () => {
    const home = mkdtempSync(join(tmpdir(), "cw-mcpe-home-"));
    const v = versions(join(home, ".var", "app", FLATPAK_ID, "data"));
    const l = findLauncher({ env: { PATH: "" }, home, flatpakInstalled: () => true });
    expect(l).toEqual({ command: "flatpak", args: ["run", "--command=mcpelauncher-client", FLATPAK_ID], gameDir: `${join(v, "1.26.60")}/` });
  });

  it("says what to install, and that the game must be downloaded first", () => {
    expect(() => findLauncher({ env: { PATH: "" }, home: "/nowhere", flatpakInstalled: () => false })).toThrow(
      expect.objectContaining({ code: "MCPELAUNCHER_NOT_FOUND" }));
    const empty = mkdtempSync(join(tmpdir(), "cw-mcpe-empty-"));
    expect(() => findLauncher({ env: { CRAFTWIRE_MCPELAUNCHER: "/opt/mcpe/client", XDG_DATA_HOME: empty }, home: "/nowhere", flatpakInstalled: () => false }))
      .toThrow(expect.objectContaining({ code: "BEDROCK_GAME_NOT_DOWNLOADED" }));
    expect(findLauncher({ env: { CRAFTWIRE_MCPELAUNCHER: "/opt/mcpe/client", CRAFTWIRE_BEDROCK_GAME_DIR: "/games/bedrock" }, home: "/x", flatpakInstalled: () => false }))
      .toEqual({ command: "/opt/mcpe/client", args: [], gameDir: "/games/bedrock/" });
  });
});

describe("LinuxHelper", () => {
  it("starts the game on its screen with the connect link, then hands later links to the running game", async () => {
    const { h, spawned, ran } = helper();
    await h.request({ op: "open", url: "minecraft://connect/?serverUrl=a&serverPort=19132" });
    expect(spawned[0]!.command).toBe(launcher.command);
    expect(spawned[0]!.args).toEqual(["-dg", launcher.gameDir, "-ww", "1280", "-wh", "720", "--uri", "minecraft://connect/?serverUrl=a&serverPort=19132"]);
    expect(spawned[0]!.options.env?.DISPLAY).toBe(":7");
    expect(await h.request({ op: "find" })).toEqual({ running: true, focused: true, client: [0, 0, 1280, 720] });
    await h.request({ op: "open", url: "minecraft://connect/?serverUrl=b&serverPort=19133" });
    expect(spawned).toHaveLength(1);
    expect(ran.at(-1)).toMatchObject({ command: launcher.command, args: ["minecraft://connect/?serverUrl=b&serverPort=19133"] });
  });

  it("opens without a link when there is no address", async () => {
    const { h, spawned } = helper();
    await h.request({ op: "open", url: "minecraft://" });
    expect(spawned[0]!.args).not.toContain("--uri");
  });

  it("focuses the game window and sends keys, text, clicks and scrolls through xdotool", async () => {
    const { h, ran } = helper();
    await h.request({ op: "open", url: "minecraft://" });
    const sent = async (req: Record<string, unknown> & { op: string }): Promise<string[]> => {
      await h.request(req);
      return ran.at(-1)!.args;
    };
    expect(await sent({ op: "key", key: "enter", holdMs: 80 })).toEqual(["windowfocus", "200", "keydown", "Return", "sleep", "0.080", "keyup", "Return"]);
    expect(await sent({ op: "text", text: "-hi there" })).toEqual(["windowfocus", "200", "type", "--delay", "30", "--", "-hi there"]);
    expect(await sent({ op: "click", x: 640, y: 360, button: "right" })).toEqual(["windowfocus", "200", "mousemove", "640", "360", "click", "3"]);
    expect(await sent({ op: "scroll", x: 10, y: 20, clicks: -3 }))
      .toEqual(["windowfocus", "200", "mousemove", "10", "20", "click", "--repeat", "3", "--delay", "30", "5"]);
    expect(ran.every((r) => r.display === ":7")).toBe(true);
  });

  it("captures the window with import, or ffmpeg when import is missing", async () => {
    const withImport = helper();
    await withImport.h.request({ op: "open", url: "minecraft://" });
    expect(await withImport.h.request({ op: "capture", maxWidth: 960 })).toEqual({ png: Buffer.from("PNG").toString("base64"), method: "import", client: [0, 0, 1280, 720] });
    expect(withImport.ran.at(-1)).toMatchObject({ command: "import", args: ["-window", "200", "-resize", "960x>", "png:-"] });
    const withFfmpeg = helper({ missing: ["import"] });
    await withFfmpeg.h.request({ op: "open", url: "minecraft://" });
    expect(await withFfmpeg.h.request({ op: "capture", maxWidth: 960 })).toMatchObject({ method: "x11grab" });
    expect(withFfmpeg.ran.at(-1)!.args).toContain(":7+0,0");
    const neither = helper({ missing: ["import", "ffmpeg"] });
    await neither.h.request({ op: "open", url: "minecraft://" });
    await expect(neither.h.request({ op: "capture", maxWidth: 960 })).rejects.toMatchObject({ code: "MISSING_TOOL", message: expect.stringContaining("ffmpeg") });
  });

  it("reports once how the game ended by itself, with its last log lines", async () => {
    const { h, spawned } = helper();
    await h.request({ op: "open", url: "minecraft://" });
    spawned[0]!.stderr.write("Error Failed to load libminecraftpe.so\n");
    await new Promise((r) => setImmediate(r));
    spawned[0]!.emit("exit", 1);
    expect(await h.request({ op: "find" })).toEqual({ running: false, exited: { code: 1, log: ["Error Failed to load libminecraftpe.so"] } });
    expect(await h.request({ op: "find" })).toEqual({ running: false });
    expect(await h.request({ op: "key", key: "t" })).toEqual({ error: "NOT_RUNNING" });
  });

  it("stops the game it started", async () => {
    const { h } = helper();
    expect(await h.request({ op: "close" })).toEqual({ closed: false });
    await h.request({ op: "open", url: "minecraft://" });
    expect(await h.request({ op: "close" })).toEqual({ closed: true });
    expect(await h.request({ op: "find" })).toEqual({ running: false });
  });

  it("starts an Xvfb of its own and keeps the game off Wayland", async () => {
    const s = fakeSpawn((c) => {
      if (c.command === "Xvfb") setImmediate(() => c.stdout.write(`${c.args[0]!.slice(1)}\n`));
    });
    const r = fakeRun();
    const h = new LinuxHelper({ env: { WAYLAND_DISPLAY: "wayland-0", DISPLAY: ":0" }, run: r.run, spawn: s.spawn, find: () => launcher,
      probe: { inUse: (n) => n === 99, socketFiles: () => true } });
    await h.request({ op: "open", url: "minecraft://" });
    expect(s.spawned[0]!.command).toBe("Xvfb");
    expect(s.spawned[0]!.args).toEqual([":100", "-displayfd", "1", "-screen", "0", "1280x720x24", "-nolisten", "tcp"]);
    expect(s.spawned[1]!.options.env).toMatchObject({ DISPLAY: ":100" });
    expect(s.spawned[1]!.options.env?.WAYLAND_DISPLAY).toBeUndefined();
    await h.request({ op: "key", key: "escape" });
    expect(r.ran.at(-1)).toMatchObject({ display: ":100", wayland: undefined });
  });

  it("listens on the abstract socket only where socket files cannot be made (WSL), and skips a taken number", async () => {
    const s = fakeSpawn((c) => {
      if (c.command !== "Xvfb") return;
      setImmediate(() => {
        if (c.args[0] === ":99") {
          c.stderr.write("(EE) Server is already active for display 99\n");
          setImmediate(() => c.emit("exit", 1));
        } else c.stdout.write(`${c.args[0]!.slice(1)}\n`);
      });
    });
    const screen = new X11Screen({}, s.spawn, { inUse: () => false, socketFiles: () => false });
    expect(await screen.display()).toBe(":100");
    expect(s.spawned.map((c) => c.args[0])).toEqual([":99", ":100"]);
    expect(s.spawned[1]!.args.slice(-4)).toEqual(["-nolisten", "tcp", "-nolisten", "unix"]);
  });

  it("names Xvfb's package when it is missing", async () => {
    const s = fakeSpawn((c) => {
      if (c.command === "Xvfb") setImmediate(() => c.emit("error", Object.assign(new Error("spawn Xvfb ENOENT"), { code: "ENOENT" })));
    });
    const h = new LinuxHelper({ env: {}, run: fakeRun().run, spawn: s.spawn, find: () => launcher });
    await expect(h.request({ op: "open", url: "minecraft://" })).rejects.toMatchObject({ code: "MISSING_TOOL", hint: expect.stringContaining("apt install xvfb") });
  });
});

describe("BedrockClient on Linux", () => {
  it("turns an early exit into an error with the game's log", async () => {
    const answers: Record<string, Record<string, unknown>> = { find: { running: false, exited: { code: 134, log: ["Aborted"] } } };
    const c = new BedrockClient("unused", "linux", { request: async (req) => answers[req.op] ?? {}, close: () => undefined });
    await expect(c.start(undefined, 5_000)).rejects.toMatchObject({ code: "BEDROCK_CLIENT_EXITED", details: { log: ["Aborted"] } });
  });
});

describe("linuxChecks", () => {
  it("names what is missing for client_process and bedrock_client", () => {
    const none = linuxChecks({ env: {}, onPath: () => undefined, findLauncher: () => launcher });
    expect(none.map((c) => c.status)).toEqual(["warn", "ok", "warn"]);
    expect(none[2]!.fix).toBe("apt install xvfb xdotool imagemagick");
    const all = linuxChecks({ env: { DISPLAY: ":0" }, onPath: (n) => `/usr/bin/${n}`, findLauncher: () => launcher });
    expect(all.map((c) => c.status)).toEqual(["ok", "ok"]);
    const noGame = linuxChecks({ env: { DISPLAY: ":0" }, onPath: () => "/usr/bin/x", findLauncher: () => findLauncher({ env: { PATH: "" }, home: "/n", flatpakInstalled: () => false }) });
    expect(noGame).toEqual([expect.objectContaining({ status: "warn", fix: expect.stringContaining("minecraft-linux.github.io") })]);
  });
});
