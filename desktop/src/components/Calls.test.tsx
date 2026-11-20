// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { Calls } from "./Calls";
import { CallBanner } from "./CallBanner";
import type { CallView, Line } from "../lib/api";
import type { CallsModel } from "../lib/useCalls";

const ALL = { history: true, dial: true, answer: true, end: true };
const IDLE: Line = { state: "idle", direction: null, number: null, since: 0 };

const entry = (id: number, over: Partial<CallView>): CallView => ({
  id: `call:${id}`,
  number: "+15855550101",
  name: "Alex Rivera",
  kind: "in",
  date: 1_762_981_200_000,
  duration_s: 187,
  seen: true,
  ...over,
});

function model(over: Partial<CallsModel> = {}): CallsModel {
  return {
    connected: true,
    line: IDLE,
    lineSince: Date.now(),
    abilities: ALL,
    history: [],
    missed: 0,
    names: new Map([["+15855550101", "Alex Rivera"]]),
    pending: false,
    error: null,
    dial: vi.fn(),
    act: vi.fn(),
    markSeen: vi.fn(),
    ...over,
  };
}

afterEach(cleanup);

describe("call history", () => {
  it("lists calls by contact, number, or as private, with how each went", () => {
    const calls = model({
      history: [
        entry(3, { kind: "missed", duration_s: 0 }),
        entry(2, { name: null, number: "+15855550177", kind: "out", duration_s: 42 }),
        entry(1, { name: null, number: null, kind: "rejected", duration_s: 0 }),
      ],
    });
    render(<Calls calls={calls} focusDial={0} />);
    const rows = within(screen.getByRole("region", { name: "Call history" })).getAllByRole("listitem");
    expect(rows.map((r) => r.textContent)).toEqual([
      expect.stringContaining("Alex RiveraMissed · +15855550101"),
      expect.stringContaining("+15855550177Outgoing, 0:42"),
      expect.stringContaining("Private numberDeclined"),
    ]);
    // A withheld number cannot be called back.
    expect(within(rows[2]!).queryByRole("button", { name: /^Call / })).toBeNull();
  });

  it("calls back from a row with that row's number", () => {
    const calls = model({ history: [entry(1, {})] });
    render(<Calls calls={calls} focusDial={0} />);
    fireEvent.click(screen.getByRole("button", { name: "Call Alex Rivera" }));
    expect(calls.dial).toHaveBeenCalledTimes(1);
    expect(calls.dial).toHaveBeenCalledWith("+15855550101");
  });

  it("marks missed calls seen once the history is on screen", () => {
    const calls = model({ missed: 2 });
    render(<Calls calls={calls} focusDial={0} />);
    expect(calls.markSeen).toHaveBeenCalledTimes(1);
  });

  it("does not touch the seen mark when nothing was missed", () => {
    const calls = model();
    render(<Calls calls={calls} focusDial={0} />);
    expect(calls.markSeen).not.toHaveBeenCalled();
  });
});

describe("dial pad", () => {
  const typed = (text: string) => fireEvent.change(screen.getByLabelText("Number to call"), { target: { value: text } });
  const callButton = () => within(screen.getByRole("region", { name: "Dial pad" })).getByRole("button", { name: "Call" });

  it("dials what was typed, once, on Call", () => {
    const calls = model();
    render(<Calls calls={calls} focusDial={0} />);
    typed("(585) 555-0177");
    fireEvent.click(callButton());
    expect(calls.dial).toHaveBeenCalledTimes(1);
    expect(calls.dial).toHaveBeenCalledWith("5855550177");
  });

  it("dials on Enter, and the keys type into the number", () => {
    const calls = model();
    render(<Calls calls={calls} focusDial={0} />);
    for (const k of ["5", "5", "5", "*", "#"]) fireEvent.click(screen.getByRole("button", { name: `Digit ${k}` }));
    fireEvent.keyDown(screen.getByLabelText("Number to call"), { key: "Enter" });
    expect(calls.dial).toHaveBeenCalledWith("555*#");
  });

  it("never dials something that is not a number", () => {
    const calls = model();
    render(<Calls calls={calls} focusDial={0} />);
    typed("call mom");
    expect(callButton()).toHaveProperty("disabled", true);
    fireEvent.keyDown(screen.getByLabelText("Number to call"), { key: "Enter" });
    expect(calls.dial).not.toHaveBeenCalled();
  });

  it.each([
    ["while a request is waiting for the phone", { pending: true }],
    ["while the phone is in a call", { line: { state: "active", direction: "in", number: null, since: 0 } as Line }],
    ["when not connected", { connected: false }],
    ["when the phone may not place calls", { abilities: { ...ALL, dial: false } }],
  ])("cannot dial %s", (_why, over) => {
    const calls = model({ history: [entry(1, {})], ...over });
    render(<Calls calls={calls} focusDial={0} />);
    typed("5855550177");
    expect(callButton()).toHaveProperty("disabled", true);
    fireEvent.keyDown(screen.getByLabelText("Number to call"), { key: "Enter" });
    fireEvent.click(screen.getByRole("button", { name: "Call Alex Rivera" }));
    expect(calls.dial).not.toHaveBeenCalled();
  });

  it("shows why the phone refused", () => {
    render(<Calls calls={model({ error: "emergency numbers are dialed on the phone" })} focusDial={0} />);
    expect(screen.getByRole("status").textContent).toBe("emergency numbers are dialed on the phone");
  });

  it("says what to do when the phone has not allowed calls", () => {
    render(<Calls calls={model({ abilities: { history: false, dial: false, answer: false, end: false } })} focusDial={0} />);
    expect(screen.getByText(/tap Allow calls/)).toBeTruthy();
  });
});

describe("call bar", () => {
  const ringing: Line = { state: "ringing", direction: "in", number: "+15855550101", since: 0 };

  it("is not there while the line is idle", () => {
    const { container } = render(<CallBanner calls={model()} />);
    expect(container.textContent).toBe("");
  });

  it("names a ringing caller from the contacts and offers answer and decline", () => {
    const calls = model({ line: ringing });
    render(<CallBanner calls={calls} />);
    const bar = screen.getByRole("alert", { name: "Phone call" });
    expect(bar.textContent).toContain("Incoming call");
    expect(bar.textContent).toContain("Alex Rivera");
    fireEvent.click(screen.getByRole("button", { name: "Decline" }));
    expect(calls.act).toHaveBeenCalledWith("end");
    fireEvent.click(screen.getByRole("button", { name: "Answer on phone" }));
    expect(calls.act).toHaveBeenCalledWith("answer");
  });

  it("shows a withheld number as private", () => {
    render(<CallBanner calls={model({ line: { ...ringing, number: null } })} />);
    expect(screen.getByRole("alert").textContent).toContain("Private number");
  });

  it("offers only what the phone allows", () => {
    render(<CallBanner calls={model({ line: ringing, abilities: { ...ALL, answer: false, end: false } })} />);
    expect(screen.queryByRole("button")).toBeNull();
  });

  it("offers Hang up during a call, with who it is with and for how long", () => {
    const calls = model({ line: { state: "active", direction: "out", number: "+15855550177", since: 0 }, lineSince: Date.now() - 65_000 });
    render(<CallBanner calls={calls} />);
    const bar = screen.getByRole("alert");
    expect(bar.textContent).toContain("Call to +15855550177");
    expect(bar.textContent).toMatch(/1:0[5-7]/);
    fireEvent.click(screen.getByRole("button", { name: "Hang up" }));
    expect(calls.act).toHaveBeenCalledWith("end");
    expect(screen.queryByRole("button", { name: "Answer on phone" })).toBeNull();
  });

  it("does not guess who a call placed on the phone itself is with", () => {
    render(<CallBanner calls={model({ line: { state: "active", direction: "out", number: null, since: 0 } })} />);
    expect(screen.getByRole("alert").textContent).toContain("Call in progress on the phone");
  });
});
