import { connect } from "node:net";
import { join } from "node:path";
import { AgentServer } from "./agents.js";
import { AuditLog } from "./audit.js";
import { ClientManager } from "./client/client-manager.js";
import { prepareClient } from "./client/launcher.js";
import { hubPort, loadOrCreateToken, readHubConfig, writeHubConfig, type HubConfig } from "./config.js";
import { ServerManager } from "./dev/server-manager.js";
import { trackExceptions } from "./exceptions.js";
import { OperationTracker } from "./operations.js";
import type { ToolContext } from "./tools/registry.js";

export interface Hub {
  ctx: ToolContext;
  /** The port agents connect to. */
  port: number;
  /** Stops clients first (they may be on these servers), then servers (gracefully, so worlds are saved), then the agent port. */
  close(): Promise<void>;
}

/**
 * The hub's state: the agent port (written to hub.json so agents find it), the managers for the servers and
 * clients it runs, and everything tools share. One per process, whichever way the MCP side is served.
 */
export async function startHub(home: string, log: (msg: string) => void): Promise<Hub> {
  const token = loadOrCreateToken(home);
  const agents = new AgentServer({ token, port: hubPort() });
  const port = await agents.listen();
  const previous = readHubConfig(home);
  writeHubConfig({ port, token }, home);
  agents.on("connected", (i) => log(`${i.id} connected (${i.name}, Minecraft ${i.mcVersion}, agent ${i.agentVersion})`));
  agents.on("disconnected", (i) => log(`${i.id} disconnected`));
  const servers = new ServerManager({ agents, home });
  const clients = new ClientManager({ agents, home, servers, prepare: (p) => prepareClient(p) });
  const ctx: ToolContext = {
    agents, servers, clients, ops: new OperationTracker(), audit: new AuditLog(join(home, "logs")), exceptions: trackExceptions(agents),
  };
  let closing: Promise<void> | undefined;
  return {
    ctx,
    port,
    close: () => (closing ??= clients.shutdown().finally(() => servers.shutdown()).finally(() => agents.close())
      .finally(() => handBack(home, port, previous))),
  };
}

/**
 * Agents follow hub.json, so the newest hub gets them. When it closes, point them back at the hub that was there
 * before (another AI session, or the hub `craftwire test` borrowed them from), if that one is still listening.
 */
async function handBack(home: string, port: number, previous: HubConfig | undefined): Promise<void> {
  if (!previous || previous.port === port || readHubConfig(home)?.port !== port) return;
  if (await listening(previous.port)) writeHubConfig(previous, home);
}

/** The port of another hub that is running for this home (hub.json points at it and it accepts connections). */
export async function runningHub(home: string): Promise<number | undefined> {
  const cfg = readHubConfig(home);
  return cfg && (await listening(cfg.port)) ? cfg.port : undefined;
}

function listening(port: number): Promise<boolean> {
  return new Promise((resolve) => {
    const socket = connect({ host: "127.0.0.1", port });
    const done = (up: boolean) => { socket.destroy(); resolve(up); };
    socket.setTimeout(500, () => done(false));
    socket.once("connect", () => done(true));
    socket.once("error", () => done(false));
  });
}
