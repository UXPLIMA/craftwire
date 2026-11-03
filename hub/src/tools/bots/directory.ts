/** Which agent runs which bot, learned from spawn results, so a bot_action by name goes to the right agent. */
import type { AgentServer, InstanceInfo } from "../../agents.js";

export type BotKind = "plugin" | "java" | "bedrock";

export interface BotHome {
  instanceId: string;
  kind: BotKind;
}

const RANGE = /^(.*?)(\d+)\.\.(.*?)(\d+)$/;

/** "Load1..Load3, Tester" (the agents' compact form) back to Load1, Load2, Load3, Tester. */
export function expandNames(compact: string): string[] {
  const out: string[] = [];
  for (const part of compact.split(",").map((s) => s.trim()).filter((s) => s.length > 0)) {
    const m = RANGE.exec(part);
    if (m === null || m[1] !== m[3]) {
      out.push(part);
      continue;
    }
    const from = Number(m[2]);
    const to = Number(m[4]);
    for (let n = from; n <= to && out.length < 100_000; n++) out.push(`${m[1]}${n}`);
  }
  return out;
}

export class BotDirectory {
  private readonly homes = new Map<string, BotHome>();

  constructor(agents: AgentServer) {
    // A disconnected agent took its bots with it.
    agents.on("disconnected", (info: InstanceInfo) => {
      for (const [name, home] of this.homes) if (home.instanceId === info.id) this.homes.delete(name);
    });
  }

  recordSpawn(instanceId: string, kind: BotKind, compactNames: string): void {
    for (const n of expandNames(compactNames)) this.homes.set(n.toLowerCase(), { instanceId, kind });
  }

  forget(names: string[]): void {
    for (const n of names) this.homes.delete(n.toLowerCase());
  }

  forgetInstance(instanceId: string): void {
    for (const [name, home] of this.homes) if (home.instanceId === instanceId) this.homes.delete(name);
  }

  locate(name: string): BotHome | undefined {
    return this.homes.get(name.toLowerCase());
  }

  size(): number {
    return this.homes.size;
  }
}

const directories = new WeakMap<AgentServer, BotDirectory>();

/** One directory per hub (per AgentServer), shared by every MCP session. */
export function directoryFor(agents: AgentServer): BotDirectory {
  let d = directories.get(agents);
  if (d === undefined) {
    d = new BotDirectory(agents);
    directories.set(agents, d);
  }
  return d;
}
