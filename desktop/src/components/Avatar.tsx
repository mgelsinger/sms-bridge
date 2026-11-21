import { useEffect, useState } from "react";
import { api } from "../lib/api";
import styles from "./Messenger.module.css";

/** Photos fetched this session, by contact id, so the list asks the phone once each. */
const photos = new Map<number, Promise<string | null>>();

function photoUrl(contactId: number): Promise<string | null> {
  let p = photos.get(contactId);
  if (!p) {
    p = api
      .contactPhoto(contactId)
      .then((buf) => URL.createObjectURL(new Blob([buf])))
      .catch(() => {
        photos.delete(contactId); // Try again later, e.g. once the phone is back.
        return null;
      });
    photos.set(contactId, p);
  }
  return p;
}

/** Up to two letters from a name; "#" for a number or anything without letters. */
export function initials(title: string): string {
  const words = title.split(/[\s,]+/).filter((w) => /^\p{L}/u.test(w));
  if (words.length === 0) return "#";
  const letters = words.length === 1 ? [words[0]] : [words[0], words[words.length - 1]];
  return letters.map((w) => [...(w ?? "")][0]?.toUpperCase() ?? "").join("");
}

/** The contact's photo when the phone has one, else their initials. */
export function Avatar({ title, contactId, group }: { title: string; contactId: number | null | undefined; group?: boolean }) {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    let alive = true;
    setUrl(null);
    if (contactId != null) void photoUrl(contactId).then((u) => alive && setUrl(u));
    return () => {
      alive = false;
    };
  }, [contactId]);
  if (url) return <img className={styles.avatar} src={url} alt="" />;
  return (
    <span className={`${styles.avatar} ${styles.avatarLetters}`} aria-hidden="true">
      {group ? "👥" : initials(title)}
    </span>
  );
}
