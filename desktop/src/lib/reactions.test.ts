import { describe, expect, it } from "vitest";
import { parseReaction, placeReactions, quoteMatches, type ReactionMessage } from "./reactions";

const msg = (id: string, date: number, body: string | null, extra: Partial<ReactionMessage> = {}): ReactionMessage => ({
  id,
  date,
  body,
  parts: [],
  address: "+15855550101",
  direction: "in",
  ...extra,
});

describe("parseReaction", () => {
  it("reads the classic tapbacks with curly or straight quotes", () => {
    expect(parseReaction("Loved “see you soon”")).toEqual({ emoji: "❤️", target: "see you soon", attachment: null, removed: false });
    expect(parseReaction('Liked "ok"')?.emoji).toBe("👍");
    expect(parseReaction("Laughed at “that dog”")?.emoji).toBe("😂");
    expect(parseReaction("Emphasized “now”")?.emoji).toBe("‼️");
    expect(parseReaction("Questioned “really”")?.emoji).toBe("❓");
    expect(parseReaction("Disliked “rain”")?.emoji).toBe("👎");
  });

  it("reads emoji tapbacks and reactions to pictures", () => {
    expect(parseReaction("Reacted 🔥 to “the game”")).toEqual({ emoji: "🔥", target: "the game", attachment: null, removed: false });
    expect(parseReaction("Loved an image")).toEqual({ emoji: "❤️", target: null, attachment: "an image", removed: false });
  });

  it("reads removals", () => {
    expect(parseReaction("Removed a heart from “see you soon”")).toEqual({ emoji: "❤️", target: "see you soon", attachment: null, removed: true });
  });

  it("leaves ordinary texts alone", () => {
    for (const body of ["Liked it a lot", "Loved the movie", "Laughed at him lol", "Reacted badly", "Removed the tree from the yard", null, ""]) {
      expect(parseReaction(body)).toBeNull();
    }
  });
});

describe("quoteMatches", () => {
  it("matches the whole text, or the start of a long one iOS cut short", () => {
    expect(quoteMatches("see you soon", "see you soon")).toBe(true);
    expect(quoteMatches("Are we still on for dinner…", "Are we still on for dinner on Friday at the usual place?")).toBe(true);
    expect(quoteMatches("see you", "see you soon")).toBe(false);
    expect(quoteMatches("ok…", "okay then")).toBe(false);
  });
});

describe("placeReactions", () => {
  it("puts a reaction on the newest earlier message it names and hides its own bubble", () => {
    const a = msg("a", 1, "see you soon", { direction: "out" });
    const b = msg("b", 2, "see you soon", { direction: "out" });
    const r = msg("r", 3, "Loved “see you soon”");
    const placed = placeReactions([r, a, b], () => "Alex");
    expect(placed.byMessage.get("b")).toEqual([{ emoji: "❤️", from: "Alex" }]);
    expect(placed.byMessage.has("a")).toBe(false);
    expect([...placed.hidden]).toEqual(["r"]);
  });

  it("puts a reaction to an image on the newest earlier picture", () => {
    const pic = msg("p", 1, null, { parts: [{ content_type: "image/jpeg" }] });
    const text = msg("t", 2, "nice");
    const r = msg("r", 3, "Loved an image");
    expect(placeReactions([pic, text, r], () => "Alex").byMessage.get("p")).toEqual([{ emoji: "❤️", from: "Alex" }]);
  });

  it("takes a reaction back when it is removed", () => {
    const a = msg("a", 1, "see you soon", { direction: "out" });
    const placed = placeReactions([a, msg("r1", 2, "Loved “see you soon”"), msg("r2", 3, "Removed a heart from “see you soon”")], () => "Alex");
    expect(placed.byMessage.get("a")).toEqual([]);
    expect(placed.hidden.size).toBe(2);
  });

  it("keeps the bubble of a reaction whose message is not loaded", () => {
    const placed = placeReactions([msg("r", 3, "Loved “something older”")], () => "Alex");
    expect(placed.hidden.size).toBe(0);
  });
});
