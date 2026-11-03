/** Which started runner a connecting bot runner agent is: the hub reports and stops runners through that link. */
import { describe, expect, it } from "vitest";
import type { InstanceInfo } from "../src/agents.js";
import { ownRunner } from "../src/botrunner/runner-manager.js";

const agent = (id: string, name: string, pid?: number): InstanceInfo => ({
  id, kind: "botrunner", name, agentVersion: "0", mcVersion: "26.2", connectedAt: 0, ...(pid !== undefined ? { pid } : {}),
});

describe("ownRunner", () => {
  it("matches the runner by pid", () => {
    const runners = [{ name: "a", child: { pid: 10 } }, { name: "b", child: { pid: 20 } }];
    expect(ownRunner(runners, agent("botrunner-1", "b", 20))?.name).toBe("b");
  });

  it("matches by name when a launcher stub started the JVM under another pid", () => {
    // Oracle's javapath\java.exe (43960) started the real JVM (33740), the pid the runner reports.
    const runners = [{ name: "runner-1", child: { pid: 43960 } }];
    expect(ownRunner(runners, agent("botrunner-2", "runner-1", 33740))).toBe(runners[0]);
  });

  it("does not hand a runner that is already linked to a second agent with its name", () => {
    const runners = [{ name: "runner-1", child: { pid: 43960 }, agent: "botrunner-2" }];
    expect(ownRunner(runners, agent("botrunner-3", "runner-1", 777))).toBeUndefined();
  });

  it("ignores agents that are not bot runners", () => {
    const runners = [{ name: "paper", child: { pid: 5 } }];
    expect(ownRunner(runners, { ...agent("server-1", "paper", 5), kind: "server" })).toBeUndefined();
  });
});
