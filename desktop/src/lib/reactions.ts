// iPhone reactions ("tapbacks") sent to a phone without RCS arrive as plain texts such
// as `Loved "see you soon"`. This turns them back into reactions on the message they
// name. Pure, so it is unit tested.

export interface Reaction {
  emoji: string;
  /** The quoted text, without quotes; null when the reaction was to a picture or other attachment. */
  target: string | null;
  /** What kind of attachment was reacted to ("an image", "a movie"), when target is null. */
  attachment: string | null;
  removed: boolean;
}

const VERBS: [string, string][] = [
  ["Liked", "👍"],
  ["Loved", "❤️"],
  ["Disliked", "👎"],
  ["Laughed at", "😂"],
  ["Emphasized", "‼️"],
  ["Emphasised", "‼️"],
  ["Questioned", "❓"],
];

const REMOVALS: [string, string][] = [
  ["a like", "👍"],
  ["a heart", "❤️"],
  ["a dislike", "👎"],
  ["a laugh", "😂"],
  ["an exclamation", "‼️"],
  ["a question mark", "❓"],
];

const QUOTED = /^[“"](.*)[”"]$/su;
const ATTACHMENT = /^(an? (?:image|photo|movie|video|audio message|attachment|sticker|location))$/u;

function rest(target: string): Pick<Reaction, "target" | "attachment"> | null {
  const t = target.trim();
  const q = QUOTED.exec(t);
  if (q) return { target: q[1] ?? "", attachment: null };
  const a = ATTACHMENT.exec(t);
  if (a) return { target: null, attachment: a[1] ?? null };
  return null;
}

export function parseReaction(body: string | null): Reaction | null {
  if (!body) return null;
  const text = body.trim();
  for (const [verb, emoji] of VERBS) {
    if (text.startsWith(verb + " ")) {
      const r = rest(text.slice(verb.length + 1));
      if (r) return { emoji, ...r, removed: false };
    }
  }
  // iOS 18 emoji tapbacks: `Reacted 🔥 to "..."`.
  const custom = /^Reacted (\S+) to (.+)$/su.exec(text);
  if (custom) {
    const r = rest(custom[2] ?? "");
    if (r && custom[1]) return { emoji: custom[1], ...r, removed: false };
  }
  const removal = /^Removed (an? [a-z ]+?) from (.+)$/su.exec(text);
  if (removal) {
    const kind = REMOVALS.find(([k]) => k === removal[1]);
    const r = rest(removal[2] ?? "");
    if (kind && r) return { emoji: kind[1], ...r, removed: true };
  }
  return null;
}

/** The reactions the PC offers, in the order iPhones show them. */
export const OFFERED = ["❤️", "👍", "👎", "😂", "‼️", "❓"] as const;

const VERB_FOR: Record<string, string> = { "❤️": "Loved", "👍": "Liked", "👎": "Disliked", "😂": "Laughed at", "‼️": "Emphasized", "❓": "Questioned" };
const REMOVAL_FOR: Record<string, string> = { "❤️": "a heart", "👍": "a like", "👎": "a dislike", "😂": "a laugh", "‼️": "an exclamation", "❓": "a question mark" };
const QUOTE_LIMIT = 100;

/**
 * The text that reacts to a message over SMS, in the form iPhones send and Google
 * Messages shows as a reaction: `Loved "see you soon"`, `Loved an image`, or
 * `Removed a heart from "see you soon"`. Long texts are quoted up to an ellipsis, as
 * iPhones do. Null for a message with nothing to name.
 */
export function reactionText(emoji: string, target: { body: string | null; hasPicture: boolean }, remove = false): string | null {
  const body = target.body?.trim() ?? "";
  let object: string;
  if (body) {
    const chars = [...body];
    const quoted = chars.length > QUOTE_LIMIT ? chars.slice(0, QUOTE_LIMIT).join("").trimEnd() + "…" : body;
    object = `“${quoted}”`;
  } else if (target.hasPicture) {
    object = "an image";
  } else {
    return null;
  }
  if (remove) {
    const what = REMOVAL_FOR[emoji];
    return what ? `Removed ${what} from ${object}` : null;
  }
  const verb = VERB_FOR[emoji];
  return verb ? `${verb} ${object}` : `Reacted ${emoji} to ${object}`;
}

/** iOS shortens long quotes with an ellipsis; this decides whether a quote names a body. */
export function quoteMatches(quote: string, body: string | null): boolean {
  if (body === null) return false;
  const q = quote.trim();
  const b = body.trim();
  if (q === b) return true;
  const cut = q.replace(/(…|\.\.\.)$/u, "").trimEnd();
  return cut.length >= 8 && cut.length < q.length && b.startsWith(cut);
}

export interface ReactionMessage {
  id: string;
  body: string | null;
  parts: { content_type: string }[];
  date: number;
  address: string;
  direction: "in" | "out";
}

export interface Placed {
  /** Reactions to show under each message, by message id: emoji with who sent each. */
  byMessage: Map<string, { emoji: string; from: string }[]>;
  /** Reaction texts that were placed on their message and need no bubble of their own. */
  hidden: Set<string>;
}

/**
 * Places every reaction text on the newest earlier message it names. A reaction whose
 * message is not loaded (or never matched) keeps its own bubble, so nothing is lost.
 * `who` names the sender of a message for the badge.
 */
export function placeReactions<M extends ReactionMessage>(messages: M[], who: (m: M) => string): Placed {
  const ordered = [...messages].sort((a, b) => a.date - b.date);
  const byMessage = new Map<string, { emoji: string; from: string }[]>();
  const hidden = new Set<string>();
  for (let i = 0; i < ordered.length; i++) {
    const m = ordered[i];
    if (!m) continue;
    const r = parseReaction(m.body);
    if (!r) continue;
    let target: M | undefined;
    for (let j = i - 1; j >= 0; j--) {
      const c = ordered[j];
      if (!c || hidden.has(c.id) || parseReaction(c.body)) continue;
      const hit = r.target !== null ? quoteMatches(r.target, c.body) : c.parts.some((p) => !p.content_type.startsWith("text/") && p.content_type !== "application/smil");
      if (hit) {
        target = c;
        break;
      }
    }
    if (!target) continue;
    const from = who(m);
    const list = byMessage.get(target.id) ?? [];
    if (r.removed) {
      const k = list.findIndex((x) => x.emoji === r.emoji && x.from === from);
      if (k >= 0) list.splice(k, 1);
    } else if (!list.some((x) => x.emoji === r.emoji && x.from === from)) {
      list.push({ emoji: r.emoji, from });
    }
    byMessage.set(target.id, list);
    hidden.add(m.id);
  }
  return { byMessage, hidden };
}
