# Status

Read this first in a new session. Update it whenever the state below changes.

Last updated: 2026-11-09.

## Where things stand

- Phases 0 and 1 are done and tagged.
- Phases 2 (PC read path) and 3 (send and reply) are built and green in CI.
  Their gates need the real phone and PC: work through
  [DESK-CHECKLIST.md](DESK-CHECKLIST.md). Nobody has run the Windows app on a
  real machine yet, and the phone app has only run on an emulator.
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
- The plan's 2-second inbound latency has not been measured on real hardware.
- The Windows installer is unsigned and dev APKs use a public dev key.
  Phase 4 covers signed releases.
