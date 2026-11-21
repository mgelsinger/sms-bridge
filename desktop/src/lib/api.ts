// Typed wrappers over the Tauri commands in src-tauri/src/main.rs.
import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";

export interface Paired {
  device_name: string;
  host: string;
  port: number;
  fingerprint: string;
}

export type ConnState =
  | { state: "connecting" }
  | { state: "syncing" }
  | { state: "connected"; device_name: string }
  | { state: "offline"; reason: string; retry_in_s: number }
  | { state: "fingerprint_mismatch" }
  | { state: "unauthorized" }
  | { state: "stopped" };

export interface Snapshot {
  paired: Paired | null;
  conn: ConnState | null;
  protocol_version: number;
}

export interface DiscoveredPhone {
  name: string;
  host: string;
  port: number;
  fingerprint_prefix: string | null;
  protocol_version: string | null;
}

export interface ThreadView {
  id: number;
  participants: string[];
  title: string;
  snippet: string | null;
  date: number;
  unread: number;
  /** For a one-to-one thread whose contact has a photo: that contact's id. */
  photo_contact?: number | null;
}

export interface MmsPart {
  part_id: number;
  content_type: string;
  size: number | null;
  name: string | null;
}

export type MessageStatus = "pending" | "sent" | "delivered" | "failed";

export interface MessageView {
  id: string;
  thread_id: number;
  kind: "sms" | "mms";
  direction: "in" | "out";
  address: string;
  sender_name: string | null;
  body: string | null;
  parts: MmsPart[];
  date: number;
  status: MessageStatus | null;
  client_id: string | null;
  error: string | null;
  /** On an unsent message: attachments the phone has not stored yet. */
  pending_attachments?: number;
  /** On a scheduled send not yet gone: when it will go. */
  scheduled_for?: number | null;
}

/** An attachment on its way to the phone, as base64. */
export interface OutgoingAttachment {
  content_type: string;
  name?: string;
  data: string;
}

export interface MmsAbilities {
  send: boolean;
  max_bytes: number;
}

export interface DeviceStatus {
  device_name: string;
  battery_percent: number | null;
  charging: boolean | null;
  signal_level: number | null;
  sim_state: "ready" | "absent" | "locked" | "unknown";
  is_default_sms_app: boolean;
  uptime_s: number;
  paired_clients: number;
  /** Absent when the phone's app does not know about calls. */
  calls?: CallAbilities;
  /** Absent when the phone cannot send MMS. */
  mms?: MmsAbilities;
}

export type CallKind = "in" | "out" | "missed" | "rejected" | "blocked" | "voicemail";

/** One entry of the phone's call history. */
export interface CallView {
  id: string;
  /** Null when the other party withheld the number. */
  number: string | null;
  name: string | null;
  kind: CallKind;
  date: number;
  duration_s: number;
  seen: boolean;
  /** When the number's contact has a photo on the phone: that contact's id. */
  photo_contact: number | null;
}

/** What the phone's line is doing. It describes the line, not one call. */
export interface Line {
  state: "idle" | "ringing" | "active";
  direction: "in" | "out" | null;
  number: string | null;
  since: number;
}

/** What the phone can do with calls given the permissions it holds. */
export interface CallAbilities {
  history: boolean;
  dial: boolean;
  answer: boolean;
  end: boolean;
}

/** What became of a dial or a call action. The error is null when the phone took it. */
export interface CallResult {
  client_id: string;
  error: string | null;
}

export const api = {
  appState: () => invoke<Snapshot>("app_state"),
  discover: () => invoke<DiscoveredPhone[]>("discover"),
  probe: (host: string, port: number) => invoke<string>("probe", { host, port }),
  pair: (host: string, port: number, fingerprint: string, code: string, clientName: string) =>
    invoke<Paired>("pair", { host, port, fingerprint, code, clientName }),
  unpair: () => invoke<void>("unpair"),
  threads: () => invoke<ThreadView[]>("threads"),
  messages: (threadId: number, before?: number) => invoke<MessageView[]>("messages", { threadId, before: before ?? null }),
  loadOlder: (threadId: number) => invoke<boolean>("load_older", { threadId }),
  search: (query: string) => invoke<MessageView[]>("search", { query }),
  contacts: () => invoke<[string, string][]>("contacts"),
  /** `to` with several numbers starts a group text. */
  send: (body: string, target: { threadId: number } | { to: string | string[] }, attachments: OutgoingAttachment[] = [], sendAt?: number) =>
    invoke<void>("send", {
      threadId: "threadId" in target ? target.threadId : null,
      to: "to" in target ? (Array.isArray(target.to) ? target.to : [target.to]) : null,
      body,
      attachments: attachments.length > 0 ? attachments : null,
      sendAt: sendAt ?? null,
    }),
  mmsAbilities: () => invoke<MmsAbilities | null>("mms_abilities"),
  retry: (clientId: string) => invoke<void>("retry", { clientId }),
  discard: (clientId: string) => invoke<void>("discard", { clientId }),
  markRead: (threadId: number) => invoke<void>("mark_read", { threadId }),
  setOpenThread: (threadId: number | null) => invoke<void>("set_open_thread", { threadId }),
  reconnect: () => invoke<void>("reconnect"),
  popupsEnabled: () => invoke<boolean>("popups_enabled"),
  setPopups: (enabled: boolean) => invoke<void>("set_popups", { enabled }),
  autostartEnabled: () => invoke<boolean>("autostart_enabled"),
  setAutostart: (enabled: boolean) => invoke<void>("set_autostart", { enabled }),
  mmsPart: (partId: number) => invoke<ArrayBuffer>("mms_part", { partId }),
  contactPhoto: (contactId: number) => invoke<ArrayBuffer>("contact_photo", { contactId }),
  saveMmsPart: (partId: number, name: string | null) => invoke<string>("save_mms_part", { partId, name }),
  showInFolder: (path: string) => invoke<void>("show_in_folder", { path }),
  openLink: (url: string) => invoke<void>("open_link", { url }),
  computerName: () => invoke<string>("computer_name"),
  calls: (before?: number) => invoke<CallView[]>("calls", { before: before ?? null }),
  missedCalls: () => invoke<number>("missed_calls"),
  markCallsSeen: () => invoke<void>("mark_calls_seen"),
  line: () => invoke<Line>("line"),
  callAbilities: () => invoke<CallAbilities | null>("call_abilities"),
  /** Asks the phone to place a call. Resolves to the request id; the outcome is a callResult event. */
  dial: (to: string) => invoke<string>("dial", { to }),
  callAction: (action: "answer" | "end") => invoke<string>("call_action", { action }),
  callPopupsEnabled: () => invoke<boolean>("call_popups_enabled"),
  setCallPopups: (enabled: boolean) => invoke<void>("set_call_popups", { enabled }),
};

export const events = {
  conn: (cb: (s: ConnState | null) => void): Promise<UnlistenFn> => listen<ConnState | null>("conn-state", (e) => cb(e.payload)),
  threadsChanged: (cb: () => void): Promise<UnlistenFn> => listen("threads-changed", () => cb()),
  threadChanged: (cb: (id: number) => void): Promise<UnlistenFn> => listen<number>("thread-changed", (e) => cb(e.payload)),
  deviceStatus: (cb: (s: DeviceStatus) => void): Promise<UnlistenFn> => listen<DeviceStatus>("device-status", (e) => cb(e.payload)),
  callsChanged: (cb: () => void): Promise<UnlistenFn> => listen("calls-changed", () => cb()),
  line: (cb: (l: Line) => void): Promise<UnlistenFn> => listen<Line>("line", (e) => cb(e.payload)),
  callResult: (cb: (r: CallResult) => void): Promise<UnlistenFn> => listen<CallResult>("call-result", (e) => cb(e.payload)),
};

/** Turns Tauri command rejections (strings) and errors into one message. */
export function errorText(e: unknown): string {
  if (typeof e === "string") return e;
  if (e instanceof Error) return e.message;
  return "Something went wrong.";
}
