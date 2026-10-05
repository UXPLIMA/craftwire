import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import WebSocket from "ws";
import type { HubStatus } from "./agents.js";
import { pluginInfoOfJar, pluginJarsNamed } from "./dev/jar.js";
import { eulaAccepted, MIN_JAVA, probeJavaMajor, resolveLaunch } from "./dev/launch.js";
import { HUB_VERSION } from "./version.js";

export interface Check {
  status: "ok" | "warn" | "fail";
  label: string;
  fix?: string;
}

/** Asks a running hub for its status over the agent WebSocket (token from hub.json). */
export function hubStatus(port: number, token: string, timeoutMs = 3000): Promise<HubStatus> {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(`ws://127.0.0.1:${port}/`);
    const timer = setTimeout(() => { socket.terminate(); reject(new Error("no answer")); }, timeoutMs);
    socket.once("error", (e) => { clearTimeout(timer); reject(e); });
    socket.once("open", () => socket.send(JSON.stringify({ jsonrpc: "2.0", id: 0, method: "status", params: { token } })));
    socket.once("message", (raw) => {
      clearTimeout(timer);
      const msg = JSON.parse(String(raw)) as { result?: HubStatus; error?: { message?: string } };
      socket.close();
      if (msg.result) resolve(msg.result);
      else reject(new Error(msg.error?.message ?? "unexpected answer"));
    });
  });
}

export interface DoctorOptions {
  home: string;
  serverDir?: string;
  nodeVersion?: string;
  javaMajor?: (java: string) => Promise<number>;
}

export async function runDoctor(o: DoctorOptions): Promise<Check[]> {
  const checks: Check[] = [];
  const node = o.nodeVersion ?? process.versions.node;
  checks.push(Number(node.split(".")[0]) >= 20
    ? { status: "ok", label: `Node ${node}` }
    : { status: "fail", label: `Node ${node} is too old`, fix: "Install Node 20 or newer." });

  const file = join(o.home, "hub.json");
  let cfg: { port?: unknown; token?: unknown } | undefined;
  try {
    cfg = JSON.parse(readFileSync(file, "utf8")) as { port?: unknown; token?: unknown };
  } catch {
    cfg = undefined;
  }
  if (!cfg || typeof cfg.port !== "number" || typeof cfg.token !== "string") {
    checks.push({ status: "warn", label: `${file} is missing or unreadable`, fix: "The hub writes it when it starts: open Claude Code with the craftwire plugin enabled (check /mcp)." });
  } else {
    checks.push({ status: "ok", label: `hub.json: port ${cfg.port}` });
    try {
      checks.push(...hubChecks(await hubStatus(cfg.port, cfg.token)));
    } catch {
      checks.push({ status: "warn", label: `no hub answers on 127.0.0.1:${cfg.port}`, fix: "The hub runs inside Claude Code: open Claude Code with the craftwire plugin enabled (check /mcp)." });
    }
  }

  try {
    const major = await (o.javaMajor ?? probeJavaMajor)("java");
    checks.push(major >= MIN_JAVA
      ? { status: "ok", label: `java on PATH is Java ${major}` }
      : { status: "warn", label: `java on PATH is Java ${major}`, fix: `Paper 26.x needs Java ${MIN_JAVA}+: install it, or pass java to server_process.` });
  } catch {
    checks.push({ status: "warn", label: "java is not on PATH", fix: `Install Java ${MIN_JAVA}+ to run Paper 26.x servers.` });
  }
  if (o.serverDir) checks.push(...serverChecks(o.serverDir));
  return checks;
}

function hubChecks(s: HubStatus): Check[] {
  const out: Check[] = [s.hubVersion === HUB_VERSION
    ? { status: "ok", label: `hub ${s.hubVersion} is running` }
    : { status: "warn", label: `hub ${s.hubVersion} is running, this command is ${HUB_VERSION}`, fix: "Restart Claude Code so both use the same version." }];
  if (s.instances.length === 0) out.push({ status: "warn", label: "no game or server is connected", fix: "Start Minecraft with the Craftwire Agent mod, or a Paper server with the Craftwire plugin." });
  for (const i of s.instances) {
    const what = `${i.id} (${i.name}, ${i.kind === "client" ? "mod" : "plugin"} ${i.agentVersion}, Minecraft ${i.mcVersion})`;
    out.push(i.agentVersion === s.hubVersion
      ? { status: "ok", label: what }
      : { status: "warn", label: `${what} differs from hub ${s.hubVersion}`, fix: `Update the Craftwire ${i.kind === "client" ? "Agent mod" : "plugin"} to ${s.hubVersion}.` });
  }
  for (const r of s.rejected) {
    out.push({
      status: "fail",
      label: `${r.agentKind} agent ${r.agentVersion} (${r.instanceName}) was refused: ${r.code}`,
      fix: r.code === "PROTOCOL_MISMATCH" ? "Use the same Craftwire version for the hub, the mod and the plugin." : "Restart the game or server so it re-reads hub.json.",
    });
  }
  return out;
}

function serverChecks(dir: string): Check[] {
  if (!existsSync(dir)) return [{ status: "fail", label: `${dir} does not exist`, fix: "Pass --server <folder with the Paper jar>." }];
  const out: Check[] = [];
  try {
    const l = resolveLaunch(dir);
    out.push({ status: "ok", label: `server jar ${l.jar} (JVM flags from ${l.source})` });
  } catch (e) {
    out.push({ status: "fail", label: (e as Error).message, fix: "Put the Paper jar in the folder as server.jar." });
  }
  out.push(eulaAccepted(dir)
    ? { status: "ok", label: "EULA accepted" }
    : { status: "warn", label: "EULA not accepted (eula.txt)", fix: "Read https://aka.ms/MinecraftEULA and set eula=true yourself; Craftwire never does it for you." });
  const jars = pluginJarsNamed(join(dir, "plugins"), "Craftwire");
  if (jars.length === 0) {
    out.push({ status: "warn", label: "Craftwire plugin is not in plugins/", fix: `Copy craftwire-paper-${HUB_VERSION}.jar into plugins/ (or use plugin_deploy {jar}).` });
  } else {
    const v = pluginInfoOfJar(jars[0]!)?.version;
    out.push(v === HUB_VERSION
      ? { status: "ok", label: `Craftwire plugin ${v}` }
      : { status: "warn", label: `Craftwire plugin ${v ?? "?"} differs from hub ${HUB_VERSION}`, fix: `Install craftwire-paper-${HUB_VERSION}.jar.` });
    if (jars.length > 1) out.push({ status: "warn", label: `${jars.length} Craftwire jars in plugins/`, fix: "Keep only one." });
  }
  return out;
}

export function formatChecks(checks: Check[]): string {
  return checks.map((c) => `[${c.status}] ${c.label}${c.fix ? `\n       -> ${c.fix}` : ""}`).join("\n") + "\n";
}
