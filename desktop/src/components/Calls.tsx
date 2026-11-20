import { useEffect, useRef, useState } from "react";
import { callSummary, callerLabel, callsUnavailable, dialable } from "../lib/calls";
import { listTime } from "../lib/format";
import type { CallsModel } from "../lib/useCalls";
import styles from "./Calls.module.css";

const KEYS = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"];

/**
 * The call history and a dial pad. A call is placed by the phone, and only
 * because the user clicked Call or pressed Enter here; the voice stays on the
 * phone.
 */
export function Calls({ calls, focusDial }: { calls: CallsModel; focusDial: number }) {
  const { history, abilities, connected, line, pending, error, missed, markSeen } = calls;
  const [number, setNumber] = useState("");
  const input = useRef<HTMLInputElement>(null);

  // Looking at the history is what makes a missed call seen.
  useEffect(() => {
    if (missed > 0) markSeen();
  }, [missed, markSeen]);

  useEffect(() => {
    input.current?.focus();
  }, [focusDial]);

  const unavailable = callsUnavailable(connected, abilities);
  const busy = line.state !== "idle";
  const canDial = connected && abilities?.dial === true && !busy && !pending;
  const whyNot = !connected
    ? "Not connected to the phone"
    : abilities?.dial !== true
      ? "The phone does not allow placing calls yet"
      : busy
        ? "The phone is already in a call"
        : undefined;
  const target = dialable(number);

  const place = (to: string | null) => {
    if (to && canDial) calls.dial(to);
  };

  return (
    <div className={styles.calls}>
      <section className={styles.history} aria-label="Call history">
        <h2 className={styles.title}>Calls</h2>
        {unavailable && <p className={styles.notice}>{unavailable}</p>}
        {connected && abilities && !abilities.history && (abilities.dial || abilities.end) && (
          <p className={styles.notice}>The phone does not share its call history. Allow it in SMS Bridge on the phone to see calls here.</p>
        )}
        <ul className={styles.list}>
          {history.length === 0 && <li className={styles.empty}>No calls yet.</li>}
          {history.map((c) => {
            const who = callerLabel(c.name, c.number);
            return (
              <li key={c.id} className={styles.row}>
                <button className={styles.entry} onClick={() => c.number && setNumber(c.number)} disabled={!c.number} title={c.number ? "Put this number in the dial pad" : undefined}>
                  <span className={`${styles.who} ${c.kind === "missed" ? styles.missed : ""}`}>{who}</span>
                  <span className={styles.detail}>
                    {callSummary(c.kind, c.duration_s)}
                    {c.name && c.number ? ` · ${c.number}` : ""}
                  </span>
                </button>
                <span className={styles.time}>{listTime(c.date)}</span>
                {c.number && (
                  <button className={styles.callBack} disabled={!canDial} title={whyNot} aria-label={`Call ${who}`} onClick={() => place(c.number)}>
                    Call
                  </button>
                )}
              </li>
            );
          })}
        </ul>
      </section>
      <section className={styles.pad} aria-label="Dial pad">
        <input
          ref={input}
          className={styles.number}
          value={number}
          onChange={(e) => setNumber(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") place(target);
          }}
          placeholder="Number (Ctrl+D)"
          aria-label="Number to call"
          inputMode="tel"
        />
        <div className={styles.keys}>
          {KEYS.map((k) => (
            <button key={k} onClick={() => setNumber((n) => n + k)} aria-label={`Digit ${k}`}>
              {k}
            </button>
          ))}
        </div>
        <button className={styles.call} disabled={!canDial || !target} title={whyNot} onClick={() => place(target)}>
          Call
        </button>
        <p className={styles.status} role="status">
          {pending ? "Asking the phone…" : (error ?? "")}
        </p>
        <p className={styles.hint}>Your phone places the call and you talk on the phone. Emergency numbers are dialed on the phone itself.</p>
      </section>
    </div>
  );
}
