import { EventEmitter } from "node:events";
import type { AddressInfo } from "node:net";
import { WebSocketServer, type WebSocket } from "ws";
import { tokensEqual } from "./config.js";
import { CraftwireError } from "./errors.js";
import { type AgentEvent, EventNotification, HelloRequest, RpcResponse, StatusRequest } from "./protocol.js";
import { RingBuffer } from "./ringbuffer.js";
import { HUB_VERSION, PROTOCOL_VERSION } from "./version.js";

export type AgentKind = "client" | "server";

export interface InstanceInfo {
  id: string;
  kind: AgentKind;
  name: string;
  agentVersion: string;
  mcVersion: string;
  connectedAt: number;
  /** Server agents: the server's working directory and JVM pid (agents >= 0.3.0). */
  serverDir?: string;
  pid?: number;
  /** Client agents: the game directory, which maps clients started by client_process to their agent (agents >= 0.5.0). */
  gameDir?: string;
}

export interface RejectedAgent {
  time: number;
  code: string;
  agentKind: string;
  agentVersion: string;
  instanceName: string;
  protocolVersion: number;
}

export interface HubStatus {
  hubVersion: string;
  protocolVersion: number;
  instances: InstanceInfo[];
  rejected: RejectedAgent[];
}

interface Pending {
  resolve: (v: unknown) => void;
  reject: (e: Error) => void;
  timer: NodeJS.Timeout;
}

interface Instance {
  info: InstanceInfo;
  socket: WebSocket;
  pending: Map<number, Pending>;
  events: RingBuffer<AgentEvent>;
  nextId: number;
}

export interface AgentServerOptions {
  token: string;
  port: number;
  host?: string;
  requestTimeoutMs?: number;
  handshakeTimeoutMs?: number;
  bufferSize?: number;
}

const CLOSE_UNAUTHORIZED = 4001;
const CLOSE_PROTOCOL = 4002;
const CLOSE_BAD_HANDSHAKE = 4003;

export class AgentServer extends EventEmitter {
  private wss?: WebSocketServer;
  private readonly live = new Map<string, Instance>();
  private readonly counters: Record<AgentKind, number> = { client: 0, server: 0 };
  private readonly rejected = new RingBuffer<RejectedAgent>(20);

  constructor(private readonly opts: AgentServerOptions) {
    super();
    // Every MCP session (several with craftwire serve) listens for resource updates.
    this.setMaxListeners(0);
  }

  async listen(): Promise<number> {
    try {
      return await this.listenOn(this.opts.port);
    } catch (e) {
      if ((e as NodeJS.ErrnoException).code === "EADDRINUSE") return this.listenOn(0);
      throw e;
    }
  }

  private listenOn(port: number): Promise<number> {
    return new Promise((resolve, reject) => {
      const wss = new WebSocketServer({
        host: this.opts.host ?? "127.0.0.1",
        port,
        // Browsers always send Origin; agents never do. Refusing it blocks drive-by localhost attacks.
        verifyClient: (info: { origin?: string }) => !info.origin,
      });
      wss.once("error", reject);
      wss.once("listening", () => {
        wss.off("error", reject);
        wss.on("error", () => {});
        wss.on("connection", (socket) => this.onConnection(socket));
        this.wss = wss;
        resolve((wss.address() as AddressInfo).port);
      });
    });
  }

  private onConnection(socket: WebSocket): void {
    const timer = setTimeout(() => socket.close(CLOSE_BAD_HANDSHAKE, "handshake timeout"), this.opts.handshakeTimeoutMs ?? 5000);
    socket.on("error", () => {});
    socket.once("message", (raw) => {
      clearTimeout(timer);
      let parsed: unknown;
      try {
        parsed = JSON.parse(String(raw));
      } catch {
        socket.close(CLOSE_BAD_HANDSHAKE, "invalid JSON");
        return;
      }
      const status = StatusRequest.safeParse(parsed);
      if (status.success) {
        this.answerStatus(socket, status.data);
        return;
      }
      const hello = HelloRequest.safeParse(parsed);
      if (!hello.success) {
        socket.close(CLOSE_BAD_HANDSHAKE, "first message must be hello");
        return;
      }
      const { id, params } = hello.data;
      const fail = (code: string, message: string, hint: string, closeCode: number) => {
        this.rejected.push({
          time: Date.now(), code, agentKind: params.agentKind, agentVersion: params.agentVersion,
          instanceName: params.instanceName, protocolVersion: params.protocolVersion,
        });
        socket.send(JSON.stringify({ jsonrpc: "2.0", id, error: { code: -32001, message, data: { code, hint } } }));
        socket.close(closeCode, code);
      };
      if (!tokensEqual(params.token, this.opts.token)) {
        fail("UNAUTHORIZED", "Invalid token", "The agent read a stale hub.json; it will retry automatically.", CLOSE_UNAUTHORIZED);
        return;
      }
      if (params.protocolVersion !== PROTOCOL_VERSION) {
        const older = params.protocolVersion < PROTOCOL_VERSION ? "agent" : "hub";
        fail(
          "PROTOCOL_MISMATCH",
          `Agent speaks protocol ${params.protocolVersion}, hub speaks ${PROTOCOL_VERSION}`,
          older === "agent" ? "Update the Craftwire agent (mod/plugin)." : "Update the craftwire hub (npm).",
          CLOSE_PROTOCOL,
        );
        return;
      }
      this.counters[params.agentKind] += 1;
      const info: InstanceInfo = {
        id: `${params.agentKind}-${this.counters[params.agentKind]}`,
        kind: params.agentKind,
        name: params.instanceName,
        agentVersion: params.agentVersion,
        mcVersion: params.mcVersion,
        connectedAt: Date.now(),
        ...(params.serverDir !== undefined ? { serverDir: params.serverDir } : {}),
        ...(params.pid !== undefined ? { pid: params.pid } : {}),
        ...(params.gameDir !== undefined ? { gameDir: params.gameDir } : {}),
      };
      const inst: Instance = { info, socket, pending: new Map(), events: new RingBuffer(this.opts.bufferSize ?? 5000), nextId: 1 };
      this.live.set(info.id, inst);
      socket.on("message", (data) => this.onMessage(inst, String(data)));
      socket.on("close", () => this.onClose(inst));
      socket.send(JSON.stringify({ jsonrpc: "2.0", id, result: { instanceId: info.id } }));
      this.emit("connected", info);
    });
  }

  private answerStatus(socket: WebSocket, req: StatusRequest): void {
    if (!tokensEqual(req.params.token, this.opts.token)) {
      socket.send(JSON.stringify({
        jsonrpc: "2.0", id: req.id,
        error: { code: -32001, message: "Invalid token", data: { code: "UNAUTHORIZED", hint: "hub.json changed; run the command again." } },
      }));
      socket.close(CLOSE_UNAUTHORIZED, "UNAUTHORIZED");
      return;
    }
    const result: HubStatus = { hubVersion: HUB_VERSION, protocolVersion: PROTOCOL_VERSION, instances: this.instances(), rejected: this.rejectedAgents() };
    socket.send(JSON.stringify({ jsonrpc: "2.0", id: req.id, result }), () => socket.close(1000, "status"));
  }

  rejectedAgents(): RejectedAgent[] {
    return this.rejected.toArray();
  }

  private onMessage(inst: Instance, text: string): void {
    let msg: unknown;
    try {
      msg = JSON.parse(text);
    } catch {
      return;
    }
    const ev = EventNotification.safeParse(msg);
    if (ev.success) {
      inst.events.push(ev.data.params);
      this.emit("event", inst.info.id, ev.data.params);
      return;
    }
    const res = RpcResponse.safeParse(msg);
    if (!res.success || typeof res.data.id !== "number") return;
    const p = inst.pending.get(res.data.id);
    if (!p) return;
    inst.pending.delete(res.data.id);
    clearTimeout(p.timer);
    if ("error" in res.data) {
      const { message, data } = res.data.error;
      p.reject(new CraftwireError(data?.code ?? "INTERNAL", message, data?.hint));
    } else {
      p.resolve(res.data.result);
    }
  }

  private onClose(inst: Instance): void {
    this.live.delete(inst.info.id);
    for (const p of inst.pending.values()) {
      clearTimeout(p.timer);
      p.reject(new CraftwireError("AGENT_DISCONNECTED", `${inst.info.id} disconnected`, "Retry with the same operationId once the agent reconnects (list_instances)."));
    }
    inst.pending.clear();
    this.emit("disconnected", inst.info);
  }

  instances(): InstanceInfo[] {
    return [...this.live.values()].map((i) => i.info);
  }

  resolve(kind: AgentKind, selector?: string): InstanceInfo {
    const ofKind = this.instances().filter((i) => i.kind === kind);
    const label = kind === "client" ? "Minecraft client (Craftwire Agent mod)" : "Paper server (Craftwire plugin)";
    if (selector) {
      const s = selector.toLowerCase();
      const hit = ofKind.find((i) => i.id === selector) ?? ofKind.find((i) => i.name.toLowerCase() === s);
      if (!hit) throw new CraftwireError("NO_INSTANCE", `No ${kind} instance matches "${selector}"`, "Call list_instances to see connected instances.");
      return hit;
    }
    if (ofKind.length === 0) {
      throw new CraftwireError("NO_INSTANCE", `No ${label} is connected`, kind === "client"
        ? "Start Minecraft with the Craftwire Agent mod installed; it connects automatically."
        : "Start the Paper server with the Craftwire plugin installed; it connects automatically.");
    }
    if (ofKind.length > 1) throw new CraftwireError("AMBIGUOUS_INSTANCE", `${ofKind.length} ${kind} instances are connected`, `Pass instance: one of ${ofKind.map((i) => `${i.id} (${i.name})`).join(", ")}.`);
    return ofKind[0]!;
  }

  /** Any instance: by selector, else the only server, else the only instance. */
  resolveAny(selector?: string): InstanceInfo {
    const all = this.instances();
    if (selector) {
      const s = selector.toLowerCase();
      const hit = all.find((i) => i.id === selector) ?? all.find((i) => i.name.toLowerCase() === s);
      if (!hit) throw new CraftwireError("NO_INSTANCE", `No instance matches "${selector}"`, "Call list_instances to see connected instances.");
      return hit;
    }
    const servers = all.filter((i) => i.kind === "server");
    if (servers.length === 1) return servers[0]!;
    if (all.length === 1) return all[0]!;
    if (all.length === 0) throw new CraftwireError("NO_INSTANCE", "Nothing is connected", "Start a Paper server with the Craftwire plugin, or Minecraft with the Craftwire Agent mod.");
    throw new CraftwireError("AMBIGUOUS_INSTANCE", `${all.length} instances are connected`, `Pass instance: one of ${all.map((i) => `${i.id} (${i.name})`).join(", ")}.`);
  }

  request(instanceId: string, method: string, params: Record<string, unknown>, timeoutMs?: number): Promise<unknown> {
    const inst = this.live.get(instanceId);
    if (!inst) return Promise.reject(new CraftwireError("AGENT_DISCONNECTED", `${instanceId} is not connected`, "Call list_instances."));
    const id = inst.nextId++;
    const ms = timeoutMs ?? this.opts.requestTimeoutMs ?? 30000;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        inst.pending.delete(id);
        reject(new CraftwireError("TIMEOUT", `${method} did not answer within ${ms} ms`, "The game may be frozen or loading; check with list_instances and retry."));
      }, ms);
      inst.pending.set(id, { resolve, reject, timer });
      inst.socket.send(JSON.stringify({ jsonrpc: "2.0", id, method, params }));
    });
  }

  events(instanceId: string): AgentEvent[] {
    return this.live.get(instanceId)?.events.toArray() ?? [];
  }

  async close(): Promise<void> {
    for (const inst of this.live.values()) inst.socket.terminate();
    await new Promise<void>((res) => (this.wss ? this.wss.close(() => res()) : res()));
  }
}
