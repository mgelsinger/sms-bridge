import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api, errorText, events, type ConnState, type DeviceStatus, type MessageView, type Paired, type ThreadView } from "../lib/api";
import { cleanNumber, dayLabel, listTime, sameDay } from "../lib/format";
import { threadMatches } from "../lib/search";
import { Composer } from "./Composer";
import { Attachment } from "./Attachment";
import { Avatar } from "./Avatar";
import { OFFERED, placeReactions, reactionText } from "../lib/reactions";
import { linkify } from "../lib/links";
import { hasDraft, loadDraft, onDraftsChanged } from "../lib/drafts";
import { whenLabel } from "../lib/schedule";
import styles from "./Messenger.module.css";

type Selection = { kind: "thread"; id: number } | { kind: "new"; forwarded?: Forwarded } | null;

/** A message's text and attachments, fetched from the phone, ready to send again. */
async function forwardOf(m: MessageView): Promise<Forwarded> {
  const files = await Promise.all(
    m.parts.map(async (p, i) => new File([await api.mmsPart(p.part_id)], p.name ?? `attachment-${i + 1}`, { type: p.content_type })),
  );
  return { body: m.body ?? "", files };
}

/** Calling the other person of a conversation. Absent where the window has no calls. */
export interface CallFromThread {
  /** Why a call cannot be placed right now, or null when it can. */
  blocked: string | null;
  /** Places the call. Only ever run from the user's click. */
  dial: (number: string) => void;
}

export function Messenger({ paired, conn, onUnpaired, call }: { paired: Paired; conn: ConnState | null; onUnpaired: () => void; call?: CallFromThread }) {
  const [threads, setThreads] = useState<ThreadView[]>([]);
  const [selection, setSelection] = useState<Selection>(null);
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<MessageView[] | null>(null);
  const [device, setDevice] = useState<DeviceStatus | null>(null);
  const searchRef = useRef<HTMLInputElement>(null);

  const refreshThreads = useCallback(() => {
    api.threads().then(setThreads).catch(() => {});
  }, []);

  // Redraw the list's "Draft:" marks when a conversation gains or loses one.
  const [, setDraftTick] = useState(0);
  useEffect(() => onDraftsChanged(() => setDraftTick((n) => n + 1)), []);

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

  // While searching, the list shows the conversations the query names (by contact
  // name or number) above the messages whose text matches it.
  const searching = query.trim() !== "";
  const visible = useMemo(() => (searching ? threads.filter((t) => threadMatches(t, query)) : threads), [threads, query, searching]);

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
          <input ref={searchRef} className={styles.search} placeholder="Search (Ctrl+K)" value={query} onChange={(e) => setQuery(e.target.value)} aria-label="Search names, numbers, and messages" />
          <button className={styles.newBtn} onClick={() => setSelection({ kind: "new" })} title="New message (Ctrl+N)" aria-label="New message">
            +
          </button>
        </div>
        {searching ? (
          <ul className={styles.threads} aria-label="Search results">
            {visible.length > 0 && <li className={styles.sectionLabel}>Conversations</li>}
            {visible.map((t) => (
              <ThreadRow key={t.id} thread={t} selected={selection?.kind === "thread" && selection.id === t.id} onOpen={() => { setQuery(""); setSelection({ kind: "thread", id: t.id }); }} />
            ))}
            {results && results.length > 0 && <li className={styles.sectionLabel}>Messages</li>}
            {results && results.length === 0 && visible.length === 0 && <li className={styles.empty}>Nothing matches.</li>}
            {(results ?? []).map((m) => {
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
              <ThreadRow key={t.id} thread={t} selected={selection?.kind === "thread" && selection.id === t.id} onOpen={() => setSelection({ kind: "thread", id: t.id })} />
            ))}
          </ul>
        )}
        <StatusBar paired={paired} conn={conn} device={device} onUnpaired={onUnpaired} />
      </aside>
      <section className={styles.main}>
        {selection?.kind === "new" ? (
          <NewMessage
            key={selection.forwarded ? "forward" : "new"}
            forwarded={selection.forwarded}
            onSent={(threadId) => {
              refreshThreads();
              setSelection(threadId ? { kind: "thread", id: threadId } : null);
            }}
          />
        ) : current ? (
          <Conversation
            key={current.id}
            thread={current}
            call={call}
            onForward={(m) =>
              forwardOf(m).then(
                (forwarded) => setSelection({ kind: "new", forwarded }),
                () => window.alert("The attachments could not be fetched from the phone. Try again when it is connected."),
              )
            }
          />
        ) : (
          <div className={styles.placeholder}>Choose a conversation, or press Ctrl+N to start one.</div>
        )}
      </section>
    </div>
  );
}

/**
 * The phone's MMS limit when it can send MMS, else null. Asked once when a
 * conversation opens, and again whenever the phone's status changes.
 */
function useMmsLimit(): number | null {
  const [max, setMax] = useState<number | null>(null);
  useEffect(() => {
    let alive = true;
    const ask = () =>
      api
        .mmsAbilities()
        .then((m) => alive && setMax(m?.send ? m.max_bytes : null))
        .catch(() => {});
    ask();
    const off = events.deviceStatus((s) => setMax(s.mms?.send ? s.mms.max_bytes : null));
    return () => {
      alive = false;
      void off.then((f) => f());
    };
  }, []);
  return max;
}

function ThreadRow({ thread: t, selected, onOpen }: { thread: ThreadView; selected: boolean; onOpen: () => void }) {
  return (
    <li>
      <button className={`${styles.thread} ${styles.threadRow} ${selected ? styles.selected : ""}`} onClick={onOpen}>
        <Avatar title={t.title} contactId={t.photo_contact} group={t.participants.length > 1} />
        <span className={styles.threadText}>
          <span className={styles.threadTop}>
            <span className={`${styles.name} ${t.unread > 0 ? styles.unreadName : ""}`}>{t.title}</span>
            <span className={styles.time}>{listTime(t.date)}</span>
          </span>
          <span className={styles.threadBottom}>
            {hasDraft(`thread:${t.id}`) && !selected ? (
              <span className={styles.snippet}>
                <span className={styles.draftMark}>Draft:</span> {loadDraft(`thread:${t.id}`)}
              </span>
            ) : (
              <span className={styles.snippet}>{t.snippet ?? ""}</span>
            )}
            {t.unread > 0 && <span className={styles.badge}>{t.unread}</span>}
          </span>
        </span>
      </button>
    </li>
  );
}

function Conversation({ thread, call, onForward }: { thread: ThreadView; call?: CallFromThread; onForward?: (m: MessageView) => void }) {
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
  // iPhone reactions sent as texts are shown on the message they name instead.
  const reactions = useMemo(() => placeReactions(messages, (m) => (m.direction === "out" ? "You" : m.sender_name ?? m.address)), [messages]);
  const ordered = useMemo(() => [...messages].reverse().filter((m) => !reactions.hidden.has(m.id)), [messages, reactions]);
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
  const mmsMax = useMmsLimit();

  return (
    <div className={styles.conversation}>
      <header className={styles.header}>
        <Avatar title={thread.title} contactId={thread.photo_contact} group={group} />
        <div className={styles.headerText}>
          <div className={styles.headerTitle}>{thread.title}</div>
          {thread.title !== thread.participants.join(", ") && <div className={styles.headerSub}>{thread.participants.join(", ")}</div>}
        </div>
        {call && !group && thread.participants[0] && cleanNumber(thread.participants[0]) && (
          <button
            className={styles.callBtn}
            disabled={call.blocked !== null}
            title={call.blocked ?? "Your phone places the call and you talk on the phone"}
            aria-label={`Call ${thread.title}`}
            onClick={() => call.dial(thread.participants[0]!)}
          >
            Call
          </button>
        )}
      </header>
      <div className={styles.messages} ref={scroller} onScroll={() => void onScroll()}>
        {!more && <div className={styles.dayLabel}>Start of conversation</div>}
        {ordered.map((m, i) => {
          const prev = ordered[i - 1];
          return (
            <div key={m.id}>
              {(!prev || !sameDay(prev.date, m.date)) && <div className={styles.dayLabel}>{dayLabel(m.date)}</div>}
              <Bubble
                message={m}
                showSender={group && m.direction === "in" && prev?.address !== m.address}
                reactions={reactions.byMessage.get(m.id) ?? []}
                onError={setError}
                onReact={
                  reactionText("❤️", { body: m.body, hasPicture: m.parts.length > 0 }) && (!group || mmsMax)
                    ? (emoji, remove) => {
                        const text = reactionText(emoji, { body: m.body, hasPicture: m.parts.length > 0 }, remove);
                        if (!text) return;
                        setError(null);
                        api.send(text, { threadId: thread.id }).then(load, (e) => setError(errorText(e)));
                      }
                    : undefined
                }
                onForward={onForward ? () => onForward(m) : undefined}
                onChanged={load}
              />
            </div>
          );
        })}
      </div>
      {error && <div className={styles.error} role="alert">{error}</div>}
      {group && !mmsMax ? (
        <div className={styles.notice}>Replying to a group needs your phone to send MMS, which it does not offer right now.</div>
      ) : (
        <Composer
          autoFocus
          mmsMaxBytes={mmsMax}
          draftKey={`thread:${thread.id}`}
          placeholder={group ? "Message everyone" : undefined}
          onSend={async (body, attachments, sendAt) => {
            try {
              setError(null);
              await api.send(body, { threadId: thread.id }, attachments, sendAt);
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

function Bubble({
  message: m,
  showSender,
  reactions,
  onError,
  onReact,
  onForward,
  onChanged = () => {},
}: {
  message: MessageView;
  showSender: boolean;
  reactions: { emoji: string; from: string }[];
  onError: (e: string) => void;
  /** Absent where reacting cannot be sent (the phone is not reachable, or nothing to name). */
  onReact?: (emoji: string, remove: boolean) => void;
  onForward?: () => void;
  /** After a pending send was discarded or cancelled, so the view reloads. */
  onChanged?: () => void;
}) {
  const out = m.direction === "out";
  const time = new Date(m.date).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });
  const status = out ? statusText(m) : null;
  const [picking, setPicking] = useState(false);
  const [copied, setCopied] = useState(false);
  const settled = !m.id.startsWith("outbox:");
  const mine = new Set(reactions.filter((r) => r.from === "You").map((r) => r.emoji));
  const copy = () => {
    navigator.clipboard
      .writeText(m.body ?? "")
      .then(() => {
        setCopied(true);
        setTimeout(() => setCopied(false), 1500);
      })
      .catch(() => onError("Could not copy the text."));
  };
  return (
    <div className={`${styles.bubbleRow} ${out ? styles.out : styles.in}`}>
      {settled && (
        <div className={styles.bubbleActions}>
          {onReact && (
            <button className={styles.actionBtn} onClick={() => setPicking((p) => !p)} aria-label="React" aria-expanded={picking} title="React">
              ☺
            </button>
          )}
          {m.body && (
            <button className={styles.actionBtn} onClick={copy} aria-label="Copy text" title="Copy text">
              {copied ? "Copied" : "Copy"}
            </button>
          )}
          {onForward && (
            <button className={styles.actionBtn} onClick={onForward} aria-label="Forward" title="Forward">
              Forward
            </button>
          )}
        </div>
      )}
      {picking && onReact && (
        <div className={styles.reactionPicker} role="menu" aria-label="Send a reaction">
          {OFFERED.map((e) => (
            <button
              key={e}
              role="menuitemcheckbox"
              aria-checked={mine.has(e)}
              className={`${styles.pickerBtn} ${mine.has(e) ? styles.pickerMine : ""}`}
              title={mine.has(e) ? "Take back your reaction" : "React"}
              onClick={() => {
                setPicking(false);
                onReact(e, mine.has(e));
              }}
            >
              {e}
            </button>
          ))}
        </div>
      )}
      <div className={styles.bubbleWrap}>
        {showSender && <div className={styles.sender}>{m.sender_name ?? m.address}</div>}
        <div className={`${styles.bubble} ${out ? styles.bubbleOut : styles.bubbleIn} ${m.status === "failed" ? styles.failed : ""}`}>
          {m.parts.map((p) => (
            <Attachment key={p.part_id} part={p} />
          ))}
          {(m.pending_attachments ?? 0) > 0 && (
            <div className={styles.attachment}>
              {m.pending_attachments === 1 ? "1 attachment" : `${m.pending_attachments} attachments`}
            </div>
          )}
          {m.body && (
            <div className={styles.body}>
              {linkify(m.body).map((piece, i) =>
                "href" in piece ? (
                  <a
                    key={i}
                    className={styles.link}
                    href={piece.href}
                    onClick={(e) => {
                      e.preventDefault();
                      api.openLink(piece.href).catch((err) => onError(errorText(err)));
                    }}
                  >
                    {piece.text}
                  </a>
                ) : (
                  <span key={i}>{piece.text}</span>
                ),
              )}
            </div>
          )}
        </div>
        {reactions.length > 0 && (
          <div className={styles.reactions} aria-label="Reactions">
            {reactions.map((r) => (
              <span key={r.emoji + r.from} className={styles.reaction} title={r.from}>
                {r.emoji}
              </span>
            ))}
          </div>
        )}
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
              <button className={styles.inlineBtn} onClick={() => api.discard(m.client_id ?? "").then(onChanged, (e) => onError(errorText(e)))}>
                Discard
              </button>
            </>
          )}
          {m.scheduled_for && m.client_id && (
            <>
              {" · "}
              <button className={styles.inlineBtn} onClick={() => api.discard(m.client_id ?? "").then(onChanged, (e) => onError(errorText(e)))}>
                Cancel
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
      return m.scheduled_for ? `Scheduled for ${whenLabel(m.scheduled_for)}` : "Sending";
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

/** What a forwarded message brings into a new one. */
export interface Forwarded {
  body: string;
  files: File[];
}

const last10 = (n: string) => n.replace(/\D/g, "").slice(-10);

/** The conversation whose people are exactly [numbers], compared by their last ten digits. */
export function threadFor(threads: ThreadView[], numbers: string[]): ThreadView | undefined {
  const want = new Set(numbers.map(last10));
  return threads.find((t) => t.participants.length === want.size && t.participants.every((p) => want.has(last10(p))));
}

function NewMessage({ onSent, forwarded }: { onSent: (threadId: number | null) => void; forwarded?: Forwarded }) {
  const [to, setTo] = useState("");
  const [recipients, setRecipients] = useState<{ number: string; label: string }[]>([]);
  const [contacts, setContacts] = useState<[string, string][]>([]);
  const [error, setError] = useState<string | null>(null);
  const mmsMax = useMmsLimit();

  useEffect(() => {
    api.contacts().then(setContacts).catch(() => {});
  }, []);

  const typed = to.trim();
  const matches = typed.length > 0 ? contacts.filter(([name, number]) => name.toLowerCase().includes(typed.toLowerCase()) || (/\d/.test(typed) && number.includes(typed.replace(/\D/g, "")))).slice(0, 8) : [];

  const add = (number: string, label: string) => {
    setError(null);
    setRecipients((r) => (r.some((x) => last10(x.number) === last10(number)) ? r : [...r, { number, label }]));
    setTo("");
  };

  // Enter or a comma turns what was typed into a recipient, so several can be added.
  const commit = (): boolean => {
    if (!typed) return false;
    const n = cleanNumber(typed);
    if (n) add(n, typed);
    else if (matches[0]) add(matches[0][1], matches[0][0]);
    else {
      setError("Enter a phone number, or pick a contact.");
      return false;
    }
    return true;
  };

  return (
    <div className={styles.conversation}>
      <header className={styles.header}>
        <div className={styles.toLabel}>
          To:
          <ul className={styles.recipients} aria-label="Recipients">
            {recipients.map((r) => (
              <li key={r.number} className={styles.recipient}>
                {r.label}
                <button className={styles.inlineBtn} onClick={() => setRecipients((all) => all.filter((x) => x.number !== r.number))} aria-label={`Remove ${r.label}`}>
                  ×
                </button>
              </li>
            ))}
          </ul>
          <input
            className={styles.toInput}
            autoFocus
            value={to}
            onChange={(e) => {
              const v = e.target.value;
              if (v.endsWith(",")) {
                setTo(v.slice(0, -1));
                setTimeout(() => commit(), 0);
              } else setTo(v);
            }}
            onKeyDown={(e) => {
              if (e.key === "Enter" && typed) {
                e.preventDefault();
                commit();
              } else if (e.key === "Backspace" && !to && recipients.length > 0) {
                setRecipients((r) => r.slice(0, -1));
              }
            }}
            placeholder={recipients.length > 0 ? "Add another person" : "Name or number"}
            aria-label="Recipient"
          />
        </div>
      </header>
      <div className={styles.messages}>
        {recipients.length > 1 && <div className={styles.dayLabel}>{mmsMax ? `A group text to ${recipients.length} people` : "Your phone cannot send group texts right now"}</div>}
        {matches.map(([name, number]) => (
          <button key={`${name}${number}`} className={styles.suggestion} onClick={() => add(number, name)}>
            <span className={styles.name}>{name}</span> <span className={styles.time}>{number}</span>
          </button>
        ))}
      </div>
      {error && <div className={styles.error} role="alert">{error}</div>}
      <Composer
        mmsMaxBytes={mmsMax}
        initialText={forwarded?.body}
        initialFiles={forwarded?.files}
        onSend={async (body, attachments, sendAt) => {
          const typedNumber = typed ? cleanNumber(typed) : null;
          const numbers = [...recipients.map((r) => r.number), ...(typedNumber && !recipients.some((r) => last10(r.number) === last10(typedNumber)) ? [typedNumber] : [])];
          if (numbers.length === 0) {
            setError("Enter a phone number, or pick a contact.");
            throw new Error("no number");
          }
          if (numbers.length > 1 && !mmsMax) {
            setError("Your phone cannot send group texts right now.");
            throw new Error("no mms");
          }
          try {
            setError(null);
            await api.send(body, { to: numbers }, attachments, sendAt);
            // The thread id is known once the phone echoes the message; find it then.
            setTimeout(async () => onSent(threadFor(await api.threads(), numbers)?.id ?? null), 1500);
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
  // New texts always put a dot on the taskbar icon. Pop-ups are extra and off by default.
  const [popups, setPopups] = useState(false);
  // Starting with Windows is the user's call too: off until switched on here.
  const [autostart, setAutostart] = useState(false);
  // A ringing call cannot wait for the taskbar to be looked at: its pop-up is on until switched off.
  const [callPopups, setCallPopups] = useState(true);
  useEffect(() => {
    api.popupsEnabled().then(setPopups).catch(() => {});
    api.autostartEnabled().then(setAutostart).catch(() => {});
    api.callPopupsEnabled().then(setCallPopups).catch(() => {});
  }, []);
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
            role="menuitemcheckbox"
            aria-checked={popups}
            onClick={() => {
              setMenu(false);
              const next = !popups;
              void api.setPopups(next).then(() => setPopups(next));
            }}
          >
            Pop-up notifications: {popups ? "on" : "off"}
          </button>
          <button
            role="menuitemcheckbox"
            aria-checked={callPopups}
            onClick={() => {
              setMenu(false);
              const next = !callPopups;
              void api.setCallPopups(next).then(() => setCallPopups(next));
            }}
          >
            Pop-ups for calls: {callPopups ? "on" : "off"}
          </button>
          <button
            role="menuitemcheckbox"
            aria-checked={autostart}
            onClick={() => {
              setMenu(false);
              const next = !autostart;
              api.setAutostart(next).then(() => setAutostart(next)).catch((e) => window.alert(errorText(e)));
            }}
          >
            Start with Windows: {autostart ? "on" : "off"}
          </button>
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
