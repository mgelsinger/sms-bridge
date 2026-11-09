// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { Composer } from "./Composer";

afterEach(cleanup);

const box = () => screen.getByLabelText("Message") as HTMLTextAreaElement;

describe("Composer", () => {
  it("sends on Enter and clears the box", async () => {
    const onSend = vi.fn().mockResolvedValue(undefined);
    render(<Composer onSend={onSend} />);
    fireEvent.change(box(), { target: { value: "On my way" } });
    fireEvent.keyDown(box(), { key: "Enter" });
    await waitFor(() => expect(onSend).toHaveBeenCalledWith("On my way"));
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
