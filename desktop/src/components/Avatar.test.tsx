// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, waitFor } from "@testing-library/react";
import { Avatar, initials } from "./Avatar";
import { api } from "../lib/api";

vi.mock("../lib/api", async (original) => {
  const real = await original<typeof import("../lib/api")>();
  return { ...real, api: { contactPhoto: vi.fn() } };
});
URL.createObjectURL = vi.fn(() => "blob:photo");
afterEach(cleanup);

describe("initials", () => {
  it("takes the first and last name's first letters", () => {
    expect(initials("Alex Rivera")).toBe("AR");
    expect(initials("Alex")).toBe("A");
    expect(initials("Mary Jo van der Berg")).toBe("MB");
    expect(initials("josé ortiz")).toBe("JO");
  });

  it("shows # for a number or anything without letters", () => {
    expect(initials("+15855550101")).toBe("#");
    expect(initials("")).toBe("#");
  });
});

describe("Avatar", () => {
  it("shows the phone's photo, asking for it once per contact", async () => {
    vi.mocked(api.contactPhoto).mockResolvedValue(new ArrayBuffer(8));
    const { container } = render(
      <>
        <Avatar title="Alex Rivera" contactId={7} />
        <Avatar title="Alex Rivera" contactId={7} />
      </>,
    );
    await waitFor(() => expect(container.querySelectorAll("img")).toHaveLength(2));
    expect(api.contactPhoto).toHaveBeenCalledTimes(1);
  });

  it("falls back to initials without a photo, or when the phone is away", async () => {
    vi.mocked(api.contactPhoto).mockRejectedValue("offline");
    const { container } = render(
      <>
        <Avatar title="Sam Lee" contactId={null} />
        <Avatar title="Alex Rivera" contactId={9} />
      </>,
    );
    await waitFor(() => expect(api.contactPhoto).toHaveBeenCalledWith(9));
    expect(container.querySelector("img")).toBeNull();
    expect(container.textContent).toBe("SLAR");
  });
});
