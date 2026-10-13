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
  keyframes: z.array(keyframe).min(2).max(64).optional(),
  interpolation: z.enum(["smooth", "linear"]).optional(),
  ease: z.enum(["inOut", "none"]).optional(),
  lookAt: z.object(vec3).optional(),
  center: z.object(vec3).optional(),
  radius: z.number().positive().optional(),
  height: z.number().optional(),
  startAngle: z.number().optional(),
  degrees: z.number().optional(),
  durationMs: z.number().int().positive().max(600_000).optional(),
};
const cameraMotion = z.object({ action: z.enum(["path", "orbit"]), ...motionShape });
const point = z.record(z.string(), z.number());
// What the tool list shows for a motion: loose types (cheap in tokens); checkMotion applies motionShape in full.
const looseMotion = {
  keyframes: z.array(point).optional(), interpolation: z.enum(["smooth", "linear"]).optional(), ease: z.enum(["inOut", "none"]).optional(),
  lookAt: point.optional(), center: point.optional(), radius: z.number().optional(), height: z.number().optional(),
  startAngle: z.number().optional(), degrees: z.number().optional(), durationMs: z.number().optional(),
};

function checkMotion(motion: unknown): z.infer<typeof cameraMotion> {
  const r = cameraMotion.safeParse(motion);
  if (!r.success) {
    throw new CraftwireError("INVALID_PARAMS", `Bad camera motion: ${r.error.issues.map((i) => `${i.path.join(".")}: ${i.message}`).join("; ")}`,
      "path needs keyframes [{t,x,y,z,yaw?,pitch?}] (at least 2); orbit needs center {x,y,z} and radius. See craftwire://docs/video.");
  }
  return r.data;
}

const fwd = (method: string, timeoutMs?: number) => async (args: Record<string, unknown>, c: ToolContext) =>
  ok(await forward(c, "client", method, args, timeoutMs));

export function registerClientTools(server: McpServer, ctx: ToolContext): void {
  defineTool(server, ctx, "player_state",
    "Local player: position, rotation, health, game mode, dimension, hotbar slot, inventory, crosshair target.",
    { ...targetArgs }, fwd("player.state"));

  defineTool(server, ctx, "hud_read",
    "Read the HUD as text: bossbars, actionbar, title, sidebar (lines as drawn), tab list (header, footer, players), belowName.",
    { ...targetArgs }, fwd("hud.read"));

  defineTool(server, ctx, "gui_read",
    "Describe the open screen: title, slots (item, count, name, lore), hovered tooltip, widgets. {open:false} when none is open.",
    { ...targetArgs }, fwd("gui.read"));

  defineTool(server, ctx, "gui_action",
    "Act on the open screen: hover/click/right_click/shift_click {slot}, drag {slot,toSlot}, click_widget {widget}, type {text}, close. Returns the new gui_read.",
    {
      ...targetArgs,
      action: z.enum(["hover", "click", "right_click", "shift_click", "drag", "click_widget", "type", "close"]),
      slot: z.number().int().min(0).optional(),
      toSlot: z.number().int().min(0).optional(),
      widget: z.string().optional(),
      text: z.string().max(256).optional(),
    }, fwd("gui.action"));

  defineTool(server, ctx, "input",
    "Gameplay input with no screen open: keys (forward, back, left, right, jump, sneak, sprint, attack, use, pick, drop, inventory, swap_hands, chat, command, ...) with mode press|hold|release; look adds yaw/pitch; hotbar 1-9.",
    {
      ...targetArgs,
      keys: z.array(z.string()).optional(),
      mode: z.enum(["press", "hold", "release"]).default("press"),
      durationMs: z.number().int().min(0).max(30_000).optional(),
      look: z.object({ yaw: z.number().optional(), pitch: z.number().optional() }).optional(),
      hotbar: z.number().int().min(1).max(9).optional(),
    }, fwd("input"));

  defineTool(server, ctx, "camera",
    "Move the render camera, not the player: set, look_at {target}, frame_area {area}, frame_entity {entity}, freecam_on/off, reset; path {keyframes}/orbit {center,radius} play over time. Details: craftwire://docs/video.",
    {
      ...targetArgs,
      action: z.enum(["set", "look_at", "frame_area", "frame_entity", "freecam_on", "freecam_off", "reset", "path", "orbit"]),
      x: z.number().optional(), y: z.number().optional(), z: z.number().optional(),
      yaw: z.number().optional(), pitch: z.number().min(-90).max(90).optional(),
      target: z.object(vec3).optional(),
      area: z.object({ min: z.object(vec3), max: z.object(vec3) }).optional(),
      entity: z.string().optional(),
      distanceScale: z.number().min(0.2).max(5).default(1),
      ...looseMotion,
    },
    async (args, c) => {
      if (args.action === "path" || args.action === "orbit") checkMotion(args);
      return ok(await forward(c, "client", "camera", args));
    });

  defineTool(server, ctx, "client_settings",
    "Read or set guiScale, fov, renderDistance, hideHud, windowSize; no arguments only reads.",
    {
      ...targetArgs,
      guiScale: z.number().int().min(0).max(8).optional(),
      fov: z.number().int().min(30).max(110).optional(),
      renderDistance: z.number().int().min(2).max(32).optional(),
      hideHud: z.boolean().optional(),
      windowSize: z.object({ width: z.number().int().min(320), height: z.number().int().min(240) }).optional(),
    }, fwd("client.settings"));

  const guiLayer = z.enum(["world", "gui", "tooltip", "gui+tooltip"]).optional();
  /** Transparent layers: the file type, split and camera rules, checked before anything reaches the game. */
  const checkLayer = (a: { layer?: string; split?: boolean; camera?: unknown }, badFile: string | undefined) => {
    const transparent = a.layer !== undefined && a.layer !== "world";
    if (a.split && a.layer !== "gui+tooltip") throw new CraftwireError("INVALID_PARAMS", "split needs layer 'gui+tooltip'.", "Pass layer:'gui+tooltip' with split:true.");
    if (!transparent) return;
    if (badFile) throw new CraftwireError("INVALID_PARAMS", badFile, "Change the savePath extension.");
    if (a.camera !== undefined) throw new CraftwireError("INVALID_PARAMS", "camera does not apply to a transparent layer (there is no world in it).", "Leave camera out.");
  };

  defineTool(server, ctx, "screenshot",
    "Capture the game frame. hud/chat:false hide them; maxSize: long edge; savePath writes full size (.png/.jpg); camera: this shot only. layer gui|tooltip|gui+tooltip: transparent PNG of the open menu (craftwire://docs/gui-capture).",
    {
      ...targetArgs,
      hud: z.boolean().default(true),
      chat: z.boolean().default(true),
      maxSize: z.number().int().min(256).max(4096).default(1024),
      savePath: z.string().optional(),
      format: z.enum(["auto", "png", "jpeg"]).default("auto"),
      camera: z.object({ ...vec3, yaw: z.number(), pitch: z.number(), fov: z.number().int().min(30).max(110).optional() }).optional(),
      layer: guiLayer,
      split: z.boolean().optional(),
      trim: z.boolean().optional(),
      guiScale: z.number().int().min(1).max(8).optional(),
    },
    async (args, c): Promise<CallToolResult> => {
      checkLayer(args, args.savePath && /\.jpe?g$/i.test(args.savePath) ? "A transparent layer needs a .png savePath (JPEG has no transparency)." : undefined);
      const withAbsPath = args.savePath ? { ...args, savePath: resolvePath(args.savePath) } : args;
      const r = (await forward(c, "client", "screenshot", withAbsPath, 20_000)) as {
        mime: string; data: string; width: number; height: number; fullWidth: number; fullHeight: number; savedPath?: string; savedPaths?: string[];
      };
      const meta = { width: r.width, height: r.height, fullWidth: r.fullWidth, fullHeight: r.fullHeight, savedPath: r.savedPath, savedPaths: r.savedPaths };
      return { content: [{ type: "image", data: r.data, mimeType: r.mime }, { type: "text", text: JSON.stringify(meta) }] };
    });

  defineTool(server, ctx, "record",
    "Record a video with sound through the user's ffmpeg. start {savePath, preset max|high|balanced|light, durationMs?, camera? (a camera tool path/orbit motion), wait?}; stop; status. layer gui|tooltip|gui+tooltip: transparent .mov/.webm. Details: craftwire://docs/video.",
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
      camera: z.record(z.string(), z.unknown()).optional(),
      waitForTerrain: z.boolean().optional(),
      wait: z.boolean().optional(),
      layer: guiLayer,
      split: z.boolean().optional(),
      guiScale: z.number().int().min(1).max(8).optional(),
    },
    async (args, c) => {
      if (args.action === "start") {
        checkLayer(args, args.savePath && !/\.(mov|webm)$/i.test(args.savePath) ? "A transparent layer needs .mov (ProRes 4444) or .webm (VP9); MP4 has no transparency." : undefined);
      }
      const camera = args.camera === undefined ? undefined : checkMotion(args.camera);
      const params = { ...args, ...(camera ? { camera } : {}), ...(args.savePath ? { savePath: resolvePath(args.savePath) } : {}) };
      // A waiting start lasts as long as the video plus the encoding; the rest answer quickly.
      const length = args.durationMs ?? camera?.durationMs ?? motionLength(camera?.keyframes) ?? (args.maxSeconds ?? 120) * 1000;
      const timeoutMs = args.action === "start" && args.wait ? length + 120_000 : args.action === "stop" ? 150_000 : 30_000;
      return ok(await forward(c, "client", "record", params, timeoutMs));
    });

  defineTool(server, ctx, "chat",
    "send {text}; command {text}; read recent chat and actionbar lines (contains?, since?).",
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
