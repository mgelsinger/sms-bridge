// Everything the window knows about calls, in one place: the phone's line, what
// the phone can do, the history, and the one request that is in flight.
import { useCallback, useEffect, useRef, useState } from "react";
import { api, errorText, events, type CallAbilities, type CallView, type ConnState, type Line } from "./api";

const IDLE: Line = { state: "idle", direction: null, number: null, since: 0 };

export interface CallsModel {
  connected: boolean;
  line: Line;
  /** When this PC learned of the line's current state, by its own clock. */
  lineSince: number;
  /** Undefined while not known, null when the phone's app has no calls. */
  abilities: CallAbilities | null | undefined;
  history: CallView[];
  missed: number;
  /** Contact names by number, for a caller who is not in the history yet. */
  names: ReadonlyMap<string, string>;
  /** True from a click until the phone says what became of it. */
  pending: boolean;
  /** Why the last request failed. Cleared by the next one. */
  error: string | null;
  /** Places a call. Only ever called from a click or a key press by the user. */
  dial: (to: string) => void;
  act: (action: "answer" | "end") => void;
  markSeen: () => void;
}

export function useCalls(conn: ConnState | null): CallsModel {
  const connected = conn?.state === "connected";
  const [line, setLine] = useState<Line>(IDLE);
  const [lineSince, setLineSince] = useState(() => Date.now());
  const [abilities, setAbilities] = useState<CallAbilities | null | undefined>(undefined);
  const [history, setHistory] = useState<CallView[]>([]);
  const [missed, setMissed] = useState(0);
  const [names, setNames] = useState<ReadonlyMap<string, string>>(new Map());
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const waitingFor = useRef<string | null>(null);
  // A result can overtake the id of the request it belongs to; it waits here.
  const early = useRef(new Map<string, string | null>());

  const refresh = useCallback(() => {
    api.calls().then(setHistory).catch(() => {});
    api.missedCalls().then(setMissed).catch(() => {});
  }, []);

  const applyLine = useCallback((next: Line) => {
    setLine((previous) => {
      if (previous.state !== next.state) setLineSince(Date.now());
      return next;
    });
  }, []);

  useEffect(() => {
    refresh();
    api.line().then(applyLine).catch(() => {});
    const loadNames = () =>
      api
        .contacts()
        .then((pairs) => setNames(new Map(pairs.map(([name, number]) => [number, name]))))
        .catch(() => {});
    loadNames();
    const offs = [
      events.callsChanged(refresh),
      events.line(applyLine),
      events.threadsChanged(loadNames),
      events.deviceStatus((status) => setAbilities(status.calls ?? null)),
      events.callResult((result) => {
        if (result.client_id !== waitingFor.current) {
          early.current.set(result.client_id, result.error);
          return;
        }
        waitingFor.current = null;
        setPending(false);
        setError(result.error);
      }),
    ];
    return () => offs.forEach((p) => void p.then((f) => f()));
  }, [refresh, applyLine]);

  // What the phone can do is asked once per connection: permissions change rarely.
  useEffect(() => {
    if (!connected) {
      setAbilities(undefined);
      return;
    }
    let alive = true;
    api
      .callAbilities()
      .then((a) => alive && setAbilities(a))
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, [connected]);

  const request = useCallback((start: () => Promise<string>) => {
    setError(null);
    setPending(true);
    early.current.clear();
    start()
      .then((clientId) => {
        const already = early.current.get(clientId);
        if (already === undefined) {
          waitingFor.current = clientId;
          return;
        }
        setPending(false);
        setError(already);
      })
      .catch((e: unknown) => {
        setPending(false);
        setError(errorText(e));
      });
  }, []);

  const dial = useCallback((to: string) => request(() => api.dial(to)), [request]);
  const act = useCallback((action: "answer" | "end") => request(() => api.callAction(action)), [request]);
  const markSeen = useCallback(() => {
    void api.markCallsSeen().catch(() => {});
  }, []);

  return { connected, line, lineSince, abilities, history, missed, names, pending, error, dial, act, markSeen };
}
