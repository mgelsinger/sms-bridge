# Decisions

Choices made during the build that the plan left open or that changed it.
Newest first.

## First run on the real phone, 2026-11-11

**The thread list is completed from the messages, not taken from the system's
thread index alone.** On Mickey's Pixel 9 Pro Fold with Android 17, the index
(`content://mms-sms/conversations?simple=true`) and the canonical address table
listed 30 of 42 conversations to the app: every thread created since the
Android 17 update was left out (the index has a new `read_restriction` column),
while the `sms` and `mms` rows of those threads stayed readable. The PC never
saw the newest conversations and could not reply into them. The phone now
tallies threads from the message rows and rebuilds any thread the index does
not list: participants from the SMS addresses, or for MMS-only threads from the
senders of inbound and recipients of outbound messages (the newest 20), which
never needs this phone's own number. Indexed threads are passed through
unchanged, so older Android versions behave as before. Known gap: in an
MMS-only group this phone never wrote to, members who never wrote are not
found.

**The thread list costs a fixed number of provider queries.** The first
version of the rebuild asked the provider several times per thread, on top of
two unread-count queries per thread that were already there. On the dozing
Pixel each provider query took 70 to 400 ms, so listing 43 threads took 18 to
57 seconds, the PC's 30 second request timeout fired, and the PC looped on
"Syncing" without ever reaching the live connection. The list is now built
from two scans of the message tables, two batched snippet lookups, and two
unread scans, about 8 queries whatever the thread count (measured: 0.4 to 2
seconds on the dozing phone). It is kept until a new message row is seen or a
minute has passed, and the watcher's 30 second idle poll no longer touches it.
An emulator test holds the query count down. Still per-message: the two
lookups each MMS needs when a page of history is fetched.

**The reconnect wait is capped at 15 seconds, not 60.** Mickey watched the
"retrying in 32 s" counter while the phone was already back. A retry is one
connection attempt on the LAN, so a short cap costs nothing.

**The PC fetches history for threads that are listed without any.** When the
phone starts listing a thread the PC has no messages for, the sync cursor is
already past its messages. Catch-up now fetches the first page for such
threads, as the first sync does.

**Android 17 withholds some MMS rows from the app.** 86 MMS messages in two
conversations carry `restricted = 1` and are not returned to an app that is
not the default SMS app. What marks them is not known yet. They do not show on
the PC. Not fixable from the app side as far as is known; to be researched.

**New texts put a dot on the taskbar icon; pop-ups are opt-in.** The plan had
a Windows toast for every inbound text. Mickey does not want pop-ups in the
notification area and asked for an indicator on the taskbar icon instead. The
app now shows a red overlay dot on its taskbar button while anything is
unread, and the unread count in the tray tooltip for when the window is
hidden. Pop-up notifications remain as a switch in the status menu, off by
default, for anyone who wants them. The preference is stored under a `pref:`
key that unpairing does not clear. The phase 2 gate is therefore "the text is
in the app within 2 seconds", not "a toast appears".

**Wireless debugging is how the agent reaches the phone.** Mickey has no USB
data cable. `adb pair` over Wi-Fi (he types the code) gives install and log
access; permission prompts and pairing approval stay with him.

## Emulated desk run, 2026-11-11

**A local emulator run comes before the real desk session.** Mickey asked for
proof on his own PC before spending time on the phone. The release APK
(dev-e7b1c25) ran on an Android 14 emulator on the PC, the installed Windows
build paired with it through an adb port forward, and both were driven the way
a person would: taps over adb on the phone, clicks and typing through
WebView2's DevTools port on the PC. This is the first time the real Windows
client talked to the real phone app; CI only pairs each with a stand-in. It
covers checklist sections 1, 2, 4, and 5 and the receive path of section 3. It
cannot cover real Wi-Fi and mDNS discovery, the restricted-settings step,
carrier delivery, Windows toasts, or background survival on real hardware, so
the desk checklist still closes the phase 2 and 3 gates.

**Sends are accepted while the engine is still connecting.** The engine used
to read commands only between connection attempts and while connected. A send
made during an attempt (up to 10 seconds when the phone has gone quiet) showed
no bubble and was not in the outbox until the attempt ended. The connection
attempt now runs alongside the command queue, and the outbox replay sends
whatever was queued meanwhile.

**The desktop core tests also run on Windows in CI.** They ran only on Linux,
which hid two test-harness bugs that fail on Windows: the mock phone reset the
connection instead of delivering its 401, and the mock's own pinning test
reused a TLS session, which skips the fingerprint check in Node.

## RCS, 2026-11-09

**RCS is turned off on the phone rather than bridged.** RCS messages are not
in the SMS provider, so the PC cannot see them, text or otherwise. The
alternative was a notification listener that captures incoming RCS text. It
was rejected for now: notification access exposes every app's notifications
to the bridge, it gives inbound text only with no history, and replies would
still go out as SMS. Mickey turned RCS chats off in Google Messages, accepting
the loss of RCS end-to-end encryption on those chats. Revisit only if
one-sided threads turn out to be a problem in daily use.

## Phases 2 and 3, 2026-11-09

**Hardware gates are batched into one desk session.** Phases 2 and 3 are built
and proven in CI as far as CI can go. Their gates need the real phone and PC,
so they close together with `docs/DESK-CHECKLIST.md`, and `docs/PHASE-2.md`
and `docs/PHASE-3.md` (which trigger the phase tags) are written after that
session, not before.

**Pairing without a webcam: probe, compare, then pin.** The PC completes a TLS
handshake with the phone, sends nothing, and shows the certificate fingerprint
in the same four-line layout the phone shows. The user confirms the match
before pairing, as with SSH's first connection. When the phone was found over
mDNS, its advertised fingerprint prefix is also checked automatically. The
probe still verifies that the server holds the certificate's key. This is not
a "skip verification" option: after pairing, every connection is pinned, and a
mismatch is a hard stop. Pasting the QR text skips the visual comparison
because the QR carries the full fingerprint.

**Following the phone to a new IP address.** When the phone is unreachable,
the PC browses mDNS at most every 30 seconds for a phone advertising the
paired fingerprint prefix at a different address, probes it, and moves only if
the full fingerprint matches.

**hyper instead of reqwest.** One rustls configuration with the pinning
verifier serves both REST and the WebSocket, so there is a single place where
the phone's identity is checked. hyper's HTTP/1.1 client over that TLS stream
is enough for one POST and a handful of GETs.

**The phone is authoritative for message rows.** If a message id comes back
with different content, the PC replaces the whole row instead of merging.

**Group threads are read-only on the PC.** Replying to a group needs MMS
sending, deferred to phase 5. The composer explains this instead of offering a
send that would fail.

**Sent-message matching.** Android stores each SMS sent through `SmsManager`
by a non-default app in the provider itself. The phone matches that row to the
pending send by number (last 10 digits) and exact body within 5 minutes, and
attaches the client_id, so the PC never shows a duplicate bubble.

**New dependencies**

| Dependency | Where | Why |
| --- | --- | --- |
| `tokio`, `futures-util` | desktop core | Async runtime and stream helpers for the sync engine |
| `rustls`, `tokio-rustls` (ring provider) | desktop core | TLS with a custom pinning verifier; ring avoids a C toolchain on Windows CI |
| `hyper`, `hyper-util`, `http-body-util` | desktop core | HTTP/1.1 client over the pinned TLS stream |
| `tokio-tungstenite` (handshake only) | desktop core | WebSocket client over the same stream |
| `ring` | desktop core | SHA-256 for fingerprints; already pulled in by rustls |
| `rusqlite` (bundled) | desktop core | Local message store with a pinned SQLite build |
| `mdns-sd` | desktop core | Pure-Rust mDNS browsing |
| `uuid`, `thiserror`, `log` | desktop core | client_ids, error types, logging facade |
| `keyring` | desktop app | Windows Credential Manager for the token |
| `tauri-plugin-notification` | desktop app | Windows toasts |
| `@testing-library/react`, `jsdom` (dev) | desktop UI | Component tests for the composer |

## Phase 1, 2026-11-09

**Ktor CIO behind a small TLS front.** Ktor's CIO engine is the one supported
on Android, but it has no TLS. `TlsFront` terminates TLS with the phone's
Android Keystore key on the Wi-Fi address and relays plaintext to Ktor bound
to 127.0.0.1. Nothing listens in plaintext off the device. Other apps on the
phone could reach the loopback port, but every endpoint except `/pair` still
needs a token, and pairing needs the on-screen code and the user's approval.

**A provider watcher instead of SMS broadcast receivers.** A ContentObserver
on `content://sms` and `content://mms` (plus a 30-second poll for providers
that skip notifications) sees inbound texts after the default SMS app stores
them and texts sent from the phone itself, through one code path. This drops
the `RECEIVE_SMS` and `RECEIVE_MMS` permissions from the plan.

**`READ_PHONE_STATE` is not requested.** SIM state and signal level need no
permission on API 29+. Dual-SIM selection (phase 5) will add it back.

**Foreground service type `connectedDevice`, not `dataSync`.** On Android 15
`dataSync` services are limited to 6 hours a day and cannot start from
`BOOT_COMPLETED`. `connectedDevice` has neither limit, its prerequisite
permission (`CHANGE_WIFI_MULTICAST_STATE`) is granted at install, and it
describes the situation: the PC is a device this phone serves.

**SharedPreferences, not DataStore**, for the paired-PC list and the on/off
setting. Two small values do not justify another dependency. Both are
excluded from cloud backup and device transfer.

**Dev builds are signed with a committed, public dev key**
(`android/app/dev-debug.keystore`). A random key per CI build would force an
uninstall, and a lost pairing, on every update. The cost: anyone can sign an
APK that Android accepts as an update. Only install APKs from this repo's
releases. Phase 4 moves releases to a private key kept in a GitHub secret.

**Pairing shows the fingerprint, not just a QR code.** Many desktops have no
webcam. The phone shows the 6-digit code and the certificate fingerprint in
groups of four. In phase 2 the PC can pair by typing the code and comparing
the fingerprint it sees on screen with the phone's, which resists a
man-in-the-middle as well as scanning the QR does.

**Message pages near a timestamp boundary.** Thread pages use (date, kind,
row id) keys. MMS dates have one-second resolution, so ordering between an
SMS and an MMS in the same second follows kind, not true arrival order.

**New dependencies**

| Dependency | Why the standard library is not enough |
| --- | --- |
| `ktor-server-cio`, `ktor-server-websockets` | Plan choice; HTTP routing and RFC 6455 framing are not worth hand-writing |
| `zxing:core` | Generates the pairing QR code; pure Java, no Play Services |
| `lifecycle-runtime-compose` | Collects state flows in Compose without leaking when the screen is off |
| `okhttp` (tests only) | HTTPS and WebSocket client with a custom trust manager; Android has no `java.net.http` |
| AndroidX test runner, rules, ext-junit (tests only) | Standard instrumented test stack |

## Phase 0, 2026-11-09

**Phase gates no longer pause work when CI can verify them.** Mickey asked to
avoid needing his desk until the end. Claude Code keeps going through phases
whose gates CI can prove (unit tests, emulator tests, Windows build) and stops
only when a gate needs the real phone or PC. Each phase still gets a tag and a
`PHASE-N.md` summary.

**Message ids are strings, and paging uses opaque cursors.** The plan used the
provider `_id` as a monotonic integer. Android's SMS and MMS tables have
separate, overlapping `_id` sequences, so ids are now `sms:N` / `mms:N`, and
every paging endpoint returns an opaque `next_cursor` the client passes back
unchanged. This lets the phone change its cursor encoding without a protocol
version bump.

**Added `hello` and `error` WebSocket frames.** `hello` gives the client the
phone's current sync cursor on connect, so it knows whether it missed messages.
`error` reports a rejected client frame instead of failing silently.

**Version 1 sends SMS to one recipient.** Group sends are MMS, which the plan
already defers to phase 5. The `ws-send` schema enforces one address in `to`.

**Repo is public; Android builds run only in GitHub Actions.** The workspace
Claude Code runs in cannot reach Google Maven, so the Android app is compiled
and tested in CI. CI turns compiler and test failures into annotations that are
readable through the public checks API. Public repos get Actions minutes free.

**Toolchain versions are pinned to known-good releases**: Gradle 8.11.1,
AGP 8.7.3, Kotlin 2.1.0, Compose BOM 2024.12.01, Tauri 2, React 18, Vite 6.
Dependabot proposes upgrades monthly in grouped PRs.

**JUnit 5 on Android without a third-party plugin.** Local unit tests run on
the JUnit Platform via `useJUnitPlatform()`. Instrumented tests (phase 1) use
the standard AndroidX runner.

**The mock phone binds to 127.0.0.1 by default** and ships a committed
test-only certificate. Set `MOCK_HOST` to expose it on the LAN.

## Dependencies and why

| Dependency | Where | Why the standard library is not enough |
| --- | --- | --- |
| `ajv` | repo tooling | JSON Schema 2020-12 validation; Node has none built in |
| `ws` | mock phone | Node ships a WebSocket client but no server |
| `kotlinx-serialization-json` | Android | Official Kotlin JSON with compile-time models; no reflection |
| `serde`, `serde_json` | desktop core | The standard Rust serialization pair |
| Compose, AndroidX core and activity | Android | Plan choice for the settings UI |
| `tauri`, React, Vite, TypeScript | desktop | Plan choice for the Windows app |
| `vitest` | desktop | Test runner that shares Vite's config |
