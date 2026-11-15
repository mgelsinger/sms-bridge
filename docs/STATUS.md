# Status

Read this first in a new session. Update it whenever the state below changes.

Last updated: 2026-11-14.

## Where things stand

- Phases 0, 1, and 2 are done and tagged. Phase 2 passed on Mickey's own
  hardware (Pixel 9 Pro Fold, Android 17, and his Windows PC); the results and
  what was found on the way are in [PHASE-2.md](PHASE-2.md).
- Running on both the phone and the PC since 2026-11-11: dev build
  dev-5e591cf, paired. On 2026-11-14 Mickey confirmed that texts arriving
  overnight were in the app and that the red taskbar dot showed. That also
  covers checklist section 6 (idle phone).
- Phase 3 (send and reply) is built and green in CI, and the emulated run on
  2026-11-11 passed all of it. On the real phone so far: a text sent from the
  PC with Ctrl+N went out once, matched its bubble, and showed in the phone's
  own messaging app (checklist section 4), but it was sent to Mickey's own
  number. Still needed for the phase 3 gate, all on the real phone:
  - a text from the PC to another person's phone that arrives there, and
    whether the bubble reaches "Delivered" (his carrier sent no delivery
    report for the text to himself);
  - checklist section 5: Wi-Fi off on the phone mid-send, back on, exactly one
    copy sent.
  Then write `docs/PHASE-3.md` (CI tags the commit that adds it) and start
  phase 4 from [PLAN.md](PLAN.md).
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
- History fetches are slow on the real phone: each MMS costs two provider
  lookups at 70 to 400 ms each while the phone dozes, so a 100-message page of
  an MMS-heavy thread can take 10 seconds or more, and one with 70 MMS could
  pass the PC's 30 second timeout. Batch the part lookups per page and
  consider a longer timeout for history pages. (The thread list itself was
  fixed on 2026-11-11; see DECISIONS.md.)
- CI's emulator is API 34. The Android 17 thread-index behaviour is covered
  only by JVM tests of the merge logic; an API 37 emulator job would cover it
  for real.
- The Windows installer is unsigned and dev APKs use a public dev key.
  Phase 4 covers signed releases.
