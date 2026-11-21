import { useEffect, useRef, useState, type ClipboardEvent, type DragEvent, type KeyboardEvent } from "react";
import type { OutgoingAttachment } from "../lib/api";
import { countSegments } from "../lib/segments";
import { prepareAttachments, TooLarge } from "../lib/shrink";
import { loadDraft, saveDraft } from "../lib/drafts";
import styles from "./Composer.module.css";

interface Props {
  onSend: (body: string, attachments: OutgoingAttachment[]) => Promise<void>;
  disabled?: boolean;
  placeholder?: string;
  autoFocus?: boolean;
  /** The carrier's MMS limit when the phone can send pictures; null hides attaching. */
  mmsMaxBytes?: number | null;
  /** Turns picked files into attachments; replaceable in tests, which have no canvas. */
  prepare?: (files: File[], text: string, maxBytes: number) => Promise<OutgoingAttachment[]>;
  /** What the box starts with, for a forwarded message. */
  initialText?: string;
  initialFiles?: File[];
  /** Keeps unsent text under this key across switching conversations. */
  draftKey?: string;
}

const MAX_FILES = 10;
const ACCEPT = "image/*,video/*,audio/*,.vcf";

/** A picked file with a preview for pictures. */
interface Picked {
  file: File;
  preview: string | null;
}

/**
 * Enter sends, Shift+Enter adds a line. Shows SMS segment use as you type. When the
 * phone can send MMS, pictures and other files can be attached with the button, pasted,
 * or dropped on the box; they are shrunk to fit the carrier's limit on Send.
 */
export function Composer({ onSend, disabled, placeholder = "Text message", autoFocus, mmsMaxBytes = null, prepare = prepareAttachments, initialText = "", initialFiles, draftKey }: Props) {
  const [text, setTextState] = useState(() => initialText || (draftKey ? loadDraft(draftKey) : ""));
  const setText = (t: string) => {
    setTextState(t);
    if (draftKey) saveDraft(draftKey, t);
  };
  const [picked, setPicked] = useState<Picked[]>(() =>
    (initialFiles ?? []).slice(0, MAX_FILES).map((file) => ({ file, preview: file.type.startsWith("image/") ? URL.createObjectURL(file) : null })),
  );
  const [sending, setSending] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const info = countSegments(text);
  const canAttach = mmsMaxBytes !== null && mmsMaxBytes > 0;
  const hasContent = text.trim().length > 0 || picked.length > 0;
  const canSend = hasContent && !sending && !disabled && text.length <= 5000;

  // Previews are object URLs; let them go with the composer.
  const pickedRef = useRef(picked);
  pickedRef.current = picked;
  useEffect(() => () => pickedRef.current.forEach((p) => p.preview && URL.revokeObjectURL(p.preview)), []);

  const add = (files: File[]) => {
    if (!canAttach || files.length === 0) return;
    setProblem(null);
    setPicked((now) => {
      const room = MAX_FILES - now.length;
      if (files.length > room) setProblem(`Attach at most ${MAX_FILES} files to one message.`);
      const next = files.slice(0, Math.max(0, room)).map((file) => ({ file, preview: file.type.startsWith("image/") ? URL.createObjectURL(file) : null }));
      return [...now, ...next];
    });
  };

  const remove = (i: number) => {
    setPicked((now) => {
      const gone = now[i];
      if (gone?.preview) URL.revokeObjectURL(gone.preview);
      return now.filter((_, k) => k !== i);
    });
  };

  const submit = async () => {
    if (!canSend) return;
    if (picked.length > 0 && !canAttach) {
      setProblem("Your phone cannot send pictures right now. Remove the attachments to send the text.");
      return;
    }
    const body = text.trim().length > 0 ? text : "";
    setSending(true);
    setProblem(null);
    try {
      const attachments = picked.length > 0 && mmsMaxBytes ? await prepare(picked.map((p) => p.file), body, mmsMaxBytes) : [];
      await onSend(body, attachments);
      setText("");
      picked.forEach((p) => p.preview && URL.revokeObjectURL(p.preview));
      setPicked([]);
    } catch (e) {
      // The parent shows send errors; a file that cannot fit is said here. Nothing typed or picked is lost.
      if (e instanceof TooLarge) setProblem(e.message);
    } finally {
      setSending(false);
    }
  };

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      void submit();
    }
  };

  const onPaste = (e: ClipboardEvent<HTMLTextAreaElement>) => {
    const files = [...e.clipboardData.files];
    if (files.length > 0 && canAttach) {
      e.preventDefault();
      add(files);
    }
  };

  const onDrop = (e: DragEvent<HTMLDivElement>) => {
    setDragging(false);
    if (!canAttach) return;
    e.preventDefault();
    add([...e.dataTransfer.files]);
  };

  return (
    <div
      className={`${styles.wrap} ${dragging ? styles.dragging : ""}`}
      onDragOver={(e) => {
        if (canAttach && e.dataTransfer.types.includes("Files")) {
          e.preventDefault();
          setDragging(true);
        }
      }}
      onDragLeave={() => setDragging(false)}
      onDrop={onDrop}
    >
      {picked.length > 0 && (
        <ul className={styles.picked} aria-label="Attachments">
          {picked.map((p, i) => (
            <li key={`${p.file.name}-${i}`} className={styles.chip}>
              {p.preview ? <img className={styles.thumb} src={p.preview} alt="" /> : <span className={styles.fileName}>{p.file.name || p.file.type}</span>}
              <button className={styles.removeBtn} onClick={() => remove(i)} aria-label={`Remove ${p.file.name || "attachment"}`}>
                ×
              </button>
            </li>
          ))}
        </ul>
      )}
      {problem && (
        <div className={styles.problem} role="alert">
          {problem}
        </div>
      )}
      <div className={styles.composer}>
        {canAttach && (
          <>
            <button className={styles.attach} onClick={() => fileInput.current?.click()} disabled={disabled || sending} aria-label="Attach a picture or file" title="Attach a picture or file (or paste, or drop it here)">
              +
            </button>
            <input
              ref={fileInput}
              type="file"
              multiple
              accept={ACCEPT}
              hidden
              data-testid="attach-input"
              onChange={(e) => {
                add([...(e.target.files ?? [])]);
                e.target.value = "";
              }}
            />
          </>
        )}
        <textarea
          className={styles.input}
          rows={Math.min(6, Math.max(1, text.split("\n").length))}
          value={text}
          placeholder={picked.length > 0 ? "Add a message (optional)" : placeholder}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={onKeyDown}
          onPaste={onPaste}
          autoFocus={autoFocus}
          aria-label="Message"
        />
        <div className={styles.side}>
          <span className={styles.count} aria-label="Segments" title={info.encoding === "ucs2" ? "Contains characters that need Unicode, so segments are shorter" : "Standard SMS characters"}>
            {picked.length > 0 ? "MMS" : info.segments > 1 ? `${info.remaining} / ${info.segments}` : info.units > 0 ? `${info.remaining}` : ""}
          </span>
          <button className={styles.send} onClick={() => void submit()} disabled={!canSend} aria-label="Send">
            {sending && picked.length > 0 ? "Sending…" : "Send"}
          </button>
        </div>
      </div>
    </div>
  );
}
