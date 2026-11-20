import { describe, expect, it } from "vitest";
import { threadMatches } from "./search";

const alex = { title: "Alex Rivera", participants: ["+15855550101"] };
const group = { title: "Alex Rivera, José Ortiz", participants: ["+15855550101", "+15855550177"] };
const unnamed = { title: "+15855550142", participants: ["+15855550142"] };

describe("threadMatches", () => {
  it("finds a name by the beginnings of its words, in any order", () => {
    expect(threadMatches(alex, "alex")).toBe(true);
    expect(threadMatches(alex, "riv")).toBe(true);
    expect(threadMatches(alex, "riv al")).toBe(true);
    expect(threadMatches(alex, "ALEX RIVERA")).toBe(true);
  });

  it("does not find letters from the middle of a word, like message search", () => {
    expect(threadMatches(alex, "lex")).toBe(false);
    expect(threadMatches(alex, "alex smith")).toBe(false);
  });

  it("ignores accents both ways", () => {
    expect(threadMatches(group, "jose")).toBe(true);
    expect(threadMatches({ title: "Jose Ortiz", participants: [] }, "josé")).toBe(true);
  });

  it("finds any member of a group by name or number", () => {
    expect(threadMatches(group, "ortiz")).toBe(true);
    expect(threadMatches(group, "0177")).toBe(true);
  });

  it("finds a number however it is typed", () => {
    for (const typed of ["5855550101", "585-555-0101", "(585) 555-0101", "+1 585 555 0101", "555.0101", "0101"]) {
      expect(threadMatches(alex, typed)).toBe(true);
    }
    expect(threadMatches(alex, "585-555-0199")).toBe(false);
  });

  it("finds a conversation with no contact name by its number", () => {
    expect(threadMatches(unnamed, "0142")).toBe(true);
  });

  it("needs three digits before matching on a number", () => {
    expect(threadMatches(alex, "01")).toBe(false);
    expect(threadMatches(alex, "  ")).toBe(false);
  });
});
