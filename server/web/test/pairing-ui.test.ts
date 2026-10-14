import { describe, expect, it } from "vitest";
import { displayCode, fmtLeft, normalizeCode } from "../src/ui/pairing";

describe("pairing screen helpers", () => {
  it("shows the code as XXXX-XXXX", () => {
    expect(displayCode("ABCD2345")).toBe("ABCD-2345");
    expect(normalizeCode("abcd-23o5")).toBe("ABCD2305");
  });

  it("shows the remaining time as m:ss, rounding up", () => {
    expect(fmtLeft(600_000)).toBe("10:00");
    expect(fmtLeft(598_001)).toBe("9:59");
    expect(fmtLeft(30_000)).toBe("0:30");
    expect(fmtLeft(-5)).toBe("0:00");
  });
});
