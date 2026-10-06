import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { ResourceTemplate, type McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { SubscribeRequestSchema, UnsubscribeRequestSchema } from "@modelcontextprotocol/sdk/types.js";
import type { InstanceInfo } from "./agents.js";
import { CraftwireError } from "./errors.js";
import { groupLogs } from "./tools/log-tools.js";
import type { ToolContext } from "./tools/registry.js";

const LOG_LINES = 200;
const CHAT_LINES = 100;
/** A subscribed resource is announced at most this often (a busy log would flood the client otherwise). */
const NOTIFY_EVERY_MS = 1000;
export const DOC_TOPICS = ["scenarios", "extensions", "http"] as const;

/** docs/ next to the hub in a repo checkout, or in the npm package. */
function docsDir(): string | undefined {
  const here = dirname(fileURLToPath(import.meta.url));
  return [join(here, "..", "..", "docs"), join(here, "..", "docs")].find((d) => existsSync(join(d, "scenarios.md")));
}

function instanceOf(ctx: ToolContext, key: string): InstanceInfo {
  const all = ctx.agents.instances();
  const hit = all.find((i) => i.id === key) ?? all.find((i) => i.name.toLowerCase() === key.toLowerCase());
  if (!hit) throw new CraftwireError("NO_INSTANCE", `No instance matches "${key}"`, "Read craftwire://instances for what is connected.");
  return hit;
}

function time(ms: number): string {
  return new Date(ms).toISOString().slice(11, 19);
}

function logText(ctx: ToolContext, id: string): string {
  return groupLogs(ctx.agents.events(id)).slice(-LOG_LINES).map((l) => {
    const head = `${time(l.time)} ${l.level} [${l.logger}] ${l.message}`;
    const extra = [...(l.thrown ? l.thrown.split("\n") : []), ...(l.stack ?? [])].map((s) => `  ${s.trim()}`);
    return [head, ...extra].join("\n");
  }).join("\n");
}

function chatText(ctx: ToolContext, id: string): string {
  return ctx.agents.events(id)
    .filter((e) => e.type === "chat" || e.type === "hud")
    .slice(-CHAT_LINES)
    .map((e) => `${time(e.time)} ${e.type === "hud" ? `[${String(e.data.element ?? "hud")}] ` : ""}${String(e.data.text ?? "")}`)
    .join("\n");
}

/**
 * The resources: what is connected, each game's log, a client's chat and a fresh screenshot, the grouped exceptions,
 * and the docs. Subscriptions are honoured: a subscribed resource is announced (at most once a second) when it changes.
 */
export function registerResources(server: McpServer, ctx: ToolContext): void {
  server.registerResource("instances", "craftwire://instances",
    { title: "Connected games and servers", description: "The Minecraft clients and Paper servers connected to the hub (as list_instances).", mimeType: "application/json" },
    async (uri) => ({ contents: [{ uri: uri.href, mimeType: "application/json", text: JSON.stringify(ctx.agents.instances(), null, 2) }] }));

  server.registerResource("exceptions", "craftwire://exceptions",
    { title: "Exceptions", description: "Stack traces the games and servers logged, grouped into distinct bugs (as the exceptions tool).", mimeType: "application/json" },
    async (uri) => ({ contents: [{ uri: uri.href, mimeType: "application/json", text: JSON.stringify(ctx.exceptions.list(), null, 2) }] }));

  const each = (kind: "any" | "client", path: string, mimeType: string, what: (i: InstanceInfo) => string) =>
    new ResourceTemplate(`craftwire://instances/{instance}/${path}`, {
      list: async () => ({
        resources: ctx.agents.instances().filter((i) => kind === "any" || i.kind === kind).map((i) => ({
          uri: `craftwire://instances/${i.name}/${path}`, name: `${i.name} ${path}`, description: what(i), mimeType,
        })),
      }),
      complete: { instance: (v) => ctx.agents.instances().map((i) => i.name).filter((n) => n.toLowerCase().startsWith(v.toLowerCase())) },
    });

  server.registerResource("log", each("any", "log", "text/plain", (i) => `The last ${LOG_LINES} log lines of ${i.name}`),
    { title: "Log", description: `A game's or server's last ${LOG_LINES} log lines, stack traces folded under their line.`, mimeType: "text/plain" },
    async (uri, vars) => {
      const inst = instanceOf(ctx, String(vars.instance));
      return { contents: [{ uri: uri.href, mimeType: "text/plain", text: logText(ctx, inst.id) }] };
    });

  server.registerResource("chat", each("client", "chat", "text/plain", (i) => `What ${i.name} saw in chat`),
    { title: "Chat", description: `A client's last ${CHAT_LINES} chat lines and action-bar messages.`, mimeType: "text/plain" },
    async (uri, vars) => {
      const inst = instanceOf(ctx, String(vars.instance));
      return { contents: [{ uri: uri.href, mimeType: "text/plain", text: chatText(ctx, inst.id) }] };
    });

  server.registerResource("screenshot", each("client", "screenshot", "image/png", (i) => `A screenshot of ${i.name}, taken when read`),
    { title: "Screenshot", description: "A client's current frame, captured when the resource is read.", mimeType: "image/png" },
    async (uri, vars) => {
      const inst = instanceOf(ctx, String(vars.instance));
      if (inst.kind !== "client") throw new CraftwireError("NOT_A_CLIENT", `${inst.name} is a server`, "Screenshots come from clients.");
      const r = await ctx.agents.request(inst.id, "screenshot", { maxSize: 1280 }) as { data: string; mime: string };
      return { contents: [{ uri: uri.href, mimeType: r.mime, blob: r.data }] };
    });

  server.registerResource("docs", new ResourceTemplate("craftwire://docs/{topic}", {
    list: async () => ({
      resources: DOC_TOPICS.map((t) => ({ uri: `craftwire://docs/${t}`, name: `Craftwire docs: ${t}`, mimeType: "text/markdown" })),
    }),
    complete: { topic: (v) => DOC_TOPICS.filter((t) => t.startsWith(v)) },
  }), { title: "Craftwire docs", description: "scenarios (the scenario file format), extensions (adding tools from a plugin or mod), http (craftwire serve).", mimeType: "text/markdown" },
  async (uri, vars) => {
    const topic = String(vars.topic);
    const dir = docsDir();
    if (!(DOC_TOPICS as readonly string[]).includes(topic) || !dir) {
      throw new CraftwireError("NOT_FOUND", `No doc ${topic}`, `Topics: ${DOC_TOPICS.join(", ")}.`);
    }
    return { contents: [{ uri: uri.href, mimeType: "text/markdown", text: readFileSync(join(dir, `${topic}.md`), "utf8") }] };
  });

  subscriptions(server, ctx);
}

/** resources/subscribe: announce changes to the resources a client subscribed to. */
function subscriptions(server: McpServer, ctx: ToolContext): void {
  const subscribed = new Set<string>();
  const last = new Map<string, number>();
  const timers = new Map<string, NodeJS.Timeout>();
  server.server.registerCapabilities({ resources: { subscribe: true, listChanged: true } });
  server.server.setRequestHandler(SubscribeRequestSchema, async (req) => {
    subscribed.add(req.params.uri);
    return {};
  });
  server.server.setRequestHandler(UnsubscribeRequestSchema, async (req) => {
    subscribed.delete(req.params.uri);
    return {};
  });

  const announce = (uri: string) => {
    if (!subscribed.has(uri) || timers.has(uri)) return;
    const wait = Math.max(0, (last.get(uri) ?? 0) + NOTIFY_EVERY_MS - Date.now());
    timers.set(uri, setTimeout(() => {
      timers.delete(uri);
      last.set(uri, Date.now());
      if (subscribed.has(uri) && server.isConnected()) server.server.sendResourceUpdated({ uri }).catch(() => {});
    }, wait));
  };
  /** The subscribed URIs of one instance's resource, whichever way they name it (id or name). */
  const announceFor = (inst: InstanceInfo | undefined, path: string) => {
    if (!inst) return;
    for (const uri of subscribed) {
      const m = /^craftwire:\/\/instances\/([^/]+)\/(.+)$/.exec(uri);
      if (m && m[2] === path && (m[1] === inst.id || m[1]!.toLowerCase() === inst.name.toLowerCase())) announce(uri);
    }
  };

  const onChange = () => {
    announce("craftwire://instances");
    if (server.isConnected()) server.sendResourceListChanged();
  };
  const onEvent = (id: string, params: { type?: string; data?: Record<string, unknown> }) => {
    const inst = ctx.agents.instances().find((i) => i.id === id);
    if (params.type === "log") {
      announceFor(inst, "log");
      const level = String(params.data?.level ?? "");
      if (level === "ERROR" || level === "WARN" || params.data?.thrown) announce("craftwire://exceptions");
    } else if (params.type === "chat" || params.type === "hud") {
      announceFor(inst, "chat");
    }
  };
  ctx.agents.on("connected", onChange);
  ctx.agents.on("disconnected", onChange);
  ctx.agents.on("event", onEvent);
  const previous = server.server.onclose;
  server.server.onclose = () => {
    ctx.agents.off("connected", onChange);
    ctx.agents.off("disconnected", onChange);
    ctx.agents.off("event", onEvent);
    for (const t of timers.values()) clearTimeout(t);
    previous?.();
  };
}
