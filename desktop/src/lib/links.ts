// Finding web links in a text so they can be clicked. Pure, so it is unit tested.
// The app never fetches a link itself (no previews: the only network peer is the
// phone); a click hands it to the default browser.

export type Piece = { text: string } | { text: string; href: string };

// http(s) links, and bare www. ones. Trailing punctuation that ends a sentence is
// left out, and a closing bracket only when the link did not open one.
const LINK = /\b(?:https?:\/\/|www\.)[^\s<>"]+/giu;

function trimEnd(url: string): string {
  let u = url;
  for (;;) {
    const last = u.at(-1);
    if (last && ".,;:!?'\"".includes(last)) u = u.slice(0, -1);
    else if (last === ")" && (u.match(/\(/g)?.length ?? 0) < (u.match(/\)/g)?.length ?? 0)) u = u.slice(0, -1);
    else return u;
  }
}

export function linkify(text: string): Piece[] {
  const out: Piece[] = [];
  let at = 0;
  for (const m of text.matchAll(LINK)) {
    const start = m.index ?? 0;
    const url = trimEnd(m[0]);
    if (start > at) out.push({ text: text.slice(at, start) });
    out.push({ text: url, href: /^https?:\/\//i.test(url) ? url : `https://${url}` });
    at = start + url.length;
  }
  if (at < text.length) out.push({ text: text.slice(at) });
  return out;
}
