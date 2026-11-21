import { describe, expect, it } from "vitest";
import { linkify } from "./links";

describe("linkify", () => {
  it("leaves plain text alone", () => {
    expect(linkify("see you at 7")).toEqual([{ text: "see you at 7" }]);
  });

  it("finds http, https and www links in the middle of text", () => {
    expect(linkify("menu: https://example.com/a?b=1 ok")).toEqual([
      { text: "menu: " },
      { text: "https://example.com/a?b=1", href: "https://example.com/a?b=1" },
      { text: " ok" },
    ]);
    expect(linkify("www.example.com")).toEqual([{ text: "www.example.com", href: "https://www.example.com" }]);
  });

  it("does not swallow the punctuation that ends a sentence", () => {
    expect(linkify("Look at http://x.org/p.")).toEqual([{ text: "Look at " }, { text: "http://x.org/p", href: "http://x.org/p" }, { text: "." }]);
    expect(linkify("(see https://x.org/a)")).toEqual([{ text: "(see " }, { text: "https://x.org/a", href: "https://x.org/a" }, { text: ")" }]);
    expect(linkify("https://en.wikipedia.org/wiki/Foo_(bar)")[0]).toEqual({ text: "https://en.wikipedia.org/wiki/Foo_(bar)", href: "https://en.wikipedia.org/wiki/Foo_(bar)" });
  });

  it("never makes a link of other schemes", () => {
    expect(linkify("javascript:alert(1) file:///c:/x ftp://x")).toEqual([{ text: "javascript:alert(1) file:///c:/x ftp://x" }]);
  });
});
