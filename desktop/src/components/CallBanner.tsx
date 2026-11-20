import { useEffect, useState } from "react";
import { callDuration, callerLabel, lineHeadline } from "../lib/calls";
import type { CallsModel } from "../lib/useCalls";
import styles from "./CallBanner.module.css";

/**
 * A bar across the top of the window while the phone is ringing or in a call,
 * whatever else is on screen. The voice is on the phone: Answer picks up there.
 */
export function CallBanner({ calls }: { calls: CallsModel }) {
  const { line, lineSince, abilities, names, pending, error } = calls;
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (line.state !== "active") return;
    setNow(Date.now());
    const tick = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(tick);
  }, [line.state, lineSince]);

  if (line.state === "idle") return null;
  const ringing = line.state === "ringing";
  // An outbound call placed on the phone itself has no number the PC can know.
  const anonymous = !ringing && line.number === null && line.direction === "out";
  const who = anonymous ? null : callerLabel(line.number ? names.get(line.number) : null, line.number);
  const headline = lineHeadline(line, who);

  return (
    <div className={`${styles.banner} ${ringing ? styles.ringing : ""}`} role="alert" aria-label="Phone call">
      <div className={styles.text}>
        <span className={styles.headline}>{headline}</span>
        {ringing && <span className={styles.who}>{who}</span>}
        {!ringing && <span className={styles.timer}>{callDuration((now - lineSince) / 1000)}</span>}
        {error && <span className={styles.error}>{error}</span>}
      </div>
      <div className={styles.actions}>
        {ringing && abilities?.answer && (
          <button className={styles.answer} disabled={pending} onClick={() => calls.act("answer")} title="Picks up on the phone. You talk on the phone.">
            Answer on phone
          </button>
        )}
        {abilities?.end && (
          <button className={styles.end} disabled={pending} onClick={() => calls.act("end")}>
            {ringing ? "Decline" : "Hang up"}
          </button>
        )}
      </div>
    </div>
  );
}
