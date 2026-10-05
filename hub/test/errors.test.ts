import { describe, expect, it } from "vitest";
import { CraftwireError, toToolError } from "../src/errors.js";

const body = (r: ReturnType<typeof toToolError>) => JSON.parse((r.content[0] as { text: string }).text);

describe("errors", () => {
  it("serialises CraftwireError with hint", () => {
    const r = toToolError(new CraftwireError("NO_INSTANCE", "No client connected", "Start Minecraft with Craftwire Agent."));
    expect(r.isError).toBe(true);
    expect(body(r)).toEqual({ code: "NO_INSTANCE", message: "No client connected", hint: "Start Minecraft with Craftwire Agent." });
  });

  it("maps unknown errors to INTERNAL", () => {
    expect(body(toToolError(new Error("boom")))).toMatchObject({ code: "INTERNAL", message: "boom" });
    expect(body(toToolError("weird"))).toMatchObject({ code: "INTERNAL", message: "weird" });
  });
});
