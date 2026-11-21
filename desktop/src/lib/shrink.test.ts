import { describe, expect, it } from "vitest";
import { budgetPerAttachment, shrinkPicture, type Encode } from "./shrink";

/** A fake JPEG encoder whose output grows with pixels and quality, and records each try. */
function fakeEncoder(bytesPerPixelAtFull: number) {
  const tries: [number, number, number][] = [];
  const encode: Encode = async (w, h, q) => {
    tries.push([w, h, q]);
    return new Blob([new Uint8Array(Math.round(w * h * bytesPerPixelAtFull * q))]);
  };
  return { encode, tries };
}

describe("budgetPerAttachment", () => {
  it("leaves room for the text and the message's own overhead, shared between files", () => {
    expect(budgetPerAttachment(1_000_000, 0, 1)).toBe(895_904);
    expect(budgetPerAttachment(1_000_000, 1000, 2)).toBe(447_452);
    expect(budgetPerAttachment(1000, 0, 1)).toBe(0);
  });
});

describe("shrinkPicture", () => {
  it("starts at a long edge of 1600 and keeps the shape", async () => {
    const { encode, tries } = fakeEncoder(0.5);
    const blob = await shrinkPicture(4000, 3000, 10_000_000, encode);
    expect(blob).not.toBeNull();
    expect(tries[0]).toEqual([1600, 1200, 0.85]);
  });

  it("never enlarges a small picture", async () => {
    const { encode, tries } = fakeEncoder(0.5);
    await shrinkPicture(640, 480, 10_000_000, encode);
    expect(tries[0]).toEqual([640, 480, 0.85]);
  });

  it("lowers the quality before the size, then the size, until it fits", async () => {
    const { encode, tries } = fakeEncoder(1);
    const budget = 600_000; // 1600x1200 at 0.4 is 768000: too big; 1200x900 at 0.55 is 594000.
    const blob = await shrinkPicture(4000, 3000, budget, encode);
    expect(blob?.size).toBeLessThanOrEqual(budget);
    expect(tries.slice(0, 4).map((t) => t[2])).toEqual([0.85, 0.7, 0.55, 0.4]);
    expect(tries[4]).toEqual([1200, 900, 0.85]);
    expect(tries.at(-1)).toEqual([1200, 900, 0.55]);
  });

  it("gives up below a long edge of 320", async () => {
    const { encode, tries } = fakeEncoder(1);
    expect(await shrinkPicture(4000, 3000, 100, encode)).toBeNull();
    expect(Math.min(...tries.map((t) => Math.max(t[0], t[1])))).toBe(320);
  });
});
