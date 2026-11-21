// Display helpers for calls. Pure, so they are unit tested.
import type { CallAbilities, CallKind, CallView, Line } from "./api";
import { threadMatches } from "./search";

/** "0:42", "12:05", "1:02:03". */
export function callDuration(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const two = (n: number) => String(n).padStart(2, "0");
  return h > 0 ? `${h}:${two(m)}:${two(s % 60)}` : `${m}:${two(s % 60)}`;
}

/** One line about how a call went, for the history list. */
export function callSummary(kind: CallKind, durationSeconds: number): string {
  switch (kind) {
    case "in":
      return `Incoming, ${callDuration(durationSeconds)}`;
    case "out":
      // The phone cannot tell an unanswered outgoing call from a very short one.
      return durationSeconds > 0 ? `Outgoing, ${callDuration(durationSeconds)}` : "Outgoing, not answered";
    case "missed":
      return "Missed";
    case "rejected":
      return "Declined";
    case "blocked":
      return "Blocked";
    case "voicemail":
      return "Voicemail";
  }
}

export type CallFilter = "all" | "missed";

/**
 * The history entries to show for a search and a filter. The search finds a
 * call by contact name or by number the same way the texts search does; a
 * withheld number is found by neither. "Missed" counts declined calls too:
 * both are calls nobody talked on.
 */
export function filterCalls(history: CallView[], query: string, filter: CallFilter): CallView[] {
  return history.filter((c) => {
    if (filter === "missed" && c.kind !== "missed" && c.kind !== "rejected") return false;
    if (!query.trim()) return true;
    return threadMatches({ title: c.name ?? c.number ?? "", participants: c.number ? [c.number] : [] }, query);
  });
}

/** Who a call is with: the contact, else the number, else that it was withheld. */
export function callerLabel(name: string | null | undefined, number: string | null): string {
  return name ?? number ?? "Private number";
}

/**
 * What the user typed, as the number to hand to the phone: digits, a leading +,
 * and the * and # of service codes. Null when it is not something to dial.
 */
export function dialable(input: string): string | null {
  const compact = input.replace(/[\s().-]/g, "");
  return /^\+?[0-9*#]{2,32}$/.test(compact) ? compact : null;
}

/** The headline of the call bar for what the phone's line is doing. Null when idle. */
export function lineHeadline(line: Line, who: string | null): string | null {
  if (line.state === "ringing") return "Incoming call";
  if (line.state !== "active") return null;
  if (who === null) return "Call in progress on the phone";
  return line.direction === "out" ? `Call to ${who}` : `On a call with ${who}`;
}

/**
 * Why calls cannot be used right now, in words for the user. Null when they can.
 * `abilities` is undefined while unknown, null when the phone's app has no calls.
 */
export function callsUnavailable(connected: boolean, abilities: CallAbilities | null | undefined): string | null {
  if (!connected) return "Not connected to the phone. The history below is what this PC last saw.";
  if (abilities === undefined) return null;
  if (abilities === null) return "The app on the phone does not offer calls yet. Install the newest SMS Bridge there.";
  if (!abilities.history && !abilities.dial && !abilities.answer && !abilities.end) {
    return "Calls are not allowed on the phone yet. Open SMS Bridge there and tap Allow calls.";
  }
  return null;
}
