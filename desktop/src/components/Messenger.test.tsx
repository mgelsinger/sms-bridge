// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
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
