// Display helpers. Pure, so they are unit tested.

/** "3502 8913 60fc 8a3f ..." in four lines of four groups, matching the phone's screen. */
export function groupFingerprint(fp: string): string[] {
  const groups = fp.match(/.{1,4}/g) ?? [];
  const lines: string[] = [];
  for (let i = 0; i < groups.length; i += 4) lines.push(groups.slice(i, i + 4).join(" "));
  return lines;
}

const DAY = 86_400_000;

/** Thread list time: "2:41 PM" today, "Yesterday", weekday within a week, else a date. */
export function listTime(ms: number, now: number = Date.now(), locale?: string): string {
  const d = new Date(ms);
  const n = new Date(now);
  const startOfToday = new Date(n.getFullYear(), n.getMonth(), n.getDate()).getTime();
  if (ms >= startOfToday) return d.toLocaleTimeString(locale, { hour: "numeric", minute: "2-digit" });
  if (ms >= startOfToday - DAY) return "Yesterday";
  if (ms >= startOfToday - 6 * DAY) return d.toLocaleDateString(locale, { weekday: "short" });
  if (d.getFullYear() === n.getFullYear()) return d.toLocaleDateString(locale, { month: "short", day: "numeric" });
  return d.toLocaleDateString(locale, { year: "numeric", month: "short", day: "numeric" });
}

/** Day separator label inside a conversation. */
export function dayLabel(ms: number, now: number = Date.now(), locale?: string): string {
  const d = new Date(ms);
  const n = new Date(now);
  const startOfToday = new Date(n.getFullYear(), n.getMonth(), n.getDate()).getTime();
  if (ms >= startOfToday) return "Today";
  if (ms >= startOfToday - DAY) return "Yesterday";
  return d.toLocaleDateString(locale, { weekday: "long", month: "long", day: "numeric", year: d.getFullYear() === n.getFullYear() ? undefined : "numeric" });
}

export function sameDay(a: number, b: number): boolean {
  const x = new Date(a);
  const y = new Date(b);
  return x.getFullYear() === y.getFullYear() && x.getMonth() === y.getMonth() && x.getDate() === y.getDate();
}

/** Accepts what people type for a phone number; keeps a leading + and digits. */
export function cleanNumber(input: string): string | null {
  const trimmed = input.trim();
  const plus = trimmed.startsWith("+");
  const digits = trimmed.replace(/\D/g, "");
  if (digits.length < 3 || digits.length > 15) return null;
  return (plus ? "+" : "") + digits;
}

/** A pasted pairing QR payload, if the text is one. */
export function parsePairingQr(text: string): { host: string; port: number; fingerprint: string; pairing_code: string; device_name: string } | null {
  try {
    const v: unknown = JSON.parse(text.trim());
    if (typeof v !== "object" || v === null) return null;
    const o = v as Record<string, unknown>;
    if (
      o.protocol_version === 1 &&
      typeof o.host === "string" &&
      typeof o.port === "number" &&
      typeof o.fingerprint === "string" &&
      /^[0-9a-f]{64}$/.test(o.fingerprint) &&
      typeof o.pairing_code === "string" &&
      /^\d{6}$/.test(o.pairing_code) &&
      typeof o.device_name === "string"
    ) {
      return { host: o.host, port: o.port, fingerprint: o.fingerprint, pairing_code: o.pairing_code, device_name: o.device_name };
    }
  } catch {
    // Not JSON.
  }
  return null;
}
