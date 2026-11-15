# Status

Read this first in a new session. Update it whenever the state below changes.

Last updated: 2026-11-14.

## Where things stand

- Phases 0 to 3 are done and tagged. Phases 2 and 3 passed on Mickey's own
  hardware (Pixel 9 Pro Fold, Android 17, and his Windows PC); results and
  what was found on the way are in [PHASE-2.md](PHASE-2.md) and
  [PHASE-3.md](PHASE-3.md).
- Running on both the phone and the PC since 2026-11-11: dev build
  dev-5e591cf, paired, and in ordinary daily use. Texts arrive overnight with
  the taskbar dot; texts sent from the PC go out once.
- Mickey will not run the Wi-Fi-drop-mid-send check on his real phone. It is
  accepted on emulator evidence (DECISIONS.md, "Phase 3 gate"). Do not ask
  again.
- Next is phase 4 (harden) from [PLAN.md](PLAN.md). Its gate is a week of
  daily use, which started in effect on 2026-11-11. Already in place from
  earlier phases: start after boot, reconnect with a capped wait, tray, MMS
  images, read-only group threads, search. Not started: autostart on Windows,
  FTS5 search, signed APK and installer, and the open items below.
- The agent reaches the phone over adb Wireless debugging, which Mickey turns
  on and pairs himself. Reading the app's status and installing builds is
  fine; permission prompts, pairing approval, and sending texts are his.
- If a checklist step fails: fix it, add a regression test, and re-run the
  step on a new dev build.
- Testing lesson from 2026-11-11: an established TCP connection is not proof
  the PC is connected. Check for the "Connected" state, or for `/threads` and
  `/contacts` in the phone's log with no `WS closed` after them.

## How this repo is worked

- Every green push to `main` publishes a prerelease named `dev-<sha>` with the
  APK and the Windows installer. Install from those, not from local builds,
  unless you are debugging.
- Android cannot always be built where the agent runs. CI builds it, runs JVM
  tests, and runs `EmulatorEndToEndTest` on an API 34 emulator. CI failures
  are posted as check-run annotations (`tools/ci/annotate.mjs`,
  `tools/ci/junit-summary.mjs`), readable with
  `gh api repos/mgelsinger/sms-bridge/commits/<sha>/check-runs`.
- The desktop core (`desktop/core`) builds and tests on any OS. Its
  integration tests start `tools/mock-phone`, so run `npm ci` at the repo
  root first. The Tauri shell (`desktop/src-tauri`) needs Windows or the
  Linux webkit libraries.
- [DECISIONS.md](DECISIONS.md) records every choice that departs from or
  fills in the plan, newest first.

## Open items

- Dependabot PRs #6 (npm) and #7 (Gradle) are open and unreviewed. Handle
  them after the desk session.
- RCS is off on Mickey's phone by decision (see DECISIONS.md).
- Android 17 withholds 86 MMS rows flagged `restricted` from the app (two
  conversations). Find out what the flag means. Google's Android 17 behaviour
  change pages (read 2026-11-11) do not mention it, the `read_restriction`
  thread column, or MMS at all.
- Android 17 "SMS OTP protection": verification-code texts in WebOTP or SMS
  Retriever format are withheld from apps other than the intended recipient
  for three hours, broadcast and provider queries both, for all apps. Such
  codes will reach the PC three hours late. For apps targeting API 37 this
  extends to ordinary OTP texts, so do not raise `targetSdk` from 35 to 37
  without a plan. Google lists "connected device companion apps" as exempt;
  find out what qualifies, since this app is one in spirit. Source:
  https://developer.android.com/about/versions/17/behavior-changes-all and
  https://developer.android.com/about/versions/17/behavior-changes-17
- History fetches: fixed in code on 2026-11-14, not yet measured on the real
  phone. A page now costs 3 provider queries, plus one per MMS only in group
  threads, and the PC allows 90 seconds for a page of messages (DECISIONS.md,
  "Phase 4"). To confirm on the phone: open an old picture-heavy conversation
  on the PC and scroll up; the phone's log shows how long each
  `/threads/N/messages` took.
- CI's emulator is API 34. The Android 17 thread-index behaviour is covered
  only by JVM tests of the merge logic; an API 37 emulator job would cover it
  for real.
- The Windows installer is unsigned and dev APKs use a public dev key.
  Phase 4 covers signed releases.
