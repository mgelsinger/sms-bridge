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
      reconnect: vi.fn().mockResolvedValue(undefined),
      search: vi.fn().mockResolvedValue([]),
      messages: vi.fn().mockResolvedValue([]),
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
