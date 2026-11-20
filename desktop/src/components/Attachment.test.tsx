// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { Attachment, partKind } from "./Attachment";
import { api } from "../lib/api";

vi.mock("../lib/api", async (original) => {
  const real = await original<typeof import("../lib/api")>();
  return {
    ...real,
    api: {
      mmsPart: vi.fn(),
      saveMmsPart: vi.fn(),
      showInFolder: vi.fn().mockResolvedValue(undefined),
    },
  };
});

beforeEach(() => {
  vi.mocked(api.mmsPart).mockReset().mockResolvedValue(new ArrayBuffer(4));
  vi.mocked(api.saveMmsPart).mockReset().mockResolvedValue("C:\\Users\\me\\Downloads\\clip.mp4");
  // jsdom has no blob URLs.
  URL.createObjectURL = vi.fn(() => "blob:test");
  URL.revokeObjectURL = vi.fn();
});
afterEach(cleanup);

const video = { part_id: 5, content_type: "video/mp4", size: 3_400_000, name: "clip.mp4" };

describe("partKind", () => {
  it("sorts parts by what can be done with them", () => {
    expect(partKind("image/jpeg")).toBe("image");
    expect(partKind("VIDEO/3gpp")).toBe("video");
    expect(partKind("audio/amr")).toBe("audio");
    expect(partKind("text/x-vCard")).toBe("contact");
    expect(partKind("application/pdf")).toBe("file");
  });
});

describe("Attachment", () => {
  it("does not fetch a video from the phone until Play is pressed", async () => {
    render(<Attachment part={video} />);
    expect(screen.getByText("Video: clip.mp4 (3.2 MB)")).toBeTruthy();
    expect(api.mmsPart).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Play" }));
    await waitFor(() => expect(screen.getByLabelText("Video: clip.mp4 (3.2 MB)").tagName).toBe("VIDEO"));
    expect(api.mmsPart).toHaveBeenCalledWith(5);
  });

  it("saves to Downloads and offers to show the file", async () => {
    render(<Attachment part={video} />);
    fireEvent.click(screen.getByRole("button", { name: "Save Video: clip.mp4 (3.2 MB)" }));
    expect(await screen.findByText(/Saved to clip\.mp4/)).toBeTruthy();
    expect(api.saveMmsPart).toHaveBeenCalledWith(5, "clip.mp4");
    fireEvent.click(screen.getByRole("button", { name: "Show in folder" }));
    expect(api.showInFolder).toHaveBeenCalledWith("C:\\Users\\me\\Downloads\\clip.mp4");
  });

  it("says why when saving fails", async () => {
    vi.mocked(api.saveMmsPart).mockRejectedValue("not paired");
    render(<Attachment part={{ part_id: 6, content_type: "application/pdf", size: null, name: null }} />);
    fireEvent.click(screen.getByRole("button", { name: "Save File" }));
    expect(await screen.findByText("Not saved: not paired")).toBeTruthy();
  });
});
