# Phase 4: harden and polish

Gate: a week of daily use without picking up the phone to text. Passed on
2026-11-20: Mickey used the PC for texting from 2026-11-11 and said "Texting
worked perfectly. Anytime I got a new message, I was notified. I know when
things are sending, I know when things are sent." (DECISIONS.md, "Phase 4
gate").

Signed releases, which the plan put in this phase, are deferred by Mickey's
decision of 2026-11-20: dev-key builds on his own phone for now, a release key
if he ever shares the app with friends (DECISIONS.md, "Phase 4 closed").

## Built

From earlier phases and kept: start after boot, reconnect with a capped
wait, the tray, MMS images, read-only group threads, search.

- **Faster history pages**: a page of history costs three provider queries,
  plus one per MMS only in group threads, where it used to cost two per MMS
  scanned. The PC allows 90 seconds for a page of messages and keeps 30 for
  everything else. The phone logs how long each request took.
- **Watcher start race**: the phone notes where "new" begins at once, not
  after its 300 ms debounce, so a text sent in that gap is not lost.
- **Start with Windows**: opt-in, in the status menu, stored as
  `pref:autostart`. One Run-key value, quoted, written directly rather than
  through `tauri-plugin-autostart`. A start at login stays in the tray.
- **One copy at a time**: starting the app again shows the copy already
  running (`tauri-plugin-single-instance`).
- **FTS5 search** with a schema migration for existing stores: whole words
  and word beginnings, any order, case and accents ignored.
- **Search by contact name and number**: the conversations a query names are
  listed above the message hits.

## Tested

| Check | Where | Result |
| --- | --- | --- |
| A page of picture messages costs a fixed number of queries | Android 14 emulator, CI | 3 for a one-to-one page, 8 for a group page of 5 |
| A page of 91 MMS from an old conversation | real phone, 2026-11-20 | 513 ms on the phone, 774 ms on the PC |
| Pages may take longer than other requests | Rust tests against a slow mock phone, CI | pass |
| Registry round trip of the Start with Windows entry | Windows CI | pass |
| Start with Windows off by default, no Run entry written | real PC, 2026-11-20 | as designed |
| A second start leaves one copy running and shows its window | real PC, 2026-11-20 | one process, window shown |
| FTS5 index follows inserts, updates and deletes; version 1 store migrates with its messages | Rust tests, CI | pass |
| Store upgrade on the real store; search speed | real PC, 2026-11-20 | upgraded in place, answers in under 5 ms |
| Search by name and number in the real window | real PC, 2026-11-20 | found by three letters of a surname and by a number typed as (xxx) xxx-xxxx |
| **A week of daily texting** | real phone and PC, 2026-11-11 to 20 | passed, Mickey's words above |

## Found and fixed on the way

- The watcher start race above, found by CI in an existing send test.
- `tauri-plugin-autostart` writes the exe path unquoted, and the install
  folder has a space in it; replaced by a direct registry write.

## Not done, by decision

- **Signed APK and installer.** Dev builds use the public dev key and the
  installer is unsigned. Mickey on 2026-11-20: unsigned releases on his own
  phone, and signing if he wants to share the app with friends. What signing
  needs is in STATUS.md, "Signed releases".
- **An Android 17 emulator job in CI.** Tried, not added: no Android 17 image
  behaves like his phone (DECISIONS.md, "Phase 4").
- **Dependency migrations** (Gradle 9 with Kotlin 2.4, React 19 with
  TypeScript 7). Dependabot's red PRs were closed; do them by hand, one
  ecosystem at a time.

## Known limits

- The tray-only start at login has not been seen on the real PC: switch Start
  with Windows on, sign out and in, and the app should be in the tray with no
  window.
- Uninstalling leaves the Start with Windows entry behind.
- Search no longer finds text in the middle of a word.
