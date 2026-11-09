import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api, errorText, events, type ConnState, type DeviceStatus, type MessageView, type Paired, type ThreadView } from "../lib/api";
import { cleanNumber, dayLabel, listTime, sameDay } from "../lib/format";
import { Composer } from "./Composer";
import { MmsImage } from "./MmsImage";
import styles from "./Messenger.module.css";

type Selection = { kind: "thread"; id: number } | { kind: "new" } | null;

export function Messenger({ paired, conn, onUnpaired }: { paired: Paired; conn: ConnState | null; onUnpaired: () => void }) {
  const [threads, setThreads] = useState<ThreadView[]>([]);
  const [selection, setSelection] = useState<Selection>(null);
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<MessageView[] | null>(null);
  const [device, setDevice] = useState<DeviceStatus | null>(null);
  const searchRef = useRef<HTMLInputElement>(null);

  const refreshThreads = useCallback(() => {
    api.threads().then(setThreads).catch(() => {});
  }, []);

  useEffect(() => {
    refreshThreads();
    const offs = [events.threadsChanged(refreshThreads), events.deviceStatus(setDevice)];
    return () => offs.forEach((p) => void p.then((f) => f()));
  }, [refreshThreads]);

  useEffect(() => {
    const id = selection?.kind === "thread" ? selection.id : null;
    void api.setOpenThread(id);
    if (id !== null) void api.markRead(id);
  }, [selection]);

  useEffect(() => {
    if (!query.trim()) {
      setResults(null);
      return;
    }
    const t = setTimeout(() => api.search(query).then(setResults).catch(() => setResults([])), 200);
    return () => clearTimeout(t);
  }, [query]);

  const visible = useMemo(() => threads, [threads]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey && e.key.toLowerCase() === "n") {
        e.preventDefault();
        setSelection({ kind: "new" });
      } else if (e.ctrlKey && e.key.toLowerCase() === "k") {
        e.preventDefault();
        searchRef.current?.focus();
      } else if (e.ctrlKey && (e.key === "ArrowDown" || e.key === "ArrowUp")) {
        e.preventDefault();
        if (visible.length === 0) return;
        const i = selection?.kind === "thread" ? visible.findIndex((t) => t.id === selection.id) : -1;
        const next = e.key === "ArrowDown" ? Math.min(visible.length - 1, i + 1) : Math.max(0, i - 1);
        const t = visible[next];
        if (t) setSelection({ kind: "thread", id: t.id });
      } else if (e.key === "Escape" && document.activeElement === searchRef.current) {
        setQuery("");
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [visible, selection]);

  const current = selection?.kind === "thread" ? threads.find((t) => t.id === selection.id) ?? null : null;

  return (
    <div className={styles.app}>
      <aside className={styles.sidebar}>
        <div className={styles.sideTop}>
          <input ref={searchRef} className={styles.search} placeholder="Search (Ctrl+K)" value={query} onChange={(e) => setQuery(e.target.value)} aria-label="Search messages" />
          <button className={styles.newBtn} onClick={() => setSelection({ kind: "new" })} title="New message (Ctrl+N)" aria-label="New message">
            +
          </button>
        </div>
        {results ? (
          <ul className={styles.threads}>
            {results.length === 0 && <li className={styles.empty}>No messages match.</li>}
            {results.map((m) => {
              const t = threads.find((x) => x.id === m.thread_id);
              return (
                <li key={m.id}>
                  <button className={styles.thread} onClick={() => { setQuery(""); setSelection({ kind: "thread", id: m.thread_id }); }}>
                    <span className={styles.threadTop}>
                      <span className={styles.name}>{t?.title ?? m.address}</span>
                      <span className={styles.time}>{listTime(m.date)}</span>
                    </span>
                    <span className={styles.snippet}>{m.body}</span>
                  </button>
                </li>
              );
            })}
          </ul>
        ) : (
          <ul className={styles.threads}>
            {visible.length === 0 && <li className={styles.empty}>{conn?.state === "syncing" ? "Syncing your messages…" : "No conversations yet."}</li>}
            {visible.map((t) => (
              <li key={t.id}>
                <button
                  className={`${styles.thread} ${selection?.kind === "thread" && selection.id === t.id ? styles.selected : ""}`}
                  onClick={() => setSelection({ kind: "thread", id: t.id })}
                >
                  <span className={styles.threadTop}>
                    <span className={`${styles.name} ${t.unread > 0 ? styles.unreadName : ""}`}>{t.title}</span>
                    <span className={styles.time}>{listTime(t.date)}</span>
                  </span>
                  <span className={styles.threadBottom}>
                    <span className={styles.snippet}>{t.snippet ?? ""}</span>
                    {t.unread > 0 && <span className={styles.badge}>{t.unread}</span>}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
        <StatusBar paired={paired} conn={conn} device={device} onUnpaired={onUnpaired} />
      </aside>
      <section className={styles.main}>
        {selection?.kind === "new" ? (
          <NewMessage
            onSent={(threadId) => {
              refreshThreads();
              setSelection(threadId ? { kind: "thread", id: threadId } : null);
            }}
          />
        ) : current ? (
          <Conversation key={current.id} thread={current} />
        ) : (
          <div className={styles.placeholder}>Choose a conversation, or press Ctrl+N to start one.</div>
        )}
      </section>
    </div>
  );
}

function Conversation({ thread }: { thread: ThreadView }) {
  const [messages, setMessages] = useState<MessageView[]>([]);
  const [more, setMore] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const scroller = useRef<HTMLDivElement>(null);
  const loadingOlder = useRef(false);

  const load = useCallback(() => {
    api.messages(thread.id).then((m) => setMessages(m)).catch((e) => setError(errorText(e)));
  }, [thread.id]);

  useEffect(() => {
    load();
    const off = events.threadChanged((id) => {
      if (id === thread.id) {
        load();
        void api.markRead(thread.id);
      }
    });
    return () => void off.then((f) => f());
  }, [load, thread.id]);

  // Newest at the bottom; keep the view pinned there as messages arrive.
  const ordered = useMemo(() => [...messages].reverse(), [messages]);
  useEffect(() => {
    const el = scroller.current;
    if (el && !loadingOlder.current) el.scrollTop = el.scrollHeight;
  }, [ordered.length]);

  const onScroll = async () => {
    const el = scroller.current;
    if (!el || el.scrollTop > 40 || loadingOlder.current || !more) return;
    loadingOlder.current = true;
    const before = el.scrollHeight;
    try {
      const oldest = messages[messages.length - 1];
      let page = oldest ? await api.messages(thread.id, oldest.date) : [];
      if (page.length === 0) {
        const hasMore = await api.loadOlder(thread.id);
        page = oldest ? await api.messages(thread.id, oldest.date) : [];
        if (!hasMore && page.length === 0) setMore(false);
      }
      if (page.length > 0) {
        setMessages((m) => [...m, ...page.filter((p) => !m.some((x) => x.id === p.id))]);
        requestAnimationFrame(() => {
          if (scroller.current) scroller.current.scrollTop = scroller.current.scrollHeight - before;
        });
      }
    } catch (e) {
      setError(errorText(e));
    } finally {
      loadingOlder.current = false;
    }
  };

  const group = thread.participants.length > 1;

  return (
    <div className={styles.conversation}>
      <header className={styles.header}>
        <div className={styles.headerTitle}>{thread.title}</div>
        {thread.title !== thread.participants.join(", ") && <div className={styles.headerSub}>{thread.participants.join(", ")}</div>}
      </header>
      <div className={styles.messages} ref={scroller} onScroll={() => void onScroll()}>
        {!more && <div className={styles.dayLabel}>Start of conversation</div>}
        {ordered.map((m, i) => {
          const prev = ordered[i - 1];
          return (
            <div key={m.id}>
              {(!prev || !sameDay(prev.date, m.date)) && <div className={styles.dayLabel}>{dayLabel(m.date)}</div>}
              <Bubble message={m} showSender={group && m.direction === "in" && prev?.address !== m.address} onError={setError} />
            </div>
          );
        })}
      </div>
      {error && <div className={styles.error} role="alert">{error}</div>}
      {group ? (
        <div className={styles.notice}>Replying to group conversations needs MMS, which arrives in a later version.</div>
      ) : (
        <Composer
          autoFocus
          onSend={async (body) => {
            try {
              setError(null);
              await api.send(body, { threadId: thread.id });
              load();
            } catch (e) {
              setError(errorText(e));
              throw e;
            }
          }}
        />
      )}
    </div>
  );
}

function Bubble({ message: m, showSender, onError }: { message: MessageView; showSender: boolean; onError: (e: string) => void }) {
  const out = m.direction === "out";
  const time = new Date(m.date).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });
  const status = out ? statusText(m) : null;
  return (
    <div className={`${styles.bubbleRow} ${out ? styles.out : styles.in}`}>
      <div className={styles.bubbleWrap}>
        {showSender && <div className={styles.sender}>{m.sender_name ?? m.address}</div>}
        <div className={`${styles.bubble} ${out ? styles.bubbleOut : styles.bubbleIn} ${m.status === "failed" ? styles.failed : ""}`}>
          {m.parts.map((p) => (p.content_type.startsWith("image/") ? <MmsImage key={p.part_id} part={p} /> : <div key={p.part_id} className={styles.attachment}>{p.name ?? p.content_type}</div>))}
          {m.body && <div className={styles.body}>{m.body}</div>}
        </div>
        <div className={styles.meta}>
          {time}
          {status && <span className={m.status === "failed" ? styles.failedText : undefined}> · {status}</span>}
          {m.status === "failed" && m.client_id && (
            <>
              {" · "}
              <button className={styles.inlineBtn} onClick={() => api.retry(m.client_id ?? "").catch((e) => onError(errorText(e)))}>
                Retry
              </button>
              {" · "}
              <button className={styles.inlineBtn} onClick={() => api.discard(m.client_id ?? "").catch((e) => onError(errorText(e)))}>
                Discard
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

function statusText(m: MessageView): string | null {
  switch (m.status) {
    case "pending":
      return "Sending";
    case "sent":
      return "Sent";
    case "delivered":
      return "Delivered";
    case "failed":
      return m.error ? `Not sent (${m.error})` : "Not sent";
    default:
      return null;
  }
}

function NewMessage({ onSent }: { onSent: (threadId: number | null) => void }) {
  const [to, setTo] = useState("");
  const [contacts, setContacts] = useState<[string, string][]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.contacts().then(setContacts).catch(() => {});
  }, []);

  const matches = to.trim().length > 0 ? contacts.filter(([name, number]) => name.toLowerCase().includes(to.toLowerCase()) || number.includes(to.replace(/\D/g, ""))).slice(0, 8) : [];

  return (
    <div className={styles.conversation}>
      <header className={styles.header}>
        <label className={styles.toLabel}>
          To:
          <input className={styles.toInput} autoFocus value={to} onChange={(e) => setTo(e.target.value)} placeholder="Name or number" aria-label="Recipient" />
        </label>
      </header>
      <div className={styles.messages}>
        {matches.map(([name, number]) => (
          <button key={`${name}${number}`} className={styles.suggestion} onClick={() => setTo(number)}>
            <span className={styles.name}>{name}</span> <span className={styles.time}>{number}</span>
          </button>
        ))}
      </div>
      {error && <div className={styles.error} role="alert">{error}</div>}
      <Composer
        onSend={async (body) => {
          const number = cleanNumber(to);
          if (!number) {
            setError("Enter a phone number, or pick a contact.");
            throw new Error("no number");
          }
          try {
            setError(null);
            await api.send(body, { to: number });
            // The thread id is known once the phone echoes the message; find it then.
            setTimeout(async () => {
              const threads = await api.threads();
              const t = threads.find((x) => x.participants.length === 1 && x.participants[0]?.replace(/\D/g, "").endsWith(number.replace(/\D/g, "").slice(-10)));
              onSent(t?.id ?? null);
            }, 1500);
          } catch (e) {
            setError(errorText(e));
            throw e;
          }
        }}
      />
    </div>
  );
}

function StatusBar({ paired, conn, device, onUnpaired }: { paired: Paired; conn: ConnState | null; device: DeviceStatus | null; onUnpaired: () => void }) {
  const [menu, setMenu] = useState(false);
  const label = (() => {
    switch (conn?.state) {
      case "connected":
        return { text: `Connected to ${conn.device_name}`, tone: styles.ok };
      case "syncing":
        return { text: "Syncing…", tone: undefined };
      case "connecting":
        return { text: "Connecting…", tone: undefined };
      case "offline":
        return { text: `Phone unreachable, retrying in ${conn.retry_in_s} s`, tone: styles.warn };
      case "fingerprint_mismatch":
        return { text: "Phone identity changed. Pair again.", tone: styles.bad };
      case "unauthorized":
        return { text: "This PC was removed on the phone. Pair again.", tone: styles.bad };
      default:
        return { text: "Not connected", tone: styles.warn };
    }
  })();
  return (
    <footer className={styles.status}>
      <span className={`${styles.dot} ${label.tone ?? ""}`} />
      <span className={styles.statusText} title={paired.host + ":" + paired.port}>
        {label.text}
        {device?.battery_percent != null && conn?.state === "connected" && ` · ${device.battery_percent}%`}
      </span>
      <button className={styles.inlineBtn} onClick={() => setMenu((m) => !m)} aria-label="Connection options">
        •••
      </button>
      {menu && (
        <div className={styles.menu} role="menu">
          <button role="menuitem" onClick={() => { setMenu(false); void api.reconnect(); }}>Reconnect now</button>
          <button
            role="menuitem"
            onClick={() => {
              setMenu(false);
              if (window.confirm(`Unpair from ${paired.device_name}? Messages stored on this PC are deleted.`)) {
                void api.unpair().then(onUnpaired);
              }
            }}
          >
            Unpair…
          </button>
        </div>
      )}
    </footer>
  );
}
