import WebSocket from "ws";

type Handler = (params: Record<string, unknown>) => unknown | Promise<unknown>;

export interface FakeAgent {
  instanceId: string;
  socket: WebSocket;
  onRequest(method: string, fn: Handler): void;
  emit(type: string, data: Record<string, unknown>): void;
  close(): Promise<void>;
}

export async function connectFakeAgent(
  port: number,
  opts: { token: string; kind?: "client" | "server"; name?: string; protocolVersion?: number; agentVersion?: string; serverDir?: string; pid?: number },
): Promise<FakeAgent> {
  const socket = new WebSocket(`ws://127.0.0.1:${port}/`);
  const handlers = new Map<string, Handler>();
  await new Promise<void>((res, rej) => { socket.once("open", () => res()); socket.once("error", rej); });

  const instanceId = await new Promise<string>((resolve, reject) => {
    socket.once("message", (raw) => {
      const msg = JSON.parse(String(raw));
      if (msg.result?.instanceId) resolve(msg.result.instanceId);
      else reject(new Error(JSON.stringify(msg.error)));
    });
    socket.once("close", (code, reason) => reject(new Error(`closed ${code} ${reason}`)));
    socket.send(JSON.stringify({
      jsonrpc: "2.0", id: 0, method: "hello",
      params: {
        token: opts.token, agentKind: opts.kind ?? "client", agentVersion: opts.agentVersion ?? "0.1.0",
        protocolVersion: opts.protocolVersion ?? 1, mcVersion: "26.2", instanceName: opts.name ?? "Tester",
        ...(opts.serverDir !== undefined ? { serverDir: opts.serverDir } : {}),
        ...(opts.pid !== undefined ? { pid: opts.pid } : {}),
      },
    }));
  });

  socket.on("message", async (raw) => {
    const msg = JSON.parse(String(raw));
    if (msg.method === undefined || msg.id === undefined) return;
    const fn = handlers.get(msg.method);
    try {
      if (!fn) throw Object.assign(new Error(`no handler ${msg.method}`), { code: "UNKNOWN_METHOD" });
      const result = await fn(msg.params ?? {});
      socket.send(JSON.stringify({ jsonrpc: "2.0", id: msg.id, result }));
    } catch (e) {
      const err = e as Error & { code?: string; hint?: string };
      socket.send(JSON.stringify({
        jsonrpc: "2.0", id: msg.id,
        error: { code: -32000, message: err.message, data: { code: err.code ?? "INTERNAL", hint: err.hint } },
      }));
    }
  });

  return {
    instanceId,
    socket,
    onRequest: (method, fn) => handlers.set(method, fn),
    emit: (type, data) => socket.send(JSON.stringify({ jsonrpc: "2.0", method: "event", params: { type, time: Date.now(), data } })),
    close: () => new Promise((res) => { socket.once("close", () => res()); socket.close(); }),
  };
}
