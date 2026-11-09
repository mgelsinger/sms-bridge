import { useState, type KeyboardEvent } from "react";
import { countSegments } from "../lib/segments";
import styles from "./Composer.module.css";

interface Props {
  onSend: (body: string) => Promise<void>;
  disabled?: boolean;
  placeholder?: string;
  autoFocus?: boolean;
}

/** Enter sends, Shift+Enter adds a line. Shows SMS segment use as you type. */
export function Composer({ onSend, disabled, placeholder = "Text message", autoFocus }: Props) {
  const [text, setText] = useState("");
  const [sending, setSending] = useState(false);
  const info = countSegments(text);
  const canSend = text.trim().length > 0 && !sending && !disabled && text.length <= 5000;

  const submit = async () => {
    if (!canSend) return;
    const body = text;
    setSending(true);
    try {
      await onSend(body);
      setText("");
    } catch {
      // The parent shows the error; the text stays so nothing typed is lost.
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

  return (
    <div className={styles.composer}>
      <textarea
        className={styles.input}
        rows={Math.min(6, Math.max(1, text.split("\n").length))}
        value={text}
        placeholder={placeholder}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={onKeyDown}
        autoFocus={autoFocus}
        aria-label="Message"
      />
      <div className={styles.side}>
        <span className={styles.count} aria-label="Segments" title={info.encoding === "ucs2" ? "Contains characters that need Unicode, so segments are shorter" : "Standard SMS characters"}>
          {info.segments > 1 ? `${info.remaining} / ${info.segments}` : info.units > 0 ? `${info.remaining}` : ""}
        </span>
        <button className={styles.send} onClick={() => void submit()} disabled={!canSend} aria-label="Send">
          Send
        </button>
      </div>
    </div>
  );
}
