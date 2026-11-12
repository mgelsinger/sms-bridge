# Status

Read this first in a new session. Update it whenever the state below changes.

Last updated: 2026-11-11.

## Where things stand

- Phases 0 and 1 are done and tagged.
- Phases 2 (PC read path) and 3 (send and reply) are built and green in CI.
  Their gates need the real phone and PC: work through
  [DESK-CHECKLIST.md](DESK-CHECKLIST.md).
- On 2026-11-11 the installed Windows build (dev-e7b1c25) ran on Mickey's PC
  against the release APK on a local Android 14 emulator. Pairing, first sync,
  inbound (0.6 s, screen off), reply, new conversation, connection loss with
  one copy sent, restart after reboot, and revoke all worked. See the
  "Emulated desk run" entry in [DECISIONS.md](DECISIONS.md) for what that run
  cannot show. The phone app has still not run on the real phone.
- That run found three things, fixed with regression tests: sends made while
  the PC was still connecting were not queued, the "Allow background use"
  button stayed after it was accepted, and two mock-phone test bugs that only
  fail on Windows. Use a dev build newer than dev-e7b1c25 for the desk session.
- The desk session started the same day on the real phone (Pixel 9 Pro Fold,
  Android 17), reached over wireless debugging. Checklist sections 1 and 2
  passed: setup, discovery over mDNS, fingerprint match, pairing, first sync in
  about 19 seconds. Section 2 exposed that Android 17 hides newer threads from
  the app's thread list; fixed on both sides (see DECISIONS.md) and confirmed
  with dev-f5735e9 installed on both: all 42 conversations, history
  backfilled without re-pairing.
- Section 4 (sending) passed on the real phone: Ctrl+N to a new number, one
  copy sent, bubble matched, status Sent. No carrier delivery report was seen.
- Section 3 latency passed on the real phone: a text from another number
  arrived at 22:23 with the phone dozing and was in the PC's store 1.0 second
  after the phone stored it (plan target: 2 seconds). Whether the Windows
  notification showed is not confirmed yet; only Mickey can see that. A text
  to his own number does not come back as inbound on his carrier.
- Still open on the real phone: the notification check for section 3,
  section 5 (Wi-Fi drop mid-send), section 6 (idle for an hour).
  `docs/PHASE-2.md` and `docs/PHASE-3.md` wait for the notification check and
  section 5.
- After the checklist passes: write `docs/PHASE-2.md` and `docs/PHASE-3.md`
  (CI tags the commits that add them), then start phase 4 from
  [PLAN.md](PLAN.md).
- If a checklist step fails: fix it, add a regression test, and re-run the
  step on a new dev build.

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
  conversations). Find out what the flag means.
- History fetches are slow on the real phone: each MMS costs two provider
  lookups at roughly 70 ms each while the phone dozes, so a 100-message page of
  an MMS-heavy thread takes about 10 seconds and a first sync of a large
  history takes minutes. Batch the address and part lookups per page.
- The reconnect wait doubles up to 60 seconds. Consider capping it at 15.
- CI's emulator is API 34. The Android 17 thread-index behaviour is covered
  only by JVM tests of the merge logic; an API 37 emulator job would cover it
  for real.
- The plan's 2-second inbound latency has not been measured on real hardware.
- The Windows installer is unsigned and dev APKs use a public dev key.
  Phase 4 covers signed releases.
