# Phase 1: phone read path

Gate: a paired client reads real threads over TLS, and an inbound text reaches
the WebSocket. Proven on an Android 14 emulator in CI; a check on Mickey's own
phone is optional (see "Try it on the phone").

## Built

- **Repository over the system providers**: threads with participants and
  unread counts, SMS and MMS merged per thread with opaque paging cursors,
  incremental sync by per-table row id, MMS attachments, contacts with numbers
  normalized to E.164, and device status. The phone keeps no copy of messages.
- **TLS with an Android Keystore key**: an EC P-256 key generated in Keystore,
  never exportable, with a 10-year self-signed certificate. `TlsFront`
  terminates TLS on the Wi-Fi address and relays to Ktor on 127.0.0.1.
- **Pairing**: 6-digit single-use code valid for 2 minutes, lockout after 5
  failures, an on-phone approve dialog (and a notification if the app is in
  the background), tokens stored only as SHA-256 hashes, revoke closes the
  PC's socket with 4001.
- **Live push**: a ContentObserver on the providers, plus a 30-second poll,
  publishes each new row as a `message` frame.
- **Service**: foreground service (`connectedDevice`) that follows Wi-Fi,
  advertises `_smsbridge._tcp` with the fingerprint prefix, and restarts after
  reboot or an update if it was on.
- **Settings screen**: on/off, permissions with the "Allow restricted settings"
  hint, battery exemption, pairing QR with the code and grouped fingerprint,
  paired PCs with revoke, and a recent-events log that never holds message text.
- **Dev builds**: every green `main` push publishes a prerelease with the APK
  and the Windows installer.

## Tested

| Check | Where | Result |
| --- | --- | --- |
| Core logic: cursors, paging, row mapping, pairing, lockout, tokens | JVM, CI | 21 tests pass |
| Endpoints and frames against a fixture repository, no bodies in logs | JVM, CI | 9 pass |
| Full server over real TLS and WebSocket: pinning, 401s, plaintext refused, live push, revoke 4001, version 4000 | JVM, CI | 8 pass |
| Kotlin models round-trip every protocol fixture | JVM, CI | 39 pass |
| **Pair over HTTPS with the Keystore key, receive an injected text live, read it back through sync, threads, and thread messages** | Android 14 emulator, CI | passes |

## Found and fixed

- `InetAddress.getLoopbackAddress()` is `::1` on Android and `127.0.0.1` on
  desktop JVMs, so the TLS relay pointed at an address Ktor was not on. Every
  real-phone connection would have failed. The emulator test caught it.
- The emulator test assumed a message pushed live would also be in sync from
  the `hello` cursor. A message stored right as the socket opens can be behind
  that cursor. The protocol now says so, and the test waits for a message
  newer than the cursor.

## Not yet measured

The plan's 2-second inbound latency. The watcher debounces for 300 ms after the
provider notifies, so it should be well inside 2 seconds; this gets measured on
the real phone in the phase 3 desk session.

## Try it on the phone

Optional, no PC needed. From the newest "Dev build" on the Releases page, open
the `.apk` on the phone and install it. Grant the permissions (if Android
refuses the SMS one, use App info, three-dot menu, "Allow restricted
settings"), allow background use, and tap "Turn on". It should read
"On at 192.168.x.x:8765".
