import { describe, expect, it } from "vitest";
import { cleanNumber, dayLabel, groupFingerprint, listTime, parsePairingQr, sameDay } from "./format";

const NOW = new Date(2026, 10, 9, 15, 0, 0).getTime(); // Mon Nov 9 2026, 3 PM local

describe("groupFingerprint", () => {
  it("makes four lines of four groups", () => {
    const fp = "3502891360fc8a3fa8f245b8f9d9c23c4104b6acbe6bddd704533e2884d5a538";
    const lines = groupFingerprint(fp);
    expect(lines).toHaveLength(4);
    expect(lines[0]).toBe("3502 8913 60fc 8a3f");
    expect(lines.join(" ").replace(/ /g, "")).toBe(fp);
  });
});

describe("listTime and dayLabel", () => {
  it("uses relative words near today", () => {
    expect(listTime(new Date(2026, 10, 8, 23, 0).getTime(), NOW, "en-US")).toBe("Yesterday");
    expect(listTime(new Date(2026, 10, 5, 12, 0).getTime(), NOW, "en-US")).toBe("Thu");
    expect(listTime(new Date(2026, 0, 2, 12, 0).getTime(), NOW, "en-US")).toBe("Jan 2");
    expect(listTime(new Date(2025, 0, 2, 12, 0).getTime(), NOW, "en-US")).toBe("Jan 2, 2025");
    expect(dayLabel(new Date(2026, 10, 9, 8, 0).getTime(), NOW, "en-US")).toBe("Today");
  });
  it("compares calendar days, not 24-hour windows", () => {
    expect(sameDay(new Date(2026, 10, 9, 0, 5).getTime(), new Date(2026, 10, 9, 23, 55).getTime())).toBe(true);
    expect(sameDay(new Date(2026, 10, 8, 23, 55).getTime(), new Date(2026, 10, 9, 0, 5).getTime())).toBe(false);
  });
});

describe("cleanNumber", () => {
  it("keeps + and digits", () => {
    expect(cleanNumber(" +1 (585) 555-0101 ")).toBe("+15855550101");
    expect(cleanNumber("585.555.0101")).toBe("5855550101");
    expect(cleanNumber("72975")).toBe("72975");
  });
  it("rejects things that are not numbers", () => {
    expect(cleanNumber("hi")).toBeNull();
    expect(cleanNumber("12")).toBeNull();
    expect(cleanNumber("1".repeat(16))).toBeNull();
  });
});

describe("parsePairingQr", () => {
  const good = { protocol_version: 1, host: "192.168.1.42", port: 8765, fingerprint: "a".repeat(64), pairing_code: "042917", device_name: "Pixel 9" };
  it("accepts the phone's QR payload", () => {
    expect(parsePairingQr(JSON.stringify(good))).toEqual({ host: "192.168.1.42", port: 8765, fingerprint: "a".repeat(64), pairing_code: "042917", device_name: "Pixel 9" });
  });
  it("rejects anything else", () => {
    expect(parsePairingQr("not json")).toBeNull();
    expect(parsePairingQr(JSON.stringify({ ...good, fingerprint: "XYZ" }))).toBeNull();
    expect(parsePairingQr(JSON.stringify({ ...good, protocol_version: 2 }))).toBeNull();
  });
});
