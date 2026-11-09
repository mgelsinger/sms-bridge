import { useEffect, useState } from "react";
import { api, events, type ConnState, type Paired } from "./lib/api";
import { PairScreen } from "./components/PairScreen";
import { Messenger } from "./components/Messenger";
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
    <Messenger
      paired={paired}
      conn={conn}
      onUnpaired={() => {
        setPaired(null);
        setConn(null);
      }}
    />
  );
}
