import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import styles from "./App.module.css";

export function App() {
  const [version, setVersion] = useState<number | null>(null);

  useEffect(() => {
    invoke<number>("protocol_version")
      .then(setVersion)
      .catch(() => setVersion(null));
  }, []);

  return (
    <main className={styles.shell}>
      <h1 className={styles.title}>SMS Bridge</h1>
      <p className={styles.note}>
        {version === null ? "Running outside Tauri." : `Protocol version ${version}.`} Pairing arrives in phase 2.
      </p>
    </main>
  );
}
