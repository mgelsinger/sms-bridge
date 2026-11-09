import { useEffect, useState } from "react";
import { api, type MmsPart } from "../lib/api";
import styles from "./Messenger.module.css";

/** Loads an MMS image from the phone on demand and shows it from a blob URL. */
export function MmsImage({ part }: { part: MmsPart }) {
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let revoke: string | null = null;
    let alive = true;
    api
      .mmsPart(part.part_id)
      .then((buf) => {
        if (!alive) return;
        revoke = URL.createObjectURL(new Blob([buf], { type: part.content_type }));
        setUrl(revoke);
      })
      .catch(() => alive && setFailed(true));
    return () => {
      alive = false;
      if (revoke) URL.revokeObjectURL(revoke);
    };
  }, [part.part_id, part.content_type]);

  if (failed) return <div className={styles.attachment}>Image not available while the phone is away</div>;
  if (!url) return <div className={styles.attachment}>Loading image…</div>;
  return <img className={styles.image} src={url} alt={part.name ?? "Attachment"} onClick={() => window.open(url)} />;
}
