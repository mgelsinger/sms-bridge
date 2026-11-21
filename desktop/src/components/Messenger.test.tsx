// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { Messenger } from "./Messenger";
import { api } from "../lib/api";

// The window has no Tauri backend in tests; every command answers from here.
vi.mock("../lib/api", async (original) => {
  const real = await original<typeof import("../lib/api")>();
  const never = () => Promise.resolve(() => {});
  return {
    ...real,
    api: {
      threads: vi.fn().mockResolvedValue([]),
      setOpenThread: vi.fn().mockResolvedValue(undefined),
      markRead: vi.fn().mockResolvedValue(undefined),
      popupsEnabled: vi.fn().mockResolvedValue(false),
      setPopups: vi.fn().mockResolvedValue(undefined),
      autostartEnabled: vi.fn().mockResolvedValue(false),
      setAutostart: vi.fn().mockResolvedValue(undefined),
      callPopupsEnabled: vi.fn().mockResolvedValue(true),
      setCallPopups: vi.fn().mockResolvedValue(undefined),
      reconnect: vi.fn().mockResolvedValue(undefined),
      search: vi.fn().mockResolvedValue([]),
      messages: vi.fn().mockResolvedValue([]),
      mmsAbilities: vi.fn().mockResolvedValue(null),
      send: vi.fn().mockResolvedValue(undefined),
      contacts: vi.fn().mockResolvedValue([]),
      mmsPart: vi.fn().mockResolvedValue(new ArrayBuffer(4)),
      contactPhoto: vi.fn().mockRejectedValue("none"),
    },
    events: { conn: never, threadsChanged: never, threadChanged: never, deviceStatus: never },
  };
});

const paired = { device_name: "Pixel", host: "192.168.1.20", port: 8765, fingerprint: "a".repeat(64) };

function openMenu() {
  render(<Messenger paired={paired} conn={{ state: "connected", device_name: "Pixel" }} onUnpaired={() => {}} />);
  fireEvent.click(screen.getByLabelText("Connection options"));
}

beforeEach(() => {
  vi.mocked(api.autostartEnabled).mockResolvedValue(false);
  vi.mocked(api.setAutostart).mockReset().mockResolvedValue(undefined);
});
afterEach(cleanup);

describe("search box", () => {
  const thread = (id: number, title: string, participants: string[]) => ({ id, title, participants, snippet: null, date: 0, unread: 0 });
  const message = { id: "sms:9", thread_id: 2, kind: "sms" as const, direction: "in" as const, address: "+15855550177", sender_name: null, body: "see you at dinner", parts: [], date: 5, status: null, client_id: null, error: null };

  async function typeInSearch(text: string) {
    render(<Messenger paired={paired} conn={{ state: "connected", device_name: "Pixel" }} onUnpaired={() => {}} />);
    await screen.findByText("Alex Rivera");
    fireEvent.change(screen.getByLabelText("Search names, numbers, and messages"), { target: { value: text } });
    return screen.getByRole("list", { name: "Search results" });
  }

  beforeEach(() => {
    vi.mocked(api.threads).mockResolvedValue([thread(1, "Alex Rivera", ["+15855550101"]), thread(2, "José Ortiz", ["+15855550177"])]);
    vi.mocked(api.search).mockReset().mockResolvedValue([]);
  });

  it("lists the conversations a name names, before any message search answers", async () => {
    const list = await typeInSearch("riv");
    expect(list.textContent).toContain("Conversations");
    expect(list.textContent).toContain("Alex Rivera");
    expect(list.textContent).not.toContain("José Ortiz");
  });

  it("finds a conversation by number, typed any way", async () => {
    const list = await typeInSearch("(585) 555-0177");
    expect(list.textContent).toContain("José Ortiz");
    expect(list.textContent).not.toContain("Alex Rivera");
  });

  it("shows message hits under their own heading, and says when nothing matches", async () => {
    vi.mocked(api.search).mockResolvedValueOnce([message]);
    const list = await typeInSearch("dinner");
    await waitFor(() => expect(list.textContent).toContain("Messages"));
    expect(list.textContent).toContain("see you at dinner");
    expect(list.textContent).not.toContain("Conversations");

    fireEvent.change(screen.getByLabelText("Search names, numbers, and messages"), { target: { value: "zzz" } });
    await waitFor(() => expect(screen.getByRole("list", { name: "Search results" }).textContent).toContain("Nothing matches."));
  });

  it("opens a conversation found by name and clears the search", async () => {
    const list = await typeInSearch("alex");
    fireEvent.click(within(list).getByRole("button", { name: /Alex Rivera/ }));
    expect((screen.getByLabelText("Search names, numbers, and messages") as HTMLInputElement).value).toBe("");
    await waitFor(() => expect(api.setOpenThread).toHaveBeenCalledWith(1));
  });
});

describe("status menu", () => {
  it("offers starting with Windows, off until asked for", async () => {
    openMenu();
    const item = await screen.findByRole("menuitemcheckbox", { name: "Start with Windows: off" });
    expect(item.getAttribute("aria-checked")).toBe("false");
    fireEvent.click(item);
    await waitFor(() => expect(api.setAutostart).toHaveBeenCalledWith(true));
    fireEvent.click(screen.getByLabelText("Connection options"));
    expect(await screen.findByRole("menuitemcheckbox", { name: "Start with Windows: on" })).toBeTruthy();
  });

  it("shows what Windows will do and can switch it off again", async () => {
    vi.mocked(api.autostartEnabled).mockResolvedValue(true);
    openMenu();
    fireEvent.click(await screen.findByRole("menuitemcheckbox", { name: "Start with Windows: on" }));
    await waitFor(() => expect(api.setAutostart).toHaveBeenCalledWith(false));
  });

  it("leaves the switch as it was when Windows refuses the change", async () => {
    vi.mocked(api.setAutostart).mockRejectedValue("Access is denied.");
    const alert = vi.spyOn(window, "alert").mockImplementation(() => {});
    openMenu();
    fireEvent.click(await screen.findByRole("menuitemcheckbox", { name: "Start with Windows: off" }));
    await waitFor(() => expect(alert).toHaveBeenCalledWith("Access is denied."));
    fireEvent.click(screen.getByLabelText("Connection options"));
    expect(await screen.findByRole("menuitemcheckbox", { name: "Start with Windows: off" })).toBeTruthy();
    alert.mockRestore();
  });
});

describe("reacting, forwarding and group texts from the PC", () => {
  const alex = { id: 1, title: "Alex Rivera", participants: ["+15855550101"], snippet: null, date: 0, unread: 0 };
  const view = (id: string, date: number, body: string | null, extra: Record<string, unknown> = {}) => ({
    id, thread_id: 1, kind: "sms" as const, direction: "in" as const, address: "+15855550101", sender_name: "Alex Rivera",
    body, parts: [], date, status: null, client_id: null, error: null, ...extra,
  });
  URL.createObjectURL = vi.fn(() => "blob:test");
  URL.revokeObjectURL = vi.fn();

  async function openAlex(messages: ReturnType<typeof view>[]) {
    vi.mocked(api.threads).mockResolvedValue([alex]);
    vi.mocked(api.messages).mockResolvedValue(messages);
    vi.mocked(api.send).mockClear();
    render(<Messenger paired={paired} conn={{ state: "connected", device_name: "Pixel" }} onUnpaired={() => {}} />);
    fireEvent.click(await screen.findByText("Alex Rivera"));
    // The oldest message is never a reaction to something later, so it always has a bubble.
    await screen.findByText(messages[messages.length - 1]?.body ?? "");
  }

  it("sends a reaction as the text iPhones and Google Messages read", async () => {
    await openAlex([view("sms:1", 10, "see you soon")]);
    fireEvent.click(screen.getByRole("button", { name: "React" }));
    fireEvent.click(screen.getByRole("menuitemcheckbox", { name: "❤️" }));
    await waitFor(() => expect(api.send).toHaveBeenCalledWith("Loved “see you soon”", { threadId: 1 }));
  });

  it("takes back a reaction you already sent", async () => {
    await openAlex([view("sms:2", 20, "Loved “see you soon”", { direction: "out", address: "+15855550101" }), view("sms:1", 10, "see you soon")]);
    expect(screen.getByLabelText("Reactions").textContent).toBe("❤️");
    fireEvent.click(screen.getByRole("button", { name: "React" }));
    const heart = screen.getByRole("menuitemcheckbox", { name: "❤️" });
    expect(heart.getAttribute("aria-checked")).toBe("true");
    fireEvent.click(heart);
    await waitFor(() => expect(api.send).toHaveBeenCalledWith("Removed a heart from “see you soon”", { threadId: 1 }));
  });

  it("forwards a message's text and picture into a new message", async () => {
    vi.mocked(api.mmsAbilities).mockResolvedValue({ send: true, max_bytes: 1_048_576 });
    await openAlex([view("mms:1", 10, "look", { kind: "mms", parts: [{ part_id: 9, content_type: "image/jpeg", size: 4, name: "dog.jpg" }] })]);
    fireEvent.click(screen.getByRole("button", { name: "Forward" }));
    await screen.findByLabelText("Recipient");
    expect((screen.getByLabelText("Message") as HTMLTextAreaElement).value).toBe("look");
    expect(api.mmsPart).toHaveBeenCalledWith(9);
    expect(screen.getByRole("list", { name: "Attachments" }).children).toHaveLength(1);
  });

  it("starts a group text to everyone added", async () => {
    vi.mocked(api.mmsAbilities).mockResolvedValue({ send: true, max_bytes: 1_048_576 });
    vi.mocked(api.threads).mockResolvedValue([]);
    vi.mocked(api.send).mockClear();
    render(<Messenger paired={paired} conn={{ state: "connected", device_name: "Pixel" }} onUnpaired={() => {}} />);
    fireEvent.click(screen.getByLabelText("New message"));
    const to = screen.getByLabelText("Recipient");
    fireEvent.change(to, { target: { value: "585-555-0101" } });
    fireEvent.keyDown(to, { key: "Enter" });
    fireEvent.change(to, { target: { value: "5855550177" } });
    fireEvent.keyDown(to, { key: "Enter" });
    expect(screen.getByRole("list", { name: "Recipients" }).children).toHaveLength(2);
    expect(await screen.findByText("A group text to 2 people")).toBeTruthy();
    const box = screen.getByLabelText("Message");
    fireEvent.change(box, { target: { value: "Dinner at 7?" } });
    fireEvent.keyDown(box, { key: "Enter" });
    await waitFor(() => expect(api.send).toHaveBeenCalledWith("Dinner at 7?", { to: ["5855550101", "5855550177"] }, [], undefined));
  });
});

describe("conversation options", () => {
  it("pins, mutes and marks unread through the PC's own commands", async () => {
    const t = { id: 4, title: "Sam Lee", participants: ["+15855550103"], snippet: null, date: 0, unread: 0, pinned: false, muted: true };
    vi.mocked(api.threads).mockResolvedValue([t]);
    vi.mocked(api.messages).mockResolvedValue([]);
    Object.assign(api, { setPinned: vi.fn().mockResolvedValue(undefined), setMuted: vi.fn().mockResolvedValue(undefined), markUnread: vi.fn().mockResolvedValue(undefined) });
    render(<Messenger paired={paired} conn={{ state: "connected", device_name: "Pixel" }} onUnpaired={() => {}} />);
    expect(await screen.findByLabelText("Muted")).toBeTruthy();
    fireEvent.click(screen.getAllByText("Sam Lee")[0] as HTMLElement);
    const open = async () => fireEvent.click(await screen.findByLabelText("Conversation options"));
    await open();
    fireEvent.click(screen.getByRole("menuitemcheckbox", { name: "Pin to top" }));
    await waitFor(() => expect(api.setPinned).toHaveBeenCalledWith(4, true));
    await open();
    fireEvent.click(screen.getByRole("menuitemcheckbox", { name: "Unmute" }));
    await waitFor(() => expect(api.setMuted).toHaveBeenCalledWith(4, false));
    await open();
    fireEvent.click(screen.getByRole("menuitem", { name: "Mark as unread" }));
    await waitFor(() => expect(api.markUnread).toHaveBeenCalledWith(4));
  });
});

describe("threadFor", () => {
  it("finds the conversation with exactly those people, however the numbers are written", async () => {
    const { threadFor } = await import("./Messenger");
    const t = (id: number, participants: string[]) => ({ id, participants, title: "", snippet: null, date: 0, unread: 0 });
    const threads = [t(1, ["+15855550101"]), t(2, ["+15855550101", "+15855550177"])];
    expect(threadFor(threads, ["5855550177", "585-555-0101"])?.id).toBe(2);
    expect(threadFor(threads, ["5855550101"])?.id).toBe(1);
    expect(threadFor(threads, ["5855550199"])).toBeUndefined();
  });
});

describe("group conversations", () => {
  const group = { id: 2, title: "Alex Rivera, Sam", participants: ["+15855550101", "+15855550103"], snippet: null, date: 0, unread: 0 };

  async function openGroup() {
    vi.mocked(api.threads).mockResolvedValue([group]);
    render(<Messenger paired={paired} conn={{ state: "connected", device_name: "Pixel" }} onUnpaired={() => {}} />);
    fireEvent.click(await screen.findByText("Alex Rivera, Sam"));
  }

  it("stay read-only when the phone cannot send MMS", async () => {
    vi.mocked(api.mmsAbilities).mockResolvedValue(null);
    await openGroup();
    expect(await screen.findByText(/Replying to a group needs your phone to send MMS/)).toBeTruthy();
    expect(screen.queryByLabelText("Message")).toBeNull();
  });

  it("can be replied to, and with a picture, when it can", async () => {
    vi.mocked(api.mmsAbilities).mockResolvedValue({ send: true, max_bytes: 1_048_576 });
    await openGroup();
    const box = (await screen.findByPlaceholderText("Message everyone")) as HTMLTextAreaElement;
    expect(screen.getByLabelText("Attach a picture or file")).toBeTruthy();
    fireEvent.change(box, { target: { value: "Dinner at 7?" } });
    fireEvent.keyDown(box, { key: "Enter" });
    await waitFor(() => expect(api.send).toHaveBeenCalledWith("Dinner at 7?", { threadId: 2 }, [], undefined));
  });
});

describe("calling from a conversation", () => {
  const thread = (id: number, title: string, participants: string[]) => ({ id, title, participants, snippet: null, date: 0, unread: 0 });

  async function open(title: string, call: { blocked: string | null; dial: (n: string) => void }) {
    vi.mocked(api.threads).mockResolvedValue([thread(1, "Alex Rivera", ["+15855550101"]), thread(2, "Alex Rivera, Sam", ["+15855550101", "+15855550103"]), thread(3, "ACME", ["ACME"])]);
    render(<Messenger paired={paired} conn={{ state: "connected", device_name: "Pixel" }} onUnpaired={() => {}} call={call} />);
    fireEvent.click(await screen.findByText(title));
  }

  it("dials the other person once, with their number", async () => {
    const dial = vi.fn();
    await open("Alex Rivera", { blocked: null, dial });
    fireEvent.click(await screen.findByRole("button", { name: "Call Alex Rivera" }));
    expect(dial).toHaveBeenCalledTimes(1);
    expect(dial).toHaveBeenCalledWith("+15855550101");
  });

  it("cannot be used while calls are blocked, and says why", async () => {
    const dial = vi.fn();
    await open("Alex Rivera", { blocked: "The phone is already in a call", dial });
    const button = await screen.findByRole("button", { name: "Call Alex Rivera" });
    expect(button).toHaveProperty("disabled", true);
    expect(button.getAttribute("title")).toBe("The phone is already in a call");
    fireEvent.click(button);
    expect(dial).not.toHaveBeenCalled();
  });

  it.each([
    ["a group", "Alex Rivera, Sam"],
    ["a sender that is not a number", "ACME"],
  ])("is not offered for %s", async (_what, title) => {
    await open(title, { blocked: null, dial: vi.fn() });
    // The conversation is on screen, so a missing button is not just a missing header.
    expect(document.querySelector("header")?.textContent).toContain(title);
    expect(screen.queryByRole("button", { name: /^Call / })).toBeNull();
  });
});
