import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { AgentMessage } from "../src/protocol.js";

const dir = join(__dirname, "..", "..", "protocol", "fixtures");
const files = readdirSync(dir).filter((f) => f.endsWith(".json"));

describe("protocol fixtures", () => {
  it("has fixtures", () => expect(files.length).toBeGreaterThanOrEqual(7));

  for (const f of files) {
    it(`${f} is ${f.startsWith("valid-") ? "accepted" : "rejected"}`, () => {
      const msg = JSON.parse(readFileSync(join(dir, f), "utf8"));
      const res = AgentMessage.safeParse(msg);
      expect(res.success).toBe(f.startsWith("valid-"));
    });
  }
});
