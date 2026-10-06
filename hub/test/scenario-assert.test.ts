import { describe, expect, it } from "vitest";
import { checkAssertion } from "../src/scenario/assert.js";

const result = { success: true, messages: [{ text: "You bought a Diamond" }, { text: "Coins: 70" }], coins: 70, tags: ["vip", "beta"] };

describe("checkAssertion", () => {
  it("equals compares structure, not key order", () => {
    expect(checkAssertion({ path: "success", equals: true }, result).pass).toBe(true);
    expect(checkAssertion({ equals: { coins: 70, success: true, tags: ["vip", "beta"], messages: result.messages } }, result).pass).toBe(true);
    expect(checkAssertion({ path: "coins", equals: 71 }, result)).toMatchObject({ pass: false, actual: 70 });
  });

  it("matches is a case-insensitive regex on the text (JSON for non-strings)", () => {
    expect(checkAssertion({ path: "messages[*].text", matches: "bought a diamond" }, result).pass).toBe(true);
    expect(checkAssertion({ path: "coins", matches: "^70$" }, result).pass).toBe(true);
    expect(checkAssertion({ path: "messages[*].text", matches: "sold" }, result)).toMatchObject({ pass: false, actual: ["You bought a Diamond", "Coins: 70"] });
  });

  it("contains: substrings, array elements and partial objects", () => {
    expect(checkAssertion({ path: "messages[0].text", contains: "Diamond" }, result).pass).toBe(true);
    expect(checkAssertion({ path: "tags", contains: "beta" }, result).pass).toBe(true);
    expect(checkAssertion({ path: "messages", contains: { text: "Coins: 70" } }, result).pass).toBe(true);
    expect(checkAssertion({ contains: { coins: 70 } }, result).pass).toBe(true);
    expect(checkAssertion({ path: "tags", contains: "alpha" }, result).pass).toBe(false);
  });

  it("exists, numeric comparisons and length", () => {
    expect(checkAssertion({ path: "coins", exists: true }, result).pass).toBe(true);
    expect(checkAssertion({ path: "debt", exists: false }, result).pass).toBe(true);
    expect(checkAssertion({ path: "debt", exists: true }, result)).toMatchObject({ pass: false, actual: "(missing)" });
    expect(checkAssertion({ path: "coins", greaterThan: 50, lessThan: 100 }, result).pass).toBe(true);
    expect(checkAssertion({ path: "coins", lessThan: 70 }, result).pass).toBe(false);
    expect(checkAssertion({ path: "messages", length: 2 }, result).pass).toBe(true);
  });

  it("[*] passes when any element passes, or every element with every:true", () => {
    expect(checkAssertion({ path: "tags[*]", matches: "vip" }, result).pass).toBe(true);
    expect(checkAssertion({ path: "tags[*]", matches: "vip", every: true }, result).pass).toBe(false);
  });

  it("a missing path fails every check except exists:false", () => {
    expect(checkAssertion({ path: "nope", equals: null }, result).pass).toBe(false);
  });

  it("an assertion needs at least one check", () => {
    expect(() => checkAssertion({ path: "coins" }, result)).toThrow(/equals, matches/);
  });
});
