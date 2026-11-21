// Unsent text kept per conversation on this PC, so switching away loses nothing.
// Browser storage is enough: a draft is a convenience, and losing one (a private
// window, cleared site data) costs only retyping. Every access is guarded.

const PREFIX = "draft:";
const listeners = new Set<() => void>();

function storage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

export function loadDraft(key: string): string {
  try {
    return storage()?.getItem(PREFIX + key) ?? "";
  } catch {
    return "";
  }
}

export function saveDraft(key: string, text: string): void {
  try {
    const s = storage();
    if (!s) return;
    const had = s.getItem(PREFIX + key) !== null;
    if (text.trim()) s.setItem(PREFIX + key, text);
    else s.removeItem(PREFIX + key);
    // Only a draft appearing or disappearing changes the list's marks.
    if (had !== Boolean(text.trim())) listeners.forEach((f) => f());
  } catch {
    // Storage full or blocked: the draft lives only in the box.
  }
}

export function hasDraft(key: string): boolean {
  return loadDraft(key).trim().length > 0;
}

/** Called whenever a conversation gains or loses its draft. */
export function onDraftsChanged(f: () => void): () => void {
  listeners.add(f);
  return () => listeners.delete(f);
}
