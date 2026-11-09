// SMS segment math for the composer counter.
// GSM-7: 160 septets in one segment, 153 per segment when split.
// Anything outside GSM-7 forces UCS-2: 70 code units, 67 per segment when split.

const GSM_BASIC = new Set(
  "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?" +
    "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà",
);
// Extension table characters cost two septets (escape + char).
const GSM_EXTENDED = new Set("^{}\\[~]|€\f");

export type Encoding = "gsm7" | "ucs2";

export interface SegmentInfo {
  encoding: Encoding;
  /** Septets for GSM-7, UTF-16 code units for UCS-2. */
  units: number;
  segments: number;
  /** Units left before another segment is needed. */
  remaining: number;
}

export function countSegments(text: string): SegmentInfo {
  let septets = 0;
  let gsm = true;
  for (const ch of text) {
    if (GSM_BASIC.has(ch)) septets += 1;
    else if (GSM_EXTENDED.has(ch)) septets += 2;
    else {
      gsm = false;
      break;
    }
  }
  const [encoding, units, single, multi]: [Encoding, number, number, number] = gsm
    ? ["gsm7", septets, 160, 153]
    : ["ucs2", text.length, 70, 67];
  if (units === 0) return { encoding, units, segments: 0, remaining: single };
  if (units <= single) return { encoding, units, segments: 1, remaining: single - units };
  const segments = Math.ceil(units / multi);
  return { encoding, units, segments, remaining: segments * multi - units };
}
