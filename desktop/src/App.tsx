import { useEffect, useState } from "react";
import { api, events, type ConnState, type Paired } from "./lib/api";
import { useCalls } from "./lib/useCalls";
import { callsUnavailable } from "./lib/calls";
import { PairScreen } from "./components/PairScreen";
import { Messenger } from "./components/Messenger";
import { Calls } from "./components/Calls";
import { CallBanner } from "./components/CallBanner";
import styles from "./App.module.css";

export function App() {
  const [loaded, setLoaded] = useState(false);
  const [paired, setPaired] = useState<Paired | null>(null);
  const [conn, setConn] = useState<ConnState | null>(null);

  useEffect(() => {
    let alive = true;
    api
      .appState()
      .then((s) => {
        if (!alive) return;
        setPaired(s.paired);
        setConn(s.conn);
        setLoaded(true);
      })
      .catch(() => setLoaded(true));
    const off = events.conn((s) => setConn(s));
    return () => {
      alive = false;
      void off.then((f) => f());
    };
  }, []);

  if (!loaded) return <main className={styles.center}>Loading…</main>;
  if (!paired) return <PairScreen onPaired={setPaired} />;
  return (
    <PairedWindow
      paired={paired}
      conn={conn}
      onUnpaired={() => {
        setPaired(null);
        setConn(null);
      }}
    />
  );
}

type View = "texts" | "calls";

/** The window once a phone is paired: texts and calls side by side, and the call bar above both. */
function PairedWindow({ paired, conn, onUnpaired }: { paired: Paired; conn: ConnState | null; onUnpaired: () => void }) {
  const calls = useCalls(conn);
  const [view, setView] = useState<View>("texts");
  const [focusDial, setFocusDial] = useState(0);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey && e.key.toLowerCase() === "d") {
        e.preventDefault();
        setView("calls");
        setFocusDial((n) => n + 1);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  // Why a call cannot be started from a conversation right now, if it cannot.
  const blocked =
    callsUnavailable(calls.connected, calls.abilities) ??
    (calls.abilities?.dial !== true
      ? "The phone does not allow placing calls yet"
      : calls.line.state !== "idle"
        ? "The phone is already in a call"
        : calls.pending
          ? "Asking the phone…"
          : null);
  // The Calls view and the call bar show these themselves; over the texts nothing else would.
  const notice = view === "texts" && calls.line.state === "idle" ? (calls.pending ? "Asking the phone to call…" : calls.error && `Call not placed: ${calls.error}`) : null;

  return (
    <div className={styles.shell}>
      <CallBanner calls={calls} />
      {notice && (
        <div className={styles.notice} role="status">
          {notice}
        </div>
      )}
      <div className={styles.body}>
        <nav className={styles.rail} aria-label="Sections">
          <button className={view === "texts" ? styles.current : ""} aria-pressed={view === "texts"} onClick={() => setView("texts")}>
            Texts
          </button>
          <button className={view === "calls" ? styles.current : ""} aria-pressed={view === "calls"} onClick={() => setView("calls")} title="Calls (Ctrl+D)">
            Calls
            {calls.missed > 0 && (
              <span className={styles.badge} aria-label={`${calls.missed} missed`}>
                {calls.missed}
              </span>
            )}
          </button>
        </nav>
        {/* Texts stay mounted while calls are shown, so the open conversation and a half-typed message survive the switch. */}
        <div className={styles.view} hidden={view !== "texts"}>
          <Messenger paired={paired} conn={conn} onUnpaired={onUnpaired} call={{ blocked, dial: calls.dial }} />
        </div>
        {view === "calls" && (
          <div className={styles.view}>
            <Calls calls={calls} focusDial={focusDial} />
          </div>
        )}
      </div>
    </div>
  );
}
