import { useEffect, useState } from "react";
import { api, errorText, type MmsPart } from "../lib/api";
import { MmsImage } from "./MmsImage";
import styles from "./Messenger.module.css";

/** What a part is for showing: a picture, something to play, or a file to save. */
export function partKind(contentType: string): "image" | "video" | "audio" | "contact" | "file" {
  const ct = contentType.toLowerCase();
  if (ct.startsWith("image/")) return "image";
  if (ct.startsWith("video/")) return "video";
  if (ct.startsWith("audio/")) return "audio";
  if (ct.startsWith("text/x-vcard") || ct.startsWith("text/vcard")) return "contact";
  return "file";
}

function sizeText(bytes: number | null): string {
  if (bytes === null) return "";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

const LABEL = { image: "Picture", video: "Video", audio: "Audio", contact: "Contact card", file: "File" } as const;

/**
 * One MMS attachment. Pictures load at once, as before. Video and audio load from the
 * phone only when asked, since they can be large; every kind can be saved to Downloads.
 */
export function Attachment({ part }: { part: MmsPart }) {
  const kind = partKind(part.content_type);
  const [media, setMedia] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [note, setNote] = useState<{ text: string; path?: string } | null>(null);

  useEffect(() => () => void (media && URL.revokeObjectURL(media)), [media]);

  const play = () => {
    setLoading(true);
    api
      .mmsPart(part.part_id)
      .then((buf) => setMedia(URL.createObjectURL(new Blob([buf], { type: part.content_type }))))
      .catch(() => setNote({ text: "Not available while the phone is away" }))
      .finally(() => setLoading(false));
  };

  const save = () => {
    setNote({ text: "Saving…" });
    api
      .saveMmsPart(part.part_id, part.name)
      .then((path) => setNote({ text: `Saved to ${path.split(/[\\/]/).pop() ?? path}`, path }))
      .catch((e) => setNote({ text: `Not saved: ${errorText(e)}` }));
  };

  const label = `${LABEL[kind]}${part.name ? `: ${part.name}` : ""}${part.size ? ` (${sizeText(part.size)})` : ""}`;

  return (
    <div className={styles.attachmentBox}>
      {kind === "image" && <MmsImage part={part} />}
      {kind === "video" && (media ? <video className={styles.image} src={media} controls autoPlay aria-label={label} /> : null)}
      {kind === "audio" && (media ? <audio src={media} controls autoPlay aria-label={label} /> : null)}
      {kind !== "image" && !media && <div className={styles.attachment}>{label}</div>}
      <div className={styles.attachmentActions}>
        {(kind === "video" || kind === "audio") && !media && (
          <button className={styles.inlineBtn} onClick={play} disabled={loading}>
            {loading ? "Loading…" : "Play"}
          </button>
        )}
        <button className={styles.inlineBtn} onClick={save} aria-label={`Save ${label}`}>
          Save
        </button>
        {note && (
          <span className={styles.attachmentNote}>
            {note.text}
            {note.path && (
              <>
                {" · "}
                <button className={styles.inlineBtn} onClick={() => api.showInFolder(note.path ?? "").catch((e) => setNote({ text: errorText(e) }))}>
                  Show in folder
                </button>
              </>
            )}
          </span>
        )}
      </div>
    </div>
  );
}
