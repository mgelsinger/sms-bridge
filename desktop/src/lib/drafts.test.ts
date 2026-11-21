// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from "vitest";
import { hasDraft, loadDraft, onDraftsChanged, saveDraft } from "./drafts";

beforeEach(() => window.localStorage.clear());

describe("drafts", () => {
  it("keeps text per conversation and forgets it when emptied", () => {
    saveDraft("thread:1", "half a thought");
    expect(loadDraft("thread:1")).toBe("half a thought");
    expect(hasDraft("thread:2")).toBe(false);
    saveDraft("thread:1", "   ");
    expect(hasDraft("thread:1")).toBe(false);
  });

  it("tells the list only when a draft appears or goes away", () => {
    const changed = vi.fn();
    const off = onDraftsChanged(changed);
    saveDraft("thread:1", "a");
    saveDraft("thread:1", "ab");
    saveDraft("thread:1", "");
    off();
    expect(changed).toHaveBeenCalledTimes(2);
  });

  it("works without storage, keeping nothing", () => {
    const spy = vi.spyOn(window, "localStorage", "get").mockImplementation(() => {
      throw new Error("blocked");
    });
    saveDraft("thread:1", "x");
    expect(loadDraft("thread:1")).toBe("");
    spy.mockRestore();
  });
});
