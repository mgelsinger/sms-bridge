// Which conversations a search box query names. Pure, so it is unit tested.
//
// Message text is searched by the store's FTS5 index; this covers the other two
// things the search box filters by: the contact name and the number. Names follow
// the same rule as message search (every word typed begins a word of the name, in
// any order, ignoring case and accents), so "riv al" finds "Alex Rivera". A query
// that is a phone number, written any way people write one, finds the
// conversations with a participant whose number contains those digits.

/** Lower case with accents removed, so "José" and "jose" compare equal. */
function fold(s: string): string {
  return s.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase();
}

function words(s: string): string[] {
  return fold(s).split(/[^\p{L}\p{N}]+/u).filter((w) => w.length > 0);
}

/** Digits of a query made only of digits and the marks people put in numbers, else null. */
function numberQuery(query: string): string | null {
  const t = query.trim();
  if (!/^\+?[\d\s().-]+$/.test(t)) return null;
  const digits = t.replace(/\D/g, "");
  // Two digits match nearly everything; three is where a number starts to narrow.
  return digits.length >= 3 ? digits : null;
}

export interface Searchable {
  title: string;
  participants: string[];
}

export function threadMatches(thread: Searchable, query: string): boolean {
  const digits = numberQuery(query);
  if (digits !== null && thread.participants.some((p) => p.replace(/\D/g, "").includes(digits))) return true;
  const typed = words(query);
  if (typed.length === 0) return false;
  const have = words(thread.title);
  return typed.every((w) => have.some((h) => h.startsWith(w)));
}
