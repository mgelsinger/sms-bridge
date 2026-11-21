// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { Composer } from "./Composer";
import { TooLarge } from "../lib/shrink";

afterEach(cleanup);

const box = () => screen.getByLabelText("Message") as HTMLTextAreaElement;

describe("Composer", () => {
  it("sends on Enter and clears the box", async () => {
    const onSend = vi.fn().mockResolvedValue(undefined);
    render(<Composer onSend={onSend} />);
    fireEvent.change(box(), { target: { value: "On my way" } });
    fireEvent.keyDown(box(), { key: "Enter" });
    await waitFor(() => expect(onSend).toHaveBeenCalledWith("On my way", []));
    await waitFor(() => expect(box().value).toBe(""));
  });

  it("does not send on Shift+Enter", () => {
    const onSend = vi.fn().mockResolvedValue(undefined);
    render(<Composer onSend={onSend} />);
    fireEvent.change(box(), { target: { value: "line one" } });
    fireEvent.keyDown(box(), { key: "Enter", shiftKey: true });
    expect(onSend).not.toHaveBeenCalled();
  });

  it("keeps the text when sending fails", async () => {
    const onSend = vi.fn().mockRejectedValue(new Error("offline"));
    render(<Composer onSend={onSend} />);
    fireEvent.change(box(), { target: { value: "keep me" } });
    fireEvent.click(screen.getByLabelText("Send"));
    await waitFor(() => expect(onSend).toHaveBeenCalled());
    expect(box().value).toBe("keep me");
  });

  it("ignores blank messages", () => {
    const onSend = vi.fn().mockResolvedValue(undefined);
    render(<Composer onSend={onSend} />);
    fireEvent.change(box(), { target: { value: "   " } });
    fireEvent.keyDown(box(), { key: "Enter" });
    expect(onSend).not.toHaveBeenCalled();
    expect((screen.getByLabelText("Send") as HTMLButtonElement).disabled).toBe(true);
  });

  it("shows characters left, then remaining / segments once split", () => {
    render(<Composer onSend={vi.fn()} />);
    fireEvent.change(box(), { target: { value: "a".repeat(150) } });
    expect(screen.getByLabelText("Segments").textContent).toBe("10");
    fireEvent.change(box(), { target: { value: "a".repeat(161) } });
    expect(screen.getByLabelText("Segments").textContent).toBe("145 / 2");
  });
});

describe("Composer attachments", () => {
  const pic = () => new File([new Uint8Array(10)], "IMG_1.jpg", { type: "image/jpeg" });
  const ready = [{ content_type: "image/jpeg", name: "IMG_1.jpg", data: "AAAA" }];
  // jsdom has no blob URLs.
  URL.createObjectURL = vi.fn(() => "blob:test");
  URL.revokeObjectURL = vi.fn();

  it("offers no attaching when the phone cannot send MMS", () => {
    render(<Composer onSend={vi.fn()} />);
    expect(screen.queryByLabelText("Attach a picture or file")).toBeNull();
  });

  it("sends a picture alone, shrunk to the phone's limit, and clears it", async () => {
    const onSend = vi.fn().mockResolvedValue(undefined);
    const prepare = vi.fn().mockResolvedValue(ready);
    render(<Composer onSend={onSend} mmsMaxBytes={1_000_000} prepare={prepare} />);
    fireEvent.change(screen.getByTestId("attach-input"), { target: { files: [pic()] } });
    expect(screen.getByRole("list", { name: "Attachments" }).children).toHaveLength(1);
    expect(screen.getByLabelText("Segments").textContent).toBe("MMS");
    fireEvent.click(screen.getByRole("button", { name: "Send" }));
    await waitFor(() => expect(onSend).toHaveBeenCalledWith("", ready));
    expect(prepare.mock.calls[0]?.[2]).toBe(1_000_000);
    await waitFor(() => expect(screen.queryByRole("list", { name: "Attachments" })).toBeNull());
  });

  it("takes pictures pasted into the box", () => {
    render(<Composer onSend={vi.fn()} mmsMaxBytes={1_000_000} prepare={vi.fn()} />);
    fireEvent.paste(box(), { clipboardData: { files: [pic()] } });
    expect(screen.getByRole("list", { name: "Attachments" }).children).toHaveLength(1);
  });

  it("can take a picture back before sending", () => {
    render(<Composer onSend={vi.fn()} mmsMaxBytes={1_000_000} prepare={vi.fn()} />);
    fireEvent.change(screen.getByTestId("attach-input"), { target: { files: [pic()] } });
    fireEvent.click(screen.getByRole("button", { name: "Remove IMG_1.jpg" }));
    expect(screen.queryByRole("list", { name: "Attachments" })).toBeNull();
  });

  it("says when a file cannot fit, and keeps everything", async () => {
    const onSend = vi.fn();
    render(<Composer onSend={onSend} mmsMaxBytes={1_000_000} prepare={vi.fn().mockRejectedValue(new TooLarge("clip.mp4 is too large"))} />);
    fireEvent.change(screen.getByTestId("attach-input"), { target: { files: [pic()] } });
    fireEvent.change(box(), { target: { value: "look" } });
    fireEvent.click(screen.getByRole("button", { name: "Send" }));
    expect((await screen.findByRole("alert")).textContent).toBe("clip.mp4 is too large");
    expect(onSend).not.toHaveBeenCalled();
    expect(box().value).toBe("look");
    expect(screen.getByRole("list", { name: "Attachments" }).children).toHaveLength(1);
  });
});
