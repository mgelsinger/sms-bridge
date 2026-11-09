import { useEffect, useState } from "react";
import { api, errorText, type DiscoveredPhone, type Paired } from "../lib/api";
import { groupFingerprint, parsePairingQr } from "../lib/format";
import styles from "./PairScreen.module.css";

interface Target {
  host: string;
  port: number;
  name: string;
  /** From mDNS, checked automatically against the probed fingerprint. */
  prefix: string | null;
}

type Step = { kind: "choose" } | { kind: "verify"; target: Target; fingerprint: string; fromQr: boolean; code: string };

export function PairScreen({ onPaired }: { onPaired: (p: Paired) => void }) {
  const [step, setStep] = useState<Step>({ kind: "choose" });
  const [found, setFound] = useState<DiscoveredPhone[]>([]);
  const [scanning, setScanning] = useState(false);
  const [manualHost, setManualHost] = useState("");
  const [manualPort, setManualPort] = useState("8765");
  const [pasted, setPasted] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [matches, setMatches] = useState(false);
  const [code, setCode] = useState("");
  const [clientName, setClientName] = useState("");

  const scan = () => {
    setScanning(true);
    api
      .discover()
      .then(setFound)
      .catch((e) => setError(errorText(e)))
      .finally(() => setScanning(false));
  };

  useEffect(() => {
    scan();
    api.computerName().then(setClientName).catch(() => setClientName("Windows PC"));
  }, []);

  const verify = async (target: Target) => {
    setBusy(true);
    setError(null);
    try {
      const fingerprint = await api.probe(target.host, target.port);
      if (target.prefix && !fingerprint.startsWith(target.prefix.toLowerCase())) {
        setError("The phone answering at this address is not the one that advertised itself. Do not pair; check your network.");
        return;
      }
      setMatches(false);
      setCode("");
      setStep({ kind: "verify", target, fingerprint, fromQr: false, code: "" });
    } catch (e) {
      setError(`Could not reach the phone: ${errorText(e)}`);
    } finally {
      setBusy(false);
    }
  };

  const applyPasted = () => {
    const qr = parsePairingQr(pasted);
    if (!qr) {
      setError("That is not the pairing text from the phone.");
      return;
    }
    setError(null);
    setMatches(true);
    setCode(qr.pairing_code);
    setStep({ kind: "verify", target: { host: qr.host, port: qr.port, name: qr.device_name, prefix: null }, fingerprint: qr.fingerprint, fromQr: true, code: qr.pairing_code });
  };

  const pair = async () => {
    if (step.kind !== "verify") return;
    setBusy(true);
    setError(null);
    try {
      const paired = await api.pair(step.target.host, step.target.port, step.fingerprint, code, clientName || "Windows PC");
      onPaired(paired);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className={styles.page}>
      <section className={styles.card}>
        <h1 className={styles.title}>Pair with your phone</h1>
        {step.kind === "choose" ? (
          <>
            <p className={styles.lead}>
              On the phone, open SMS Bridge, make sure it is on, and tap <b>Pair a PC</b>. Then choose it here.
            </p>
            <div className={styles.row}>
              <h2 className={styles.h2}>Phones on this network</h2>
              <button className={styles.link} onClick={scan} disabled={scanning}>
                {scanning ? "Searching…" : "Search again"}
              </button>
            </div>
            {found.length === 0 && !scanning && <p className={styles.muted}>None found yet. Check that both are on the same Wi-Fi, or enter the address below.</p>}
            <ul className={styles.list}>
              {found.map((p) => (
                <li key={`${p.host}:${p.port}`}>
                  <button className={styles.phone} disabled={busy} onClick={() => verify({ host: p.host, port: p.port, name: p.name, prefix: p.fingerprint_prefix })}>
                    <span className={styles.phoneName}>{p.name}</span>
                    <span className={styles.muted}>
                      {p.host}:{p.port}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
            <h2 className={styles.h2}>Or enter the address the phone shows</h2>
            <div className={styles.row}>
              <input className={styles.input} placeholder="192.168.1.42" value={manualHost} onChange={(e) => setManualHost(e.target.value)} aria-label="Phone address" />
              <input className={`${styles.input} ${styles.port}`} value={manualPort} onChange={(e) => setManualPort(e.target.value.replace(/\D/g, ""))} aria-label="Port" />
              <button
                className={styles.secondary}
                disabled={busy || !manualHost.trim() || !manualPort}
                onClick={() => verify({ host: manualHost.trim(), port: Number(manualPort), name: manualHost.trim(), prefix: null })}
              >
                Connect
              </button>
            </div>
            <details className={styles.details}>
              <summary>Have the pairing text from the QR code?</summary>
              <textarea className={styles.textarea} rows={3} value={pasted} onChange={(e) => setPasted(e.target.value)} placeholder='{"protocol_version":1,...}' />
              <button className={styles.secondary} onClick={applyPasted} disabled={!pasted.trim()}>
                Use it
              </button>
            </details>
          </>
        ) : (
          <>
            <p className={styles.lead}>
              {step.fromQr ? "From the QR code: " : "Connected to "}
              <b>{step.target.name}</b> at {step.target.host}:{step.target.port}.
            </p>
            {!step.fromQr && (
              <>
                <h2 className={styles.h2}>1. Compare this with the phone's screen</h2>
                <pre className={styles.fingerprint} aria-label="Certificate fingerprint">
                  {groupFingerprint(step.fingerprint).join("\n")}
                </pre>
                <label className={styles.check}>
                  <input type="checkbox" checked={matches} onChange={(e) => setMatches(e.target.checked)} />
                  Every group matches what the phone shows
                </label>
                <p className={styles.muted}>If anything differs, stop: another device may be pretending to be your phone.</p>
              </>
            )}
            <h2 className={styles.h2}>{step.fromQr ? "Pairing code" : "2. Enter the 6-digit code from the phone"}</h2>
            <input
              className={`${styles.input} ${styles.code}`}
              inputMode="numeric"
              autoFocus
              maxLength={7}
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, 6))}
              aria-label="Pairing code"
            />
            <h2 className={styles.h2}>Name for this PC</h2>
            <input className={styles.input} value={clientName} maxLength={64} onChange={(e) => setClientName(e.target.value)} aria-label="PC name" />
            <div className={styles.actions}>
              <button className={styles.secondary} onClick={() => setStep({ kind: "choose" })} disabled={busy}>
                Back
              </button>
              <button className={styles.primary} onClick={pair} disabled={busy || !matches || code.length !== 6}>
                {busy ? "Waiting for the phone…" : "Pair"}
              </button>
            </div>
            {busy && <p className={styles.muted}>Tap Allow on the phone.</p>}
          </>
        )}
        {error && (
          <p className={styles.error} role="alert">
            {error}
          </p>
        )}
      </section>
    </main>
  );
}
