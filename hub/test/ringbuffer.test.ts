import { describe, expect, it } from "vitest";
import { RingBuffer } from "../src/ringbuffer.js";

describe("RingBuffer", () => {
  it("keeps the newest N items in order", () => {
    const rb = new RingBuffer<number>(3);
    [1, 2, 3, 4, 5].forEach((n) => rb.push(n));
    expect(rb.toArray()).toEqual([3, 4, 5]);
    expect(rb.size).toBe(3);
  });

  it("works below capacity", () => {
    const rb = new RingBuffer<string>(5);
    rb.push("a");
    expect(rb.toArray()).toEqual(["a"]);
  });
});
