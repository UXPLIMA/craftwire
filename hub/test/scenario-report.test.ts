import { describe, expect, it } from "vitest";
import { formatReport, formatSummary, toJUnit } from "../src/scenario/report.js";
import type { ScenarioReport } from "../src/scenario/runner.js";

const passed: ScenarioReport = { name: "shop opens", passed: true, durationMs: 1200, steps: [], newExceptions: 0 };
const failed: ScenarioReport = {
  name: "buy <diamond>", passed: false, durationMs: 3450, steps: [], newExceptions: 1,
  failure: {
    phase: "steps", index: 2, step: "Buyer: /shop buy", message: "expectation failed at \"messages[*].text\"",
    expected: { matches: "bought" }, actual: ["Not enough coins"],
    context: { Buyer: { messages: ["Not enough coins"] }, warnings: [] },
  },
};

describe("scenario reports", () => {
  it("prints a passing scenario on one line and a failure with what was expected and found", () => {
    expect(formatReport(passed)).toBe("✓ shop opens (1.2 s)");
    expect(formatReport(failed)).toBe([
      "✗ buy <diamond> (3.5 s)",
      '    step 3 "Buyer: /shop buy": expectation failed at "messages[*].text"',
      '      expected: {"matches":"bought"}',
      '      actual:   ["Not enough coins"]',
      '      Buyer: {"messages":["Not enough coins"]}',
      "    1 new exception group(s) were logged; see the exceptions tool",
    ].join("\n"));
    expect(formatSummary([passed, failed])).toBe("2 scenario(s): 1 passed, 1 failed");
  });

  it("writes JUnit XML with escaped names and the failure text", () => {
    const x = toJUnit([passed, failed]);
    expect(x).toContain('<testsuite name="craftwire" tests="2" failures="1" time="4.650">');
    expect(x).toContain('<testcase classname="craftwire" name="shop opens" time="1.200"/>');
    expect(x).toContain('name="buy &lt;diamond&gt;"');
    expect(x).toContain('<failure message="Buyer: /shop buy: expectation failed at &quot;messages[*].text&quot;">');
  });
});
