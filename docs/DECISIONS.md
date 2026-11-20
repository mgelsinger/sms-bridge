# Decisions

Choices made during the build that the plan left open or that changed it.
Newest first.

## Release choices, 2026-11-20

Asked of Mickey on 2026-11-20; his answers.

**The Android release key: not now.** Dev-key builds continue. Phase 4 stays
open on this one item; nothing else in it is waiting.

**Dev and release builds share one app id.** His words: "This doesn't
really release yet anybody but me, so we can probably stick with just one
app." The cost, accepted: going from a dev build to a release build or back
means uninstalling, granting the permissions again, and pairing again.

**The Windows installer stays unsigned.** SmartScreen warns once per new
build. Revisit if the friend install path makes the warning a problem.

**A release is a tag he pushes, such as `v0.1.0`.** CI builds both apps from
it and publishes a GitHub release that is not a prerelease. Dev builds keep
coming from every green push to `main`. Not built yet: without the key, the
release APK could only carry the public dev key, which is what a release
exists to avoid.

## Phase 4 gate, 2026-11-20

**The week of daily use passed.** Mickey used the PC for texting from
2026-11-11 to 2026-11-20 and on 2026-11-20 said: "Texting worked perfectly.
Anytime I got a new message, I was notified. I know when things are sending,
I know when things are sent." He did not report reaching for the phone to
text. Phase 4 stays open only for signed releases, which need his key and
his decisions (STATUS.md, "Signed releases").

**Search finds conversations by contact name and number, in the window, not
the store.** The plan has the search box filter "by name, number, and body".
Body was the FTS5 index; names and numbers are matched against the thread
list the window already holds, so there is no new command and no store
change. While a query is typed, the conversations it names are listed above
the message hits. Names follow the message rule (every typed word begins a
word of the name, any order, case and accents ignored); a query made only of
digits and the marks people put in numbers (`+ ( ) - .` and spaces) with at
least three digits finds every conversation with a participant whose number
contains those digits, so "(585) 555-0101", "5855550101" and "0101" all find
the same one. Contacts with no conversation are not listed; the new-message
screen already suggests those.

## Phase 6 build, 2026-11-20

Built the same day Mickey asked for calls, at his word: "complete everything
else you possibly can before me reaching my desk". Phase 5 is optional and was
skipped over. What needs his phone is in CALLS-DESK.md.

**The protocol describes the phone's line, not calls.** An app that is not
the dialer learns three things from Android: idle, ringing, off the hook, and
who is ringing. It does not learn when an outbound call is answered, and it
does not see a held call. So `call_state` carries exactly that, and the PC
does not pretend to more: an outbound call is "active" from the moment it is
dialed, and a second call during a call shows as ringing, then active.
Per-call objects with ids were the alternative, and would have been made up.

**One action, `end`, both declines and hangs up.** It is the same call on the
phone (`TelecomManager.endCall`), and the PC labels the button by what the
line is doing. There is no silence action: that needs the dialer role.

**The phone reads the line from the phone-state broadcast.** It works from
Android 10 up and carries the caller's number; the newer `TelephonyCallback`
carries no number at all. `READ_PHONE_STATE` therefore comes back, having been
dropped in phase 1. Android delivers a ringing call twice, with and without
the number, in either order; the tracker merges them.

**Calls are four optional permissions, asked for from their own card.**
`READ_PHONE_STATE`, `READ_CALL_LOG`, `CALL_PHONE`, `ANSWER_PHONE_CALLS`. None
is in the list the Setup card insists on, so a phone that never allows calls
carries texts exactly as before and tells the PC so in `status.calls`. The
card sits below Paired PCs: above it, it pushed pairing off the first screen,
which an existing UI test caught.

**Each call request is acted on once, on both sides.** The phone remembers
`client_id` values for 24 hours and answers a repeat without acting. The PC
never queues a dial or a call action: with no connection it fails at once,
with no answer in 10 seconds it fails, and a lost connection fails whatever
was waiting and reports the line idle so a ringing call is never left on
screen. A request that failed that way may still have reached the phone; the
call bar then shows the truth.

**The phone decides what an emergency number is.** `TelephonyManager
.isEmergencyNumber` knows the SIM's country; the PC does not. If the phone
cannot answer, a short fixed list (911, 112, 999 and a few more) is refused
anyway. The PC shows the refusal and never dials around it.

**The PC keeps every call it has seen.** It syncs the whole call log on first
connect and from its cursor after that. An entry deleted on the phone stays on
the PC, as PROTOCOL.md says. History from that first sync is marked seen;
a call missed later counts on the taskbar dot and the tray tooltip until the
Calls view is opened.

**Call pop-ups are on by default, unlike text pop-ups.** A text waits for the
taskbar dot to be noticed; a ringing phone does not. A ringing or missed call
raises a Windows pop-up unless the window is in front. "Pop-ups for calls" in
the status menu switches them off (`pref:call_popups_off`). This departs from
what Mickey chose for texts and he has not been asked: flip the default if he
says so.

**The window gains a rail: Texts and Calls.** The call bar sits above both,
so a call shows whatever is on screen. The answer button says "Answer on
phone" until phase 7 can carry the voice. Ctrl+D opens the dial pad. A
one-to-one conversation has a Call button in its header.

**Emulator call tests ask the host for calls through logcat.** A call into an
emulator starts with `adb emu gsm call` on the host, and a test needs it at a
moment of its own choosing. It writes `ring <number>` or `hangup <number>`
under the tag `SmsBridgeCue`, and `tools/scripts/ci-emulator-test.sh` acts on
it. On CI's Android 14 emulator (run of 2026-11-20) this proved, against real telephony: a call in rings with its number, is answered from the client, and lands in the history; a ringing call is declined and logged as rejected; a call out is placed once however often the request is repeated, is ended from the client, and is logged.

**A call is left alone for the first 1.5 seconds it rings.** The first run of
the decline test ended a call 136 ms into the ring and the emulator's system
process died: Android 14's Telecom stops a ringtone it has not finished
loading (`NullPointerException` in `AsyncRingtonePlayer.handlePlay`). No
person clicks that fast, but a request can arrive that fast, so the phone app
waits out the rest of the 1.5 seconds before it answers or ends, and then
acts only if the call still rings. Whether Android 17 has the same fault is
not known; the wait costs nothing either way.

**`tools/calls-probe` uses Microsoft's `windows` crate.** It reaches
`Windows.ApplicationModel.Calls`, which neither the standard library nor the
`windows-registry` crate the app already uses covers. The probe stands apart
from both apps; if phase 7 goes ahead, the desktop core will need the same
crate, already in its lock file through Tauri.

## Calls, 2026-11-20

**Phone calls are in scope, as phases 6 and 7.** Mickey asked for it on
2026-11-20: place and receive calls and texts from the PC, "just the standard
functions of the phone", "the more functionality the better", Android and
Windows only. PLAN.md has the detail under "Calls". No code yet.

**Two phases, because the voice cannot use the bridge.** An Android app that
is not part of the system cannot read or write call audio, default phone app
or not. Phase 6 carries what the bridge can: who is calling, the call history,
and dial, answer, decline, hang up, with the voice staying on the phone.
Phase 7 makes the PC the phone's Bluetooth hands-free device through Windows'
own calling API, which is how the voice reaches the PC. Relaying audio through
the phone's speaker and microphone was not considered further.

**Bluetooth is accepted as a second link to the phone.** It is paired in
Windows Settings and protected by Bluetooth's own encryption, not by the
pinned certificate. It reaches only the paired phone, so the rule "no network
calls to anything but the paired phone" holds. Mickey has not been asked about
this point on its own; raise it when phase 7 starts.

**An experiment comes before either phase.** Whether an app installed like
ours may use `PhoneLineTransportDevice` on a real paired phone is not known.
Checked on this PC on 2026-11-20, without the phone: the calling classes load
and answer from an unpackaged process (device selector and call store both
fine), and the PC has an Intel Bluetooth radio with no phone paired to it. The
rest needs the phone paired over Bluetooth and Mickey placing a call.

**The app does not become the default phone app.** Same reasoning as for SMS.
The price is that the ringer cannot be silenced from the PC, and that answering
and hanging up rest on two `TelecomManager` calls deprecated since Android 10,
to be checked on Android 17 in the experiment.

**A call is never retried.** A text that fails to reach the phone waits in the
outbox and is replayed. A `dial` is not: placing a call minutes late, when the
link comes back, is worse than not placing it.

**The agent does not place or answer calls on Mickey's phone**, by extension
of what he said about texts from his number. Emulator only.

## Phase 4, 2026-11-14

**A page of history costs three provider queries, plus one per MMS only in
group threads.** It used to cost two per MMS scanned, and a page scans 50 rows
more than it returns. Now the page is chosen from the two table scans first,
the parts of every MMS on it come from one query, and address rows are read
only for the MMS on the page. Android has no way to read the address rows of
several messages at once, so those stay one query each. They are skipped
altogether in a one-to-one thread, where the other party is the sender of
every inbound message and the recipient of every outbound one. That shortcut
is not taken for a thread whose member list was guessed from a few MMS
(Android 17, MMS-only, not in the system's index), because the guess can miss
someone. Catch-up sync reads parts and addresses only for the rows that fit
the page. Measured on the emulator: 3 queries for a one-to-one page, 8 for a
group page of 5 MMS. Not measured on the real phone yet.

**Pages of messages may take 90 seconds on the PC; everything else stays at
30.** What is left is one address lookup per group MMS at up to 400 ms on a
dozing phone: about 40 seconds for a first-sync page of 100, about 85 for a
catch-up page of 200. 90 covers both. The cost is that a phone which vanishes
in the middle of a page is noticed after 90 seconds, not 30, and only for that
request; connecting still gives up after 10. Smaller pages were the
alternative, and would have made every sync slower to save a rare wait.

**Starting with Windows is a switch in the status menu, off by default.**
"Start with Windows" sits under "Pop-up notifications" and is stored as
`pref:autostart`, which unpairing does not clear. Switching it on writes one
value, `SMS Bridge`, under the current user's Run key:
`"<path to the exe>" --autostart`. A start with that argument stays in the
tray and opens no window. The menu shows what Windows will really do: it reads
"off" if the entry is missing, points at another copy, or was switched off
under Startup apps in Settings or Task Manager. At startup the entry is
repaired towards the stored choice (removed if the choice is off, rewritten if
the app moved), but an entry the user switched off in Windows itself is left
alone until they switch it on in the app again. Development builds never
register themselves. Known gap: uninstalling leaves the Run value behind;
Windows ignores an entry whose program is gone.

**The Run key is written directly, not through `tauri-plugin-autostart`.**
The plan named the plugin. It writes the program path without quotes, and
this app installs under `...\SMS Bridge\`, a folder with a space in its name;
it also tries the all-users key first. Writing the one value ourselves needs
only Microsoft's `windows-registry` crate, which the plugin uses underneath
anyway, and lets the Windows CI job test the real round trip under a
throwaway name.

**Only one copy of the app runs.** With the app starting in the tray,
opening it from the Start menu is the normal way to see the window, and that
used to start a second copy syncing beside the first. A second start now
shows the first copy's window and exits. The window is created hidden and
shown by the app once it has decided whether this is a start at login.

**Search matches words and word beginnings, not any run of letters.** The
LIKE scan found "inner" inside "dinner". The FTS5 index (SQLite's own, in the
bundled build; no new dependency) finds a message when it has every word
typed, whole or as the beginning of a word, in any order, ignoring case and
accents: "fri din" finds "Dinner on Friday?", "cafe" finds "CAFÉ", and
"inner" no longer finds "dinner". That is how people type into a search box,
and it needs no fallback for one- and two-letter queries, which the
alternative (the trigram tokenizer, which keeps substring matching) does.
Results stay newest first, not ranked. Everything typed is quoted before it
reaches FTS5, so quotes, stars, AND and the like are text, never syntax.

**The store has numbered schema steps now.** Version 2 adds the index and
fills it from the messages already stored, in one transaction, the first
time the new build opens the file. The index keeps no second copy of the
text and is kept in step by triggers on the messages table. An older build
can still open a version 2 file: it ignores the index and the triggers keep
working, because every build bundles the same SQLite.

**No Android 17 emulator job in CI yet.** The plan was to run the emulator
tests on API 37 so the Android 17 thread-index behaviour is exercised for
real. Five CI runs on a draft PR (#10) showed that no image available on
2026-11-14 does what the Pixel does:

| Image | What happened |
| --- | --- |
| any, with the runner's default device | never comes online; needs a Pixel hardware profile, 4 GB of RAM and an 8 GB disk |
| `37.0 default` (AOSP) | runs; the system's index lists the thread of an inbound text, so the index gap is not reproduced; texts the app sends are stored with `read_restriction = 65536`, with their threads and addresses, and hidden from the app; creating a thread id is refused |
| `37.0 google_apis`, `37.2 google_apis_ps16k` | Google Messages is the SMS app, as on the phone, but the emulated system crashes during the first test that sends a text |
| `37.0 google_atd` | has no SMS app, so injected texts are never stored |

A job on the AOSP image would pass one useful test and need three others
switched off for reasons that are the image's, not the app's. That is not
proof of anything on the phone, so it was left out. The API 34 job, the JVM
tests of the merge logic, and the phone itself remain the evidence.

**The watcher records where "new" begins at once.** Found through CI: it
waited out its 300 ms debounce first, and a text sent in that gap had its
stored copy treated as old, never pushed and never tied to its `client_id`.
The first scan now runs immediately, and the emulator tests wait for it.

**Dependabot's grouped PRs were closed, not merged.** Both had red CI and
both were migrations (Gradle 9 with Kotlin 2.4, React 19 with TypeScript 7),
which do not belong in a week whose gate is unchanged daily use. STATUS.md
lists them as open work.

**New dependencies**

| Dependency | Where | Why |
| --- | --- | --- |
| `windows-registry` (Windows only) | desktop core | Reads and writes the Run key value; the standard library has no registry access |
| `tauri-plugin-single-instance` | desktop app | Detects a running copy and hands it the second start; doing that by hand means a named mutex plus a message channel between processes |

## Phase 3 gate, 2026-11-14

**The disconnect-mid-send check is accepted on emulator evidence.** The plan's
phase 3 gate includes "a forced disconnect mid-send produces exactly one copy
after reconnect". Mickey will not run that on his real phone (checklist
section 5), and said so plainly. The behaviour was exercised three times
with the installed Windows build against the release APK on the emulator: the
bridge unreachable during a send, the phone's traffic silently dropped for 70
seconds, and a send made while the PC was mid-reconnect. Each time one copy
was sent. It is also covered by the sync engine test that replays the outbox
after a reconnect and by the phone's replay test for a repeated `client_id`.
That is taken as passing the gate. Section 5 stays in the checklist as
optional. Do not ask him to run it again.

**"Delivered" is not part of the gate on his carrier.** Every text sent from
the PC on the real phone ended at "Sent"; the carrier returns no delivery
reports. The emulator shows the "Delivered" transition works when a report
arrives.

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
| `windows` | calls-probe only | Microsoft's bindings for `Windows.ApplicationModel.Calls`, the Bluetooth hands-free calling API; nothing else reaches it |
