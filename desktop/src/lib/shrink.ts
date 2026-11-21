// Making attachments fit in one MMS. The search for a size is pure, so it is unit
// tested with a fake encoder; the real one draws on a canvas.

import type { OutgoingAttachment } from "./api";

/** Room left in the carrier's limit for the text, headers and layout of the MMS. */
export function budgetPerAttachment(maxBytes: number, textBytes: number, count: number): number {
  const usable = Math.floor(maxBytes * 0.9) - textBytes - 4096;
  return count > 0 ? Math.max(0, Math.floor(usable / count)) : usable;
}

export type Encode = (width: number, height: number, quality: number) => Promise<Blob>;

const QUALITIES = [0.85, 0.7, 0.55, 0.4];
const START_EDGE = 1600;
const SMALLEST_EDGE = 320;

/**
 * The best JPEG of a picture that fits in [budget] bytes: the largest size first, from
 * a long edge of 1600 pixels (or the picture's own, if smaller), each size at falling
 * quality, then three quarters of the size. Null when even a small, rough copy is too big.
 */
export async function shrinkPicture(width: number, height: number, budget: number, encode: Encode): Promise<Blob | null> {
  let edge = Math.min(START_EDGE, Math.max(width, height));
  while (edge >= SMALLEST_EDGE || edge === Math.max(width, height)) {
    const scale = edge / Math.max(width, height);
    const w = Math.max(1, Math.round(width * scale));
    const h = Math.max(1, Math.round(height * scale));
    for (const q of QUALITIES) {
      const blob = await encode(w, h, q);
      if (blob.size <= budget) return blob;
    }
    if (edge <= SMALLEST_EDGE) break;
    edge = Math.max(SMALLEST_EDGE, Math.floor(edge * 0.75));
  }
  return null;
}

/** Types the phone sends untouched when they already fit. */
const SEND_AS_IS = /^(image\/(jpeg|png|gif)|video\/(mp4|3gpp)|audio\/(amr|mp4|mpeg|aac)|text\/x-vcard)$/;

export class TooLarge extends Error {}

async function toBase64(blob: Blob): Promise<string> {
  const bytes = new Uint8Array(await blob.arrayBuffer());
  let s = "";
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

/** Draws a picture file at a size and encodes it as JPEG, in the browser. */
async function canvasEncoder(file: Blob): Promise<{ width: number; height: number; encode: Encode; close: () => void }> {
  const bitmap = await createImageBitmap(file);
  return {
    width: bitmap.width,
    height: bitmap.height,
    close: () => bitmap.close(),
    encode: async (w, h, q) => {
      const canvas = new OffscreenCanvas(w, h);
      const ctx = canvas.getContext("2d");
      if (!ctx) throw new Error("no canvas");
      ctx.fillStyle = "#fff"; // transparent PNGs become white, not black, as JPEG
      ctx.fillRect(0, 0, w, h);
      ctx.drawImage(bitmap, 0, 0, w, h);
      return canvas.convertToBlob({ type: "image/jpeg", quality: q });
    },
  };
}

/**
 * Turns picked files into attachments that fit together in one MMS of [maxBytes]
 * with [text]. Pictures are shrunk when needed; anything else must already fit.
 * Throws [TooLarge] naming the file that cannot be made to fit.
 */
export async function prepareAttachments(files: File[], text: string, maxBytes: number): Promise<OutgoingAttachment[]> {
  const budget = budgetPerAttachment(maxBytes, new TextEncoder().encode(text).length, files.length);
  const out: OutgoingAttachment[] = [];
  for (const f of files) {
    const type = f.type.toLowerCase();
    if (f.size <= budget && SEND_AS_IS.test(type)) {
      out.push({ content_type: type, name: f.name || undefined, data: await toBase64(f) });
      continue;
    }
    if (!type.startsWith("image/")) throw new TooLarge(`${f.name || "That file"} is too large for a picture message (the limit is about ${Math.round(maxBytes / 1024)} KB).`);
    const enc = await canvasEncoder(f);
    try {
      const blob = await shrinkPicture(enc.width, enc.height, budget, enc.encode);
      if (!blob) throw new TooLarge(`${f.name || "That picture"} cannot be made small enough to send.`);
      const name = f.name ? f.name.replace(/\.[^.]*$/, "") + ".jpg" : undefined;
      out.push({ content_type: "image/jpeg", name, data: await toBase64(blob) });
    } finally {
      enc.close();
    }
  }
  return out;
}
