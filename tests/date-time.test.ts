import { describe, expect, it } from "vitest";
import { formatTaskTimestamp } from "@/lib/date-time";

describe("formatTaskTimestamp", () => {
  it("renders backend timestamps as a compact, deterministic JST label", () => {
    expect(formatTaskTimestamp("2026-08-20T13:01:32.554760Z")).toBe("20 Aug, 22:01 JST");
  });

  it("preserves relative timestamps supplied by the local fallback", () => {
    expect(formatTaskTimestamp("2m ago")).toBe("2m ago");
  });
});
