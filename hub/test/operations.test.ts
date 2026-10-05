import { describe, expect, it } from "vitest";
import { CraftwireError } from "../src/errors.js";
import { OperationTracker } from "../src/operations.js";

describe("OperationTracker", () => {
  it("runs without an id every time", async () => {
    const ops = new OperationTracker();
    let n = 0;
    await ops.run(undefined, async () => ++n);
    await ops.run(undefined, async () => ++n);
    expect(n).toBe(2);
  });

  it("returns the cached result for a repeated id", async () => {
    const ops = new OperationTracker();
    let n = 0;
    expect(await ops.run("op1", async () => ++n)).toBe(1);
    expect(await ops.run("op1", async () => ++n)).toBe(1);
    expect(ops.status("op1")).toMatchObject({ status: "done", result: 1 });
  });

  it("shares an in-flight promise", async () => {
    const ops = new OperationTracker();
    let n = 0;
    const slow = () => new Promise<number>((r) => setTimeout(() => r(++n), 20));
    const [a, b] = await Promise.all([ops.run("op", slow), ops.run("op", slow)]);
    expect([a, b, n]).toEqual([1, 1, 1]);
  });

  it("re-runs after a failure and records the error", async () => {
    const ops = new OperationTracker();
    await expect(ops.run("op", async () => { throw new CraftwireError("AGENT_DISCONNECTED", "gone"); })).rejects.toThrow("gone");
    expect(ops.status("op")).toMatchObject({ status: "failed", error: { code: "AGENT_DISCONNECTED" } });
    expect(await ops.run("op", async () => 42)).toBe(42);
  });

  it("forgets entries after the TTL", async () => {
    let t = 0;
    const ops = new OperationTracker(1000, () => t);
    await ops.run("op", async () => 1);
    t = 1001;
    expect(ops.status("op")).toEqual({ status: "unknown" });
  });
});
