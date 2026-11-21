import { describe, expect, it } from "vitest";
import { callDuration, callSummary, callerLabel, callsUnavailable, dialable, filterCalls, lineHeadline } from "./calls";
import type { CallView, Line } from "./api";

describe("callDuration", () => {
  it("shows minutes and seconds, and hours only when there are any", () => {
    expect(callDuration(0)).toBe("0:00");
    expect(callDuration(42)).toBe("0:42");
    expect(callDuration(725)).toBe("12:05");
    expect(callDuration(3723)).toBe("1:02:03");
  });
});

describe("callSummary", () => {
  it("says how each kind of call went", () => {
    expect(callSummary("in", 187)).toBe("Incoming, 3:07");
    expect(callSummary("out", 42)).toBe("Outgoing, 0:42");
    expect(callSummary("out", 0)).toBe("Outgoing, not answered");
    expect(callSummary("missed", 0)).toBe("Missed");
    expect(callSummary("rejected", 0)).toBe("Declined");
    expect(callSummary("blocked", 0)).toBe("Blocked");
    expect(callSummary("voicemail", 0)).toBe("Voicemail");
  });
});

describe("callerLabel", () => {
  it("prefers the contact, then the number, then says it was withheld", () => {
    expect(callerLabel("Alex Rivera", "+15855550101")).toBe("Alex Rivera");
    expect(callerLabel(null, "+15855550101")).toBe("+15855550101");
    expect(callerLabel(undefined, null)).toBe("Private number");
  });
});

describe("dialable", () => {
  it("keeps digits, a leading plus, and service code characters", () => {
    expect(dialable("+1 (585) 555-0101")).toBe("+15855550101");
    expect(dialable("585.555.0101")).toBe("5855550101");
    expect(dialable("*#06#")).toBe("*#06#");
  });
  it("refuses what is not a number", () => {
    expect(dialable("")).toBeNull();
    expect(dialable("7")).toBeNull();
    expect(dialable("call mom")).toBeNull();
    expect(dialable("555+0101")).toBeNull();
    expect(dialable("1".repeat(33))).toBeNull();
  });
});

describe("lineHeadline", () => {
  const line = (state: Line["state"], direction: Line["direction"]): Line => ({ state, direction, number: null, since: 0 });
  it("names what the line is doing", () => {
    expect(lineHeadline(line("idle", null), null)).toBeNull();
    expect(lineHeadline(line("ringing", "in"), "Alex")).toBe("Incoming call");
    expect(lineHeadline(line("active", "in"), "Alex")).toBe("On a call with Alex");
    expect(lineHeadline(line("active", "out"), "Alex")).toBe("Call to Alex");
    expect(lineHeadline(line("active", "out"), null)).toBe("Call in progress on the phone");
  });
});

describe("filterCalls", () => {
  const call = (id: number, over: Partial<CallView>): CallView => ({
    id: `call:${id}`,
    number: "+15855550101",
    name: "Alex Rivera",
    kind: "in",
    date: id,
    duration_s: 10,
    seen: true,
    photo_contact: null,
    ...over,
  });
  const history = [
    call(1, {}),
    call(2, { kind: "missed", duration_s: 0 }),
    call(3, { name: null, number: "+15855550177", kind: "rejected", duration_s: 0 }),
    call(4, { name: "José Ortiz", number: "+15855550188", kind: "out" }),
    call(5, { name: null, number: null, kind: "missed", duration_s: 0 }),
  ];
  const ids = (list: CallView[]) => list.map((c) => c.id);

  it("shows everything with no search and no filter", () => {
    expect(ids(filterCalls(history, "", "all"))).toEqual(["call:1", "call:2", "call:3", "call:4", "call:5"]);
  });
  it("keeps missed and declined calls under Missed", () => {
    expect(ids(filterCalls(history, "", "missed"))).toEqual(["call:2", "call:3", "call:5"]);
  });
  it("finds calls by name the way the texts search does", () => {
    expect(ids(filterCalls(history, "riv", "all"))).toEqual(["call:1", "call:2"]);
    expect(ids(filterCalls(history, "jose", "all"))).toEqual(["call:4"]);
  });
  it("finds calls by number typed any way", () => {
    expect(ids(filterCalls(history, "(585) 555-0177", "all"))).toEqual(["call:3"]);
    expect(ids(filterCalls(history, "0101", "missed"))).toEqual(["call:2"]);
  });
  it("never matches a withheld number", () => {
    expect(filterCalls(history, "private", "all")).toEqual([]);
  });
});

describe("callsUnavailable", () => {
  const all = { history: true, dial: true, answer: true, end: true };
  it("explains each reason calls cannot be used", () => {
    expect(callsUnavailable(false, all)).toMatch(/Not connected/);
    expect(callsUnavailable(true, null)).toMatch(/newest SMS Bridge/);
    expect(callsUnavailable(true, { history: false, dial: false, answer: false, end: false })).toMatch(/Allow calls/);
  });
  it("says nothing when calls work, or while it is not known yet", () => {
    expect(callsUnavailable(true, all)).toBeNull();
    expect(callsUnavailable(true, { ...all, answer: false, end: false })).toBeNull();
    expect(callsUnavailable(true, undefined)).toBeNull();
  });
});
