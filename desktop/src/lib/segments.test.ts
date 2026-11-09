import { describe, expect, it } from "vitest";
import { countSegments } from "./segments";

describe("countSegments", () => {
  it("is zero segments for empty text", () => {
    expect(countSegments("")).toEqual({ encoding: "gsm7", units: 0, segments: 0, remaining: 160 });
  });
  it("fits 160 GSM-7 characters in one segment", () => {
    expect(countSegments("a".repeat(160))).toMatchObject({ segments: 1, remaining: 0 });
  });
  it("splits at 153 per segment once past 160", () => {
    expect(countSegments("a".repeat(161))).toMatchObject({ segments: 2, remaining: 145 });
    expect(countSegments("a".repeat(306))).toMatchObject({ segments: 2, remaining: 0 });
    expect(countSegments("a".repeat(307))).toMatchObject({ segments: 3 });
  });
  it("counts extension characters as two septets", () => {
    expect(countSegments("€".repeat(80))).toMatchObject({ encoding: "gsm7", units: 160, segments: 1 });
    expect(countSegments("{".repeat(81))).toMatchObject({ units: 162, segments: 2 });
  });
  it("switches to UCS-2 for characters outside GSM-7", () => {
    expect(countSegments("Bring the good thermos ☕")).toMatchObject({ encoding: "ucs2", segments: 1 });
    expect(countSegments("ж".repeat(70))).toMatchObject({ encoding: "ucs2", segments: 1, remaining: 0 });
    expect(countSegments("ж".repeat(71))).toMatchObject({ segments: 2, remaining: 63 });
  });
  it("counts an emoji as two UTF-16 code units", () => {
    expect(countSegments("😀")).toMatchObject({ encoding: "ucs2", units: 2 });
  });
});
