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
  send: (body: string, target: { threadId: number } | { to: string }) =>
    invoke<void>("send", {
      threadId: "threadId" in target ? target.threadId : null,
      to: "to" in target ? target.to : null,
      body,
    }),
  retry: (clientId: string) => invoke<void>("retry", { clientId }),
  discard: (clientId: string) => invoke<void>("discard", { clientId }),
  markRead: (threadId: number) => invoke<void>("mark_read", { threadId }),
  setOpenThread: (threadId: number | null) => invoke<void>("set_open_thread", { threadId }),
  reconnect: () => invoke<void>("reconnect"),
  popupsEnabled: () => invoke<boolean>("popups_enabled"),
  setPopups: (enabled: boolean) => invoke<void>("set_popups", { enabled }),
  mmsPart: (partId: number) => invoke<ArrayBuffer>("mms_part", { partId }),
  computerName: () => invoke<string>("computer_name"),
};

export const events = {
  conn: (cb: (s: ConnState | null) => void): Promise<UnlistenFn> => listen<ConnState | null>("conn-state", (e) => cb(e.payload)),
  threadsChanged: (cb: () => void): Promise<UnlistenFn> => listen("threads-changed", () => cb()),
  threadChanged: (cb: (id: number) => void): Promise<UnlistenFn> => listen<number>("thread-changed", (e) => cb(e.payload)),
  deviceStatus: (cb: (s: DeviceStatus) => void): Promise<UnlistenFn> => listen<DeviceStatus>("device-status", (e) => cb(e.payload)),
};

/** Turns Tauri command rejections (strings) and errors into one message. */
export function errorText(e: unknown): string {
  if (typeof e === "string") return e;
  if (e instanceof Error) return e.message;
  return "Something went wrong.";
}
