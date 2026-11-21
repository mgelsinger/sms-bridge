// Times offered for "send later". Pure, so it is unit tested.

export interface Preset {
  label: string;
  at: number;
}

/** In one hour; tonight at 8 if that is still more than half an hour away; tomorrow at 8 in the morning. */
export function presets(now: Date): Preset[] {
  const out: Preset[] = [{ label: "In 1 hour", at: now.getTime() + 3_600_000 }];
  const tonight = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 20, 0, 0, 0);
  if (tonight.getTime() - now.getTime() > 30 * 60_000) out.push({ label: "Tonight, 8:00 PM", at: tonight.getTime() });
  const morning = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, 8, 0, 0, 0);
  out.push({ label: "Tomorrow, 8:00 AM", at: morning.getTime() });
  return out;
}

/** A value for an `<input type="datetime-local">` in local time, to the minute. */
export function toLocalInput(ms: number): string {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** Reads a datetime-local value as local time; null when it is empty or not in the future. */
export function fromLocalInput(value: string, now: number): number | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(value);
  if (!m) return null;
  const [, y, mo, d, h, mi] = m.map(Number) as [number, number, number, number, number, number];
  const at = new Date(y, mo - 1, d, h, mi, 0, 0).getTime();
  return at > now ? at : null;
}

/** "Today 8:00 PM", "Tomorrow 8:00 AM", or a date. */
export function whenLabel(ms: number, now: number = Date.now(), locale?: string): string {
  const d = new Date(ms);
  const n = new Date(now);
  const time = d.toLocaleTimeString(locale, { hour: "numeric", minute: "2-digit" });
  const day = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const diff = Math.round((day(d) - day(n)) / 86_400_000);
  if (diff === 0) return `today ${time}`;
  if (diff === 1) return `tomorrow ${time}`;
  return `${d.toLocaleDateString(locale, { weekday: "short", month: "short", day: "numeric" })} ${time}`;
}
