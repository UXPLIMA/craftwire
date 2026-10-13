import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";
import { readBuildFile } from "../build-files.js";
import { CraftwireError } from "../errors.js";
import { defineTool, forward, ok, targetArgs, type ToolContext } from "./registry.js";

const name = z.string().regex(/^[A-Za-z0-9_-]{1,64}$/);
const pos = z.object({ x: z.number().int(), y: z.number().int(), z: z.number().int() });
const rotation = z.union([z.literal(0), z.literal(90), z.literal(180), z.literal(270)]);

export function registerBuildTools(server: McpServer, ctx: ToolContext): void {
  defineTool(server, ctx, "build",
    "Build from a script file of part(name, () => {...}) blocks (fill, hollow, walls, sphere, cylinder, roof, layers, mix, rotate, mirror...): build {project, file}. After editing the file call it again: only changed blocks are written; a re-run part replaces its old blocks. A new project needs origin. preview 'iso4': four corner views in one image. Guide: craftwire://docs/building.",
    {
      ...targetArgs,
      project: name,
      file: z.string().optional().describe("Script path, relative to the hub's working directory."),
      code: z.string().optional().describe("Script text, instead of file."),
      part: name.optional().describe("Run only this part of the file."),
      world: z.string().optional(),
      origin: pos.optional(),
      rotation: rotation.optional(),
      mirror: z.enum(["none", "x", "z"]).optional(),
      seed: z.number().int().optional(),
      dryRun: z.boolean().default(false).describe("Count and preview without writing."),
      preview: z.enum(["none", "iso", "iso4", "top"]).default("none"),
      previewSize: z.number().int().min(256).max(2048).default(1024),
      timeoutMs: z.number().int().min(1000).max(300_000).default(60_000),
    },
    async (args, c): Promise<CallToolResult> => {
      const { file, code, ...rest } = args;
      if ((file === undefined) === (code === undefined)) throw new CraftwireError("BAD_ARGUMENT", "Pass exactly one of file or code", "file is cheaper: edit the file and call build again.");
      const script = file !== undefined ? readBuildFile(file, c.build ?? { http: false }) : { code: code!, file: undefined };
      const params = { ...rest, code: script.code, ...(script.file ? { file: script.file } : {}) };
      const r = (await forward(c, "server", "build.run", params, args.timeoutMs + 180_000)) as Record<string, unknown> & { preview?: { mime: string; data: string } };
      const { preview, ...meta } = r;
      if (preview === undefined) return ok(meta);
      return { content: [{ type: "image", data: preview.data, mimeType: preview.mime }, { type: "text", text: JSON.stringify(meta) }] };
    });

  defineTool(server, ctx, "build_manage",
    "Manage build projects: list; undo {project, steps} (last runs); remove {project, part} (what was under it comes back); clear {project} (world as before the project); move {project, origin?, rotation?, mirror?}; commit {project} (keep the blocks, forget the undo history).",
    {
      ...targetArgs,
      action: z.enum(["list", "undo", "remove", "clear", "move", "commit"]),
      project: name.optional(),
      part: name.optional(),
      steps: z.number().int().min(1).max(100).default(1),
      origin: pos.optional(),
      rotation: rotation.optional(),
      mirror: z.enum(["none", "x", "z"]).optional(),
    },
    async (args, c) => ok(await forward(c, "server", "build.manage", args, 600_000)));
}
