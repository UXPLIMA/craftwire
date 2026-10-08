import { resolve as resolvePath } from "node:path";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";
import { CraftwireError } from "../errors.js";
import { defineTool, forward, ok, targetArgs, type ToolContext } from "./registry.js";

const vec3 = { x: z.number(), y: z.number(), z: z.number() };

// Camera moves over time: `camera` path/orbit, and record start's `camera`.
const keyframe = z.object({ t: z.number().min(0), ...vec3, yaw: z.number().optional(), pitch: z.number().min(-90).max(90).optional() });
const motionShape = {
  keyframes: z.array(keyframe).min(2).max(64).optional().describe("path: timed keyframes; t in ms from the start"),
  interpolation: z.enum(["smooth", "linear"]).optional().describe("path: smooth curves (default) or straight lines"),
  ease: z.enum(["inOut", "none"]).optional().describe("start and stop gently (path default) or keep a constant speed (orbit default)"),
  lookAt: z.object(vec3).optional().describe("path: keep aiming at this point; keyframe yaw/pitch are then optional"),
  center: z.object(vec3).optional().describe("orbit: the point to circle and face"),
  radius: z.number().positive().optional(),
  height: z.number().optional().describe("orbit: camera height above the center (default radius / 2)"),
  startAngle: z.number().optional().describe("orbit: degrees, 0 = south of the center (default: where the camera is now)"),
  degrees: z.number().optional().describe("orbit: how far to turn (default 360; negative turns the other way)"),
  durationMs: z.number().int().positive().max(600_000).optional(),
};
const cameraMotion = z.object({ action: z.enum(["path", "orbit"]), ...motionShape });

const fwd = (method: string, timeoutMs?: number) => async (args: Record<string, unknown>, c: ToolContext) =>
  ok(await forward(c, "client", method, args, timeoutMs));

export function registerClientTools(server: McpServer, ctx: ToolContext): void {
  defineTool(server, ctx, "player_state",
    "Local player snapshot: position, rotation, health, game mode, dimension, selected hotbar slot, inventory, and the block/entity under the crosshair.",
    { ...targetArgs }, fwd("player.state"));

  defineTool(server, ctx, "hud_read",
    "Read HUD state as plain text: bossbars (name, progress, color), actionbar, title/subtitle; sidebar {title, entries:[{name, value, shown}]} with lines as drawn (team prefix + name + suffix, game order, hidden # holders left out; shown is the number text or null when hidden); tab {header, footer, players:[{name, display, ping, gameMode, score?}]}; belowName {title, entries}; tabList (display names).",
    { ...targetArgs }, fwd("hud.read"));

  defineTool(server, ctx, "gui_read",
    "Describe the open screen: title, type, slots (slot, item id, count, name, lore, enchanted, container), hovered slot tooltip, and widgets (buttons/text fields with index and text). Returns {open:false} when no screen is open.",
    { ...targetArgs }, fwd("gui.read"));

  defineTool(server, ctx, "gui_action",
    "Interact with the open screen. hover/click/right_click/shift_click take `slot` (from gui_read). drag moves `slot` → `toSlot`. click_widget takes `widget` (index or visible text). type sends `text` to the focused field (or `widget`). close closes the screen. Returns the fresh gui_read state.",
    {
      ...targetArgs,
      action: z.enum(["hover", "click", "right_click", "shift_click", "drag", "click_widget", "type", "close"]),
      slot: z.number().int().min(0).optional(),
      toSlot: z.number().int().min(0).optional(),
      widget: z.string().optional(),
      text: z.string().max(256).optional(),
    }, fwd("gui.action"));

  defineTool(server, ctx, "input",
    "Simulate gameplay input (no screen open). keys: forward, back, left, right, jump, sneak, sprint, attack, use, pick, drop, inventory, swap_hands, chat, command, player_list, perspective, hide_gui, screenshot. mode press (default, optional durationMs) | hold | release. look adds yaw/pitch degrees. hotbar selects slot 1-9.",
    {
      ...targetArgs,
      keys: z.array(z.string()).optional(),
      mode: z.enum(["press", "hold", "release"]).default("press"),
      durationMs: z.number().int().min(0).max(30_000).optional(),
      look: z.object({ yaw: z.number().optional(), pitch: z.number().optional() }).optional(),
      hotbar: z.number().int().min(1).max(9).optional(),
    }, fwd("input"));

  defineTool(server, ctx, "camera",
    "Control the render camera without moving the player (client-side only). set: x,y,z,yaw,pitch. look_at: aim at `target`. frame_area: fit `area` (min/max corners) in view. frame_entity: fit `entity` (UUID or name). freecam_on/freecam_off. reset returns to the player's eyes. Moves that play over time (for videos, see record): path flies through `keyframes` [{t ms, x,y,z, yaw,pitch}] (interpolation smooth|linear, ease inOut|none, optional `lookAt` point instead of yaw/pitch); orbit circles `center` at `radius` (height above it, startAngle, degrees, durationMs, ease), always facing it. A move holds its last pose when it ends; set/reset stop it. Keep the camera within render distance of the player.",
    {
      ...targetArgs,
      action: z.enum(["set", "look_at", "frame_area", "frame_entity", "freecam_on", "freecam_off", "reset", "path", "orbit"]),
      x: z.number().optional(), y: z.number().optional(), z: z.number().optional(),
      yaw: z.number().optional(), pitch: z.number().min(-90).max(90).optional(),
      target: z.object(vec3).optional(),
      area: z.object({ min: z.object(vec3), max: z.object(vec3) }).optional(),
      entity: z.string().optional(),
      distanceScale: z.number().min(0.2).max(5).default(1),
      ...motionShape,
    }, fwd("camera"));

  defineTool(server, ctx, "client_settings",
    "Adjust rendering settings and return the current values: guiScale (0=auto..8), fov (30-110), renderDistance (2-32), hideHud, windowSize {width,height}. Call with no arguments to just read them.",
    {
      ...targetArgs,
      guiScale: z.number().int().min(0).max(8).optional(),
      fov: z.number().int().min(30).max(110).optional(),
      renderDistance: z.number().int().min(2).max(32).optional(),
      hideHud: z.boolean().optional(),
      windowSize: z.object({ width: z.number().int().min(320), height: z.number().int().min(240) }).optional(),
    }, fwd("client.settings"));

  defineTool(server, ctx, "screenshot",
    "Capture the game frame and return it as an image. hud:false hides the HUD (F1); chat:false keeps the HUD but leaves chat lines (e.g. command feedback) out. maxSize is the long edge of the returned image. savePath (relative to the current directory) also writes the full-resolution image: PNG, or JPEG when it ends in .jpg/.jpeg (a tenth of the size). camera {x,y,z,yaw,pitch,fov} applies only for this capture.",
    {
      ...targetArgs,
      hud: z.boolean().default(true),
      chat: z.boolean().default(true),
      maxSize: z.number().int().min(256).max(4096).default(1600),
      savePath: z.string().optional(),
      format: z.enum(["auto", "png", "jpeg"]).default("auto"),
      camera: z.object({ ...vec3, yaw: z.number(), pitch: z.number(), fov: z.number().int().min(30).max(110).optional() }).optional(),
    },
    async (args, c): Promise<CallToolResult> => {
      const withAbsPath = args.savePath ? { ...args, savePath: resolvePath(args.savePath) } : args;
      const r = (await forward(c, "client", "screenshot", withAbsPath, 20_000)) as {
        mime: string; data: string; width: number; height: number; fullWidth: number; fullHeight: number; savedPath?: string;
      };
      const meta = { width: r.width, height: r.height, fullWidth: r.fullWidth, fullHeight: r.fullHeight, savedPath: r.savedPath };
      return { content: [{ type: "image", data: r.data, mimeType: r.mime }, { type: "text", text: JSON.stringify(meta) }] };
    });

  defineTool(server, ctx, "record",
    "Record a video (MP4, H.264/H.265) of the game frame with its sound, in real time, encoded by the user's ffmpeg (FFMPEG_NOT_FOUND says how to install it). start: savePath (.mp4, relative to the current directory); preset max (best, CPU-heavy, 60 fps) | high (default) | balanced (fast, small; good for 60 fps on weaker PCs) | light (720p, small files for Discord); fps, resolution (source/2160p/1440p/1080p/720p/480p, only scales down), codec h264|h265, crf (0-51, lower = better), speed (x264 preset ultrafast..veryslow) and audioBitrate override the preset. audio:false records without sound (with sound, the game is silent on the speakers while it records). hud:false (default) hides the HUD like F1, open menus stay visible; chat:false hides chat lines. durationMs stops by itself; camera {action:'path'|'orbit', ...camera tool fields} starts with the first frame and sets the length; wait:true returns the finished video. maxSeconds (default 120, max 600) is the safety stop. stop finishes the file and returns {savedPath, durationMs, frames, duplicatedFrames, droppedFrames, sizeBytes, ...}; status shows progress or the last result.",
    {
      ...targetArgs,
      action: z.enum(["start", "stop", "status"]),
      savePath: z.string().optional(),
      preset: z.enum(["max", "high", "balanced", "light"]).optional(),
      fps: z.number().int().min(10).max(120).optional(),
      resolution: z.enum(["source", "2160p", "1440p", "1080p", "720p", "480p"]).optional(),
      codec: z.enum(["h264", "h265"]).optional(),
      crf: z.number().int().min(0).max(51).optional(),
      speed: z.enum(["ultrafast", "superfast", "veryfast", "faster", "fast", "medium", "slow", "slower", "veryslow"]).optional(),
      audio: z.boolean().optional(),
      audioBitrate: z.number().int().min(64).max(320).optional(),
      hud: z.boolean().optional(),
      chat: z.boolean().optional(),
      durationMs: z.number().int().min(100).max(600_000).optional(),
      maxSeconds: z.number().int().min(1).max(600).optional(),
      camera: cameraMotion.optional(),
      waitForTerrain: z.boolean().optional(),
      wait: z.boolean().optional(),
    },
    async (args, c) => {
      const params = args.savePath ? { ...args, savePath: resolvePath(args.savePath) } : args;
      // A waiting start lasts as long as the video plus the encoding; the rest answer quickly.
      const length = args.durationMs ?? args.camera?.durationMs ?? motionLength(args.camera?.keyframes) ?? (args.maxSeconds ?? 120) * 1000;
      const timeoutMs = args.action === "start" && args.wait ? length + 120_000 : args.action === "stop" ? 150_000 : 30_000;
      return ok(await forward(c, "client", "record", params, timeoutMs));
    });

  defineTool(server, ctx, "chat",
    "send: say `text` in chat. command: run `text` as a command (leading / optional). read: recent chat and actionbar messages from the hub buffer, optionally filtered by `contains` (case-insensitive) and `since` (epoch ms).",
    {
      ...targetArgs,
      action: z.enum(["send", "command", "read"]),
      text: z.string().max(256).optional(),
      contains: z.string().optional(),
      since: z.number().optional(),
      limit: z.number().int().min(1).max(500).default(50),
    },
    async (args, c) => {
      if (args.action === "read") {
        const inst = c.agents.resolve("client", args.instance);
        const needle = args.contains?.toLowerCase();
        const messages = c.agents.events(inst.id)
          .filter((e) => e.type === "chat" || e.type === "hud")
          .filter((e) => args.since === undefined || e.time >= args.since)
          .map((e) => ({ time: e.time, type: e.type, ...e.data }) as { time: number; type: string; text?: string })
          .filter((m) => !needle || String(m.text ?? "").toLowerCase().includes(needle))
          .slice(-args.limit);
        return ok({ messages });
      }
      if (!args.text) throw new CraftwireError("INVALID_PARAMS", "`text` is required for send/command", "Pass text, e.g. {action:'command', text:'/time set noon'}.");
      return ok(await forward(c, "client", "chat.send", { instance: args.instance, operationId: args.operationId, text: args.text, command: args.action === "command" }));
    });
}

function motionLength(keyframes: Array<{ t: number }> | undefined): number | undefined {
  if (!keyframes || keyframes.length < 2) return undefined;
  return keyframes[keyframes.length - 1]!.t - keyframes[0]!.t;
}
