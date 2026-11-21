import { describe, expect, it } from "vitest";
import { fromLocalInput, presets, toLocalInput, whenLabel } from "./schedule";

describe("presets", () => {
  it("offers an hour from now, tonight at 8 and tomorrow at 8 in the afternoon", () => {
    const now = new Date(2026, 10, 20, 15, 10);
    const p = presets(now);
    expect(p.map((x) => x.label)).toEqual(["In 1 hour", "Tonight, 8:00 PM", "Tomorrow, 8:00 AM"]);
    expect(new Date(p[1]?.at ?? 0).getHours()).toBe(20);
    expect(new Date(p[2]?.at ?? 0).getDate()).toBe(21);
  });

  it("drops tonight once it is too close or past", () => {
    expect(presets(new Date(2026, 10, 20, 19, 45)).map((x) => x.label)).toEqual(["In 1 hour", "Tomorrow, 8:00 AM"]);
    expect(presets(new Date(2026, 10, 20, 23, 0)).map((x) => x.label)).toEqual(["In 1 hour", "Tomorrow, 8:00 AM"]);
  });
});

describe("local input", () => {
  it("round-trips a time to the minute", () => {
    const at = new Date(2026, 10, 21, 8, 5).getTime();
    expect(toLocalInput(at)).toBe("2026-11-21T08:05");
    expect(fromLocalInput("2026-11-21T08:05", at - 1)).toBe(at);
  });

  it("refuses the past and junk", () => {
    const now = new Date(2026, 10, 21, 8, 5).getTime();
    expect(fromLocalInput("2026-11-21T08:05", now)).toBeNull();
    expect(fromLocalInput("", now)).toBeNull();
    expect(fromLocalInput("tomorrow", now)).toBeNull();
  });
});

describe("whenLabel", () => {
  it("says today or tomorrow when it can", () => {
    const now = new Date(2026, 10, 20, 15, 0).getTime();
    expect(whenLabel(new Date(2026, 10, 20, 20, 0).getTime(), now, "en-US")).toBe("today 8:00 PM");
    expect(whenLabel(new Date(2026, 10, 21, 8, 0).getTime(), now, "en-US")).toBe("tomorrow 8:00 AM");
    expect(whenLabel(new Date(2026, 10, 25, 8, 0).getTime(), now, "en-US")).toBe("Wed, Nov 25 8:00 AM");
  });
});
