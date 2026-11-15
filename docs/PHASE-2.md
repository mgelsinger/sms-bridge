# Phase 2: PC read path

Gate: a fresh install pairs, pulls the history, and shows a live inbound text
with the phone across the room. Passed on Mickey's own hardware: a Pixel 9 Pro
Fold on Android 17 and his Windows 11 PC, between 2026-11-11 and 2026-11-14,
on dev build dev-5e591cf. The plan's gate said "toast"; Mickey chose a taskbar
dot instead (see DECISIONS.md), and that is what was confirmed.

## Built

- **Core library** (`desktop/core`, builds and tests on any OS): the protocol
  models, a pinned-certificate TLS client for REST and the WebSocket, mDNS
  discovery, the SQLite store, and the sync engine.
- **Pairing without a webcam**: the PC finds the phone over mDNS (or takes a
  typed address or the pasted QR text), completes a TLS handshake without
  sending anything, and shows the certificate fingerprint for the user to
  compare with the phone before the 6-digit code is used. After pairing every
  connection is pinned; a mismatch is a hard stop.
- **Sync**: first sync pulls every thread and its newest 100 messages, older
  history loads on demand, incremental sync resumes from a stored cursor, and
  live frames are applied as they arrive. Threads that show up without
  history are fetched on the next catch-up.
- **Window**: thread list with contact names and unread counts, conversation
  view with MMS images, search, status line with reconnect and unpair.
- **Attention**: a red dot on the taskbar icon while anything is unread, the
  unread count in the tray tooltip, and pop-up notifications as an opt-in
  switch. Closing the window keeps the app in the tray.
- **Token storage** in Windows Credential Manager; messages in a local SQLite
  file; nothing leaves the LAN.

## Tested

| Check | Where | Result |
| --- | --- | --- |
| Store, sync policy, attention logic, protocol fixtures | Rust unit tests, CI on Linux and Windows | 19 pass |
| Client against the mock phone: pair, read, pin mismatch, 401, probe | Rust integration, CI | 4 pass |
| Sync engine against the mock phone: first sync, live inbound, reconnect, unfetched threads, revoked token | Rust integration, CI | 4 pass |
| Composer, formatting, SMS segment counting | Vitest, CI | 18 pass |
| Installed Windows build against the release APK on an Android 14 emulator, driven like a user | Mickey's PC, 2026-11-11 | pairing, sync, inbound in 0.6 s |
| **Fresh install on the real phone, permissions, turn on** | Pixel 9 Pro Fold, Android 17 | passes |
| **PC finds the phone by itself, fingerprints match, pairs, first sync** | real phone and PC | passes, about 19 s for 30 threads |
| **Live inbound with the phone dozing** | real phone and PC | in the PC's store 1.0 s after the phone stored it |
| **Texts arriving overnight, phone idle for hours, taskbar dot** | real phone and PC | confirmed by Mickey on 2026-11-14 |

## Found and fixed on the way

- The core tests had only ever run on Linux. On Windows two mock-phone bugs
  surfaced; CI now runs the core tests on Windows too.
- **Android 17 hides threads created since the update** from the thread index
  for apps that are not the default SMS app. The PC showed 30 of 42
  conversations. The phone now completes the list from the messages.
- That rebuild first cost several provider queries per thread; on the dozing
  phone listing 43 threads took up to 57 seconds and the PC timed out in a
  loop. It now costs about 8 queries in total and takes about a second.
- "Allow background use" stayed on screen after it was accepted.

## Known limits

- Android 17 withholds 86 MMS messages flagged `restricted` (two
  conversations) from the app. They do not show on the PC.
- Android 17 holds some verification-code texts back from apps like this one
  for three hours.
- Fetching a page of history costs two provider lookups per MMS, which is
  slow on a dozing phone. A first sync of a large MMS-heavy history takes
  minutes and a single page could pass the PC's 30 second timeout.
- A text sent to one's own number does not come back as inbound on Mickey's
  carrier, so it cannot be used to test receiving.
