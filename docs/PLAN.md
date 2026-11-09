# SMS Bridge: Android to Windows Build Plan

Nov 8, 2026 - Mickey

Build a self-hosted bridge that lets a Windows 11 PC send, receive, and reply to SMS and MMS from the phone number on an Android phone, over the local network, with no cloud relay and no Microsoft or Google account in the loop. Working name: **sms-bridge**. Hand this document to Claude Code as the project brief; the CLAUDE.md in the repo root holds the working rules.

## Goal and constraints

The phone stays the source of truth. The Windows app is a remote keyboard and screen for the phone's SMS store, with its own local cache so history survives the phone leaving the room.

Must do:

- Show every conversation thread on the phone with full history, contact names, and read state
- Receive new messages on the PC within 2 seconds of the phone receiving them, with a Windows notification
- Send new messages and replies from the PC through the phone's SIM, including multipart SMS
- Show received MMS images and group threads (sending MMS is a later phase)
- Work on any Wi-Fi network where both devices can see each other; optionally over a WireGuard or Tailscale tunnel
- Run unattended: phone app survives reboot, Doze, and app standby; PC app lives in the tray and reconnects on its own

Must never do:

- Send message content, contacts, or metadata to any third-party server, including crash reporting or analytics
- Depend on Google Play Services, Firebase, or a Microsoft account
- Accept connections without pairing, or accept plaintext connections
- Require root or a custom ROM

Targets: Android 10 (API 29) and up on the phone; Windows 11 on the PC. Sideload the APK with adb; Play Store distribution is out of scope because Play policy blocks SMS permissions for apps that are not the default SMS handler.

## Architecture

Two apps and one protocol. The Android app is a foreground service that reads and writes the system SMS store and serves it over HTTPS and WebSocket on the LAN. The Windows app is a Tauri 2 desktop client that keeps a SQLite mirror and renders it.

```
Android phone (sms-bridge app)                 Windows 11 PC (Tauri 2 app)
+-----------------------------------+          +-----------------------------------+
| Android SMS/MMS provider          |          | Rust core                         |
|   content://sms, content://mms    |          |   WebSocket client, auto-reconnect|
+----------------^------------------+          |   Incremental sync by id cursor   |
                 |                              |   Outbox with retry, client_id    |
+----------------v------------------+  Wi-Fi   |   Toast notifications, tray       |
| Foreground service                | <------> +----------------^------------------+
|   SMS_RECEIVED + ContentObserver  |  TLS,    | SQLite cache                      |
|   SmsManager send + intents       |  pinned  |   threads, messages, contacts,    |
|   Ktor: HTTPS REST + WebSocket    |  cert,   |   attachments                     |
|   mDNS advert + QR pairing        |  bearer  +----------------^------------------+
+----------------+------------------+  token   | React + TypeScript UI             |
| Settings screen                   |          |   thread list, conversation,      |
|   pair QR, clients, revoke, status|          |   composer, search                |
+-----------------------------------+          +-----------------------------------+
```

All traffic is phone-to-PC over the local network; nothing leaves the LAN unless the user adds their own VPN tunnel.

Stack decisions, already made:

| Component | Choice | Why |
| --- | --- | --- |
| Android language | Kotlin, min SDK 29, target latest stable | Modern Telephony APIs, coroutines for the server |
| Android server | Ktor embedded (CIO engine) with TLS | Lightweight, Kotlin-native, WebSocket built in |
| Android persistence | DataStore for settings, no copy of messages | The system provider is the store; the app only holds pairing state |
| Discovery | Android NSD (mDNS) advertising `_smsbridge._tcp` | Zero-config on LAN; client falls back to manual host:port |
| Desktop shell | Tauri 2, Rust backend | Small binary, native tray and notifications, no Electron overhead |
| Desktop UI | React + TypeScript + Vite | Fast to iterate |
| Desktop storage | SQLite via rusqlite (bundled) | Local history that outlives the connection |
| Desktop networking | tokio-tungstenite + reqwest with pinned cert | Async WebSocket, custom cert verification |
| Wire format | JSON, versioned with `protocol_version` | Readable in logs, easy to test by hand |
| License | MIT | Build in public |

## Repo layout and tooling

One monorepo, `mgelsinger/sms-bridge`, three top-level trees plus a shared protocol spec that both sides test against.

```
sms-bridge/
  CLAUDE.md                 # working rules for Claude Code
  README.md
  LICENSE                   # MIT
  docs/
    PLAN.md                 # this file
  protocol/
    PROTOCOL.md             # the wire spec, source of truth
    schema/*.json           # JSON Schema per message type, used by both test suites
    fixtures/*.json         # golden request/response pairs
  android/
    app/                    # Kotlin app module
    gradle/, build.gradle.kts, settings.gradle.kts
  desktop/
    src-tauri/              # Rust: networking, SQLite, tray, notifications
    src/                    # React + TypeScript UI
    package.json, tauri.conf.json
  tools/
    mock-phone/             # fake phone server for desktop dev without a device
    scripts/                # adb install, emulator SMS injection, release packaging
  .github/workflows/        # android-build.yml, desktop-build.yml, protocol-check.yml
```

Toolchain the dev box needs: Android Studio (or command-line SDK + JDK 17), adb, Rust stable with the MSVC toolchain on Windows, Node 20+, and the Tauri 2 prerequisites (WebView2 is already on Windows 11). The Android side can be built on either the Windows PC or a Linux box; the desktop side builds on Windows.

## Protocol spec

One HTTPS port on the phone (default 8765) carries REST for bulk reads and a WebSocket for live events and sends. Every JSON body carries `protocol_version: 1`. Both sides reject a major version they do not know.

**Pairing** happens once per PC:

1. Phone generates a self-signed ECDSA P-256 certificate on first run and keeps it in Android Keystore. It shows a QR code containing `{host, port, cert_sha256, pairing_code}` where `pairing_code` is a 6-digit one-time code valid for 2 minutes.
2. PC scans the QR (webcam) or the user types the fields. PC connects over TLS, verifies the certificate fingerprint matches `cert_sha256`, and calls `POST /pair` with the code and a client name.
3. Phone shows a confirm dialog with the client name. On accept it returns a 32-byte random bearer token. PC stores the token and fingerprint in the Windows Credential Manager.
4. All later calls carry `Authorization: Bearer <token>`. Phone lists paired clients and can revoke any token from the settings screen.

**REST endpoints** (all require the bearer token except `/pair`):

| Method and path | Returns |
| --- | --- |
| `POST /pair` | `{token, device_name, protocol_version}` |
| `GET /threads?limit=&before=` | Thread summaries: id, participants, snippet, last date, unread count |
| `GET /threads/{id}/messages?limit=&before_id=` | Messages in a thread, newest first, paged by message id |
| `GET /messages?since_id=` | All messages with id greater than cursor, for incremental sync |
| `GET /contacts` | Name and normalized E.164 numbers for every contact with a phone |
| `GET /mms/{part_id}` | Raw bytes of an MMS attachment with its content type |
| `GET /status` | Battery, signal, SIM state, server uptime, paired client count |

**WebSocket** at `/ws`, JSON frames, one object per frame:

| Direction | Type | Payload |
| --- | --- | --- |
| PC to phone | `send` | `{client_id, thread_id or to[], body}` |
| PC to phone | `mark_read` | `{thread_id}` (best effort, see limitations) |
| PC to phone | `ping` | `{}` every 30 s; phone replies `pong` |
| Phone to PC | `message` | A full message object: new inbound, or outbound sent from the phone itself |
| Phone to PC | `send_result` | `{client_id, message_id, status: queued or sent or delivered or failed, error?}` |
| Phone to PC | `thread_updated` | `{thread_id}` when read state or snippet changed on the phone |
| Phone to PC | `status` | Same shape as `GET /status`, pushed on change |

**Message object**, shared by REST and WebSocket:

```
{
  "id": 48213,                 // provider _id, monotonic per phone
  "thread_id": 917,
  "kind": "sms" | "mms",
  "direction": "in" | "out",
  "address": "+15855550123",  // E.164 where possible
  "participants": ["+1..."],  // group MMS
  "body": "text or null",
  "parts": [{"part_id": 77, "content_type": "image/jpeg", "size": 182334}],
  "date": 1762550400000,       // epoch ms, date received or sent
  "read": true,
  "status": "sent" | "delivered" | "failed" | null,
  "client_id": "uuid or null"  // echoes the PC's send so it can dedupe
}
```

Idempotency: the PC generates `client_id` for every send and keeps an outbox row until `send_result` arrives. A reconnect replays unresolved outbox rows; the phone ignores a `client_id` it has already seen.

## Android app

The app is a thin server over the system SMS store. It does not keep its own copy of messages and it is not the default SMS app in phase 1.

**Permissions** (request at runtime, explain each on the settings screen): `READ_SMS`, `RECEIVE_SMS`, `SEND_SMS`, `RECEIVE_MMS`, `READ_CONTACTS`, `READ_PHONE_STATE` (for SIM and signal in `/status`), `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_WIFI_MULTICAST_STATE` (for mDNS). Ask the user to exempt the app from battery optimization and explain why.

**Reading history.** Query `content://sms` for SMS (columns `_id, thread_id, address, body, date, date_sent, read, type, status`) and `content://mms` plus `content://mms/{id}/part` and `content://mms/{id}/addr` for MMS. Thread summaries come from `content://mms-sms/conversations?simple=true`. Normalize addresses to E.164 with libphonenumber using the SIM country. Join contact names from `ContactsContract.PhoneLookup` and cache the lookup in memory.

**Receiving.** Register a `BroadcastReceiver` for `android.provider.Telephony.SMS_RECEIVED` and `WAP_PUSH_RECEIVED` (MMS). Non-default apps get these broadcasts after the default app has written the message to the provider, so on receipt wait briefly, then read the new row from the provider and push a `message` frame. Also attach a `ContentObserver` on `content://sms` and `content://mms` so messages sent from the phone itself reach the PC too. Dedupe by provider `_id`.

**Sending.** Use `SmsManager` from `context.getSystemService(SmsManager::class.java)` (or the subscription-specific one when the phone has two SIMs). Split with `divideMessage` and send with `sendMultipartTextMessage`, passing `sentIntent` and `deliveryIntent` PendingIntents per part. Map the result codes to `send_result` statuses. Since Android 4.4 the system writes messages sent by non-default apps into the provider, so the `ContentObserver` will see the new row; match it to the pending `client_id` by address, body, and timestamp window, then emit `message` with `client_id` set.

**Service.** A foreground service started on boot (`RECEIVE_BOOT_COMPLETED`) with a persistent low-priority notification showing connection state. It owns the Ktor server, the NSD registration, and a `WifiManager.MulticastLock` while advertising. Hold no wake lock between requests; rely on the foreground service and the battery exemption. Stop advertising on mobile data and resume on Wi-Fi.

**TLS.** Generate the key pair in Android Keystore on first run, build a self-signed certificate valid 10 years, and serve it through Ktor's `sslConnector`. Expose the SHA-256 fingerprint on the settings screen and in the pairing QR.

**Settings screen** (Jetpack Compose, single activity): permission checklist with fix buttons, pair button that shows the QR for 2 minutes, list of paired clients with last-seen time and a revoke button, server port, and a log viewer for the last 200 events.

## Windows client

The client is a Tauri 2 app. Rust owns networking and storage; the web view only renders state and sends commands.

**Rust core** (`src-tauri`):

- Discovery: browse `_smsbridge._tcp` with `mdns-sd`; fall back to the last known host:port and a manual entry field
- Connection: `tokio-tungstenite` over `rustls` with a custom `ServerCertVerifier` that accepts only the pinned fingerprint; exponential backoff from 1 s to 60 s; `ping` every 30 s
- Sync: on connect, `GET /messages?since_id=<max local id>`; on first run, page `GET /threads` then each thread's last 200 messages, and backfill older pages in the background
- Storage: `rusqlite` with WAL mode. Tables `threads`, `messages`, `contacts`, `parts` (attachment bytes cached on disk under the app data dir), `outbox`, `meta` (cursor, fingerprint, device name). Token lives in Windows Credential Manager via the `keyring` crate, not in SQLite
- Outbox: insert a row before emitting `send`; mark resolved on `send_result`; replay unresolved rows on reconnect
- Notifications: `tauri-plugin-notification` for inbound messages with the contact name and body; clicking focuses the thread. Suppress when the thread is open and the window is focused
- Tray: `tauri` tray icon with connection state, Show, Pause notifications, Quit. Close hides to tray; start at login via `tauri-plugin-autostart`

**UI** (React + TypeScript): three-pane layout at desktop width, two-pane when narrow.

- Thread list: contact name or number, snippet, relative time, unread badge, sorted by last activity; search box filters by name, number, and body (SQLite FTS5 on `messages.body`)
- Conversation view: bubbles by direction, date separators, delivery state on outbound (clock, check, double check, red for failed with a retry button), inline MMS images with click-to-open
- Composer: multi-line textarea, Enter sends and Shift+Enter inserts a newline, character and segment counter (160 GSM-7 or 70 UCS-2 per segment), paste of a phone number starts a new thread
- New message: contact picker with typeahead over the synced contacts
- Status bar: phone name, battery, signal, last sync time, connection state
- Keyboard: Ctrl+N new message, Ctrl+K search, Ctrl+Up/Down switch threads, Esc clears search

Theme follows the Windows light/dark setting. Keep the UI dependency list short: React, a small state store (Zustand), and plain CSS modules. No component library.

## Security and privacy requirements

The threat model is a hostile device on the same Wi-Fi. Anyone on the LAN can see the mDNS advert and reach the port, so the server must give them nothing without pairing.

- TLS on every connection; the phone's certificate is self-signed and the PC pins its SHA-256 fingerprint. A fingerprint mismatch is a hard failure with a visible error, never a prompt to continue
- Pairing code is 6 digits, single use, expires in 2 minutes, and the phone shows a confirm dialog naming the client. Rate limit `/pair` to 5 attempts per 10 minutes and lock pairing for 10 minutes after that
- Bearer tokens are 32 random bytes from `SecureRandom`, stored hashed (SHA-256) on the phone and in Windows Credential Manager on the PC. Revoking a token on the phone closes its WebSocket immediately
- The server binds only to the Wi-Fi interface address, never `0.0.0.0`, and stops when Wi-Fi drops
- No message content in logs on either side at the default log level; a debug level that includes bodies must be an explicit toggle that resets on restart
- The PC SQLite file sits under `%LOCALAPPDATA%\sms-bridge` with default NTFS permissions; offer an optional SQLCipher build in a later phase rather than blocking on it now
- Dependencies: pin versions, run `cargo audit` and the Gradle dependency-check plugin in CI, and keep the dependency count small enough to review by hand
- Nothing phones home: no update checks, no telemetry, no crash upload. Updates are a GitHub release the user installs
- Document the threat model and these controls in `SECURITY.md` so the repo reads like something a SOC 2 reviewer would accept

## Phased delivery

Each phase ends in a tagged commit and a working demo. Claude Code should finish a phase, run its acceptance checks, and stop for review before starting the next.

| Phase | Scope | Gate to pass before the next phase |
| --- | --- | --- |
| 0 Scaffold | Repo + CI, protocol spec, mock phone | Schemas pass |
| 1 Phone read | Service, pairing, REST read API, WS push inbound | curl reads a thread |
| 2 PC read | Tauri + pairing, SQLite sync, threads + view, toasts | Live toast on PC |
| 3 Send + reply | SmsManager send, outbox + dedupe, composer, delivery state | Daily driver |
| 4 Harden | Doze + reconnect, MMS + groups, search + tray, signed releases | One week of daily use |

**Phase 0, scaffold.** Monorepo with the layout above, CI that builds both apps and validates `protocol/fixtures` against `protocol/schema`, and `tools/mock-phone` serving the fixtures over the real protocol so desktop work never waits on the phone. Accept when CI is green and `mock-phone` answers `GET /threads` with fixture data.

**Phase 1, phone read path.** Foreground service, Keystore certificate, pairing flow, all REST read endpoints, WebSocket `message` push from the broadcast receiver and content observer. Accept when `curl --cacert` against the phone returns real threads, and an inbound text appears on a `websocat` session within 2 seconds.

**Phase 2, PC read path.** Tauri shell, QR or manual pairing, pinned TLS, SQLite schema and incremental sync, thread list and conversation view, toast on inbound. Accept when a fresh install pairs, pulls full history, and shows a toast for a live inbound text with the phone across the room.

**Phase 3, send and reply.** `SmsManager` sending with sent and delivered intents, outbox with `client_id` dedupe, composer, new-message flow, delivery state in the bubble. Accept when a message typed on the PC arrives on another phone, shows delivered on the PC, appears in the phone's own messaging app, and a forced disconnect mid-send produces exactly one copy after reconnect.

**Phase 4, harden and polish.** Boot start, Doze survival test (phone idle 1 hour, inbound still pushes), reconnect backoff, MMS image parts and group threads, FTS5 search, tray and autostart, signed APK and Windows installer via GitHub Releases. Accept when the user runs it as the daily driver for a week without touching the phone to text.

## Testing strategy

Test the protocol once, in the middle, and both apps against it. Phone-side telephony is tested on the emulator, which can inject SMS without a SIM.

| Layer | How | Runs where |
| --- | --- | --- |
| Protocol | JSON Schema validation of every fixture; both apps load the same fixtures in their unit tests | CI on every push |
| Android unit | JUnit 5 + MockK for address normalization, provider row mapping, token hashing, pairing state machine | CI (JVM, no device) |
| Android instrumented | Espresso on an API 34 emulator; inject texts with `adb emu sms send <number> <text>`; assert the WebSocket frame arrives | CI with the Android emulator runner |
| Desktop Rust | `cargo test` for sync cursor logic, outbox replay, cert pinning (reject wrong fingerprint), SQLite migrations | CI (Windows runner) |
| Desktop UI | Vitest + React Testing Library for thread list, composer segment counter, keyboard shortcuts | CI |
| End to end | `tools/mock-phone` plus a Playwright script driving the Tauri window: pair, sync, send, receive | CI, and locally before each phase gate |
| Real device | A written checklist per phase: two phones, one PC, airplane-mode toggles, Doze via `adb shell dumpsys deviceidle force-idle` | Manual, at each gate |

Non-negotiable checks before any phase is called done: the dedupe test (disconnect during send, reconnect, exactly one message) and the pinning test (a server with a different certificate is refused with a clear error).

## Known limitations and deferred items

These are consequences of not being the default SMS app. Each has a workaround or a later phase.

- **Mark as read on the phone.** Only the default SMS app may write `read=1` to the provider. Phase 1 to 4: track read state locally on the PC and show the phone's state as read-only. Phase 5 option: a "default SMS app" mode that implements the required `SMS_DELIVER`, `WAP_PUSH_DELIVER`, `RESPOND_VIA_MESSAGE`, and `SENDTO` components, which also makes receive faster and gives full MMS control
- **Sending MMS.** `SmsManager.sendMultimediaMessage` needs a hand-built PDU and carrier APN settings. Defer to phase 5; until then the composer rejects attachments with a clear message
- **RCS.** Google Messages keeps RCS in its own store, not the SMS provider. Out of scope; RCS chats will not appear. Say so in the README so nobody files it as a bug
- **Dual SIM.** Phase 1 uses the default SMS subscription. Phase 5 adds a SIM picker in the composer using `SubscriptionManager`
- **Off-LAN access.** Not built in. Document a Tailscale or WireGuard setup where the PC reaches the phone's tunnel address; the pinned cert and bearer token already make this safe
- **OEM battery killers.** Samsung, Xiaomi, and OnePlus can still kill foreground services. Link dontkillmyapp.com in the setup screen and detect the manufacturer to show the right steps
- **Delivery reports.** Only arrive if the carrier supports them; show `sent` as the terminal state when no report comes within 60 seconds
- **Encrypted PC cache.** Plain SQLite in phase 4; SQLCipher as an opt-in build later
