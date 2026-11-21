# Status

Read this first in a new session. Update it whenever the state below changes.

Last updated: 2026-11-20.

## Where things stand

- Phases 0 to 3 are done and tagged. Phases 2 and 3 passed on Mickey's own
  hardware (Pixel 9 Pro Fold, Android 17, and his Windows PC); results and
  what was found on the way are in [PHASE-2.md](PHASE-2.md) and
  [PHASE-3.md](PHASE-3.md).
- Running on both the phone and the PC: dev-a04794c, since 19:39 on
  2026-11-20 (dev-fcd3328 earlier that evening; the phone had dev-969c085
  and the PC dev-0351f88 earlier that day, both dev-5e591cf from 2026-11-11
  before that), paired, and in
  ordinary daily use. Texts arrive overnight with the taskbar dot; texts sent
  from the PC go out once.
- Mickey will not run the Wi-Fi-drop-mid-send check on his real phone. It is
  accepted on emulator evidence (DECISIONS.md, "Phase 3 gate"). Do not ask
  again.
- Phase 4 (harden) from [PLAN.md](PLAN.md) is done, closed on 2026-11-20:
  see [PHASE-4.md](PHASE-4.md). Its gate, a week of daily use without
  picking up the phone to text, passed: Mickey on 2026-11-20, after using it
  since 2026-11-11, "Texting worked perfectly" (DECISIONS.md, "Phase 4
  gate"). Already in place from earlier phases:
  start after boot, reconnect with a capped wait, tray, MMS images,
  read-only group threads, search. Built in phase 4: faster history pages,
  "Start with Windows" (off by default, in the status menu), FTS5 search,
  and search by contact name and number. Signed releases were deferred by
  Mickey the same day: unsigned dev builds on his phone, signing if he ever
  shares the app (DECISIONS.md, "Phase 4 closed").
- The phase 4 work went onto both devices on 2026-11-20 (APK with
  `adb install -r`, installer with `/S`, the PC store backed up first to
  `%LOCALAPPDATA%\io.github.mgelsinger.smsbridge\backup-before-969c085`).
  Permissions and pairing were kept on both sides; the phone restarted the
  bridge by itself after the update, and the new PC build caught up in
  0.5 s. Checked that day: the PC store upgraded to FTS5 in place and
  search answers in under 5 ms with words in any order and word beginnings;
  "Start with Windows" is off by default and writes no Run entry; a page of
  91 MMS from an old conversation took 513 ms on the phone (774 ms on the
  PC), where the old per-MMS lookups would have cost about 180 queries.
- Phone calls were added to the plan on 2026-11-20 at Mickey's request, as
  phases 6 (calls over the bridge, voice on the phone) and 7 (voice on the PC
  over Bluetooth hands-free). Scope stays Android and Windows. Phase 6 is
  built and on `main` as far as CI can prove it: protocol, mock phone, phone
  app, PC core, PC window. It went onto both devices on 2026-11-20 as
  dev-fcd3328 (APK with `adb install -r`, installer with `/S`, the PC store
  backed up first to
  `%LOCALAPPDATA%\io.github.mgelsinger.smsbridge\backup-before-fcd3328`).
  Checked afterwards: the phone restarted the bridge by itself and the PC was
  connected again within a second; SMS permissions and pairing were kept; the
  four call permissions are not granted, the phone answers `GET /calls` with
  403 as designed, and the PC store is at schema 3 with every message still
  there. No real call has gone through it. Phase 7 is not started; a
  throwaway program for its deciding experiment is built, and Mickey accepts
  Windows' own Bluetooth calling for it (DECISIONS.md, "Calls"). What is left
  is all at the desk: [CALLS-DESK.md](CALLS-DESK.md).
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

- Pictures and group replies (MMS sending), attachment playback and saving,
  contact photos, and iPhone reactions were built on 2026-11-20 (DECISIONS.md, "Pictures,
  group replies, attachments"). CI covers the encoder, the validation, the
  outbox and the window; the emulator cannot send MMS, so its test stops at
  the system's MMS service. To check at the desk, with Mickey sending: a
  picture from the PC to his own number or a friend, a reply in a group
  conversation, and a picture over about 1 MB to see it shrunk. Watch for
  the sent picture's bubble staying "1 attachment" (stored copy withheld by
  Android 17) and for `MMS_ERROR_*` in the phone's log. Seen on his devices
  with dev-a04794c: permissions and pairing kept through the update, the PC
  store upgraded to step 5 with all 44 conversations, the phone reports
  `mms: send true, max_bytes 1048576` from the carrier's own configuration,
  and one contact photo (5 KB) came through. The emulator's MMS service
  answers `MMS_ERROR_CONFIGURATION_ERROR` at once, before reading the PDU,
  so whether the system can read it through the FileProvider grant is
  proven only by a real send. PC store backup:
  `%LOCALAPPDATA%\io.github.mgelsinger.smsbridge\backup-before-a04794c`.

- "Start with Windows", the tray-only start at login, and the one-copy rule
  are proven by CI (the registry round trip, the rules, the menu). The
  one-copy rule was seen on the real PC on 2026-11-20: a second start left
  one process and brought its window forward. The tray-only start at login
  is not seen yet: switch it on in the status menu, sign out and in, and
  confirm the app is in the tray without a window.
- Search changed with the FTS5 index: it matches whole words and word
  beginnings in any order, and no longer text in the middle of a word
  (DECISIONS.md, "Phase 4"). The store upgrade was seen working on the real
  PC on 2026-11-20. Since 2026-11-20 the search box also lists the
  conversations whose contact name or number matches, above the message
  hits. Installed on the PC as dev-0351f88 the same day and checked in the
  real window: a contact found by three letters of a surname, a number
  found typed as (xxx) xxx-xxxx, "Nothing matches." for gibberish. The
  phone stayed on dev-969c085 until dev-fcd3328 that evening.
- Dependency updates are waiting. Dependabot's grouped PRs (#6 and #7, which
  it replaced itself with #9 for npm and #8 for Gradle) were closed unmerged
  on 2026-11-14 because CI was red on both. They are toolchain migrations,
  not routine bumps: Gradle 8 to 9 with Kotlin 2.4 and the newer Android
  Gradle Plugin (the build stops at the `org.jetbrains.kotlin.android`
  plugin), and React 19 with TypeScript 7 (the UI no longer type-checks).
  Do them by hand after the phase 4 week, one ecosystem at a time. Splitting
  `.github/dependabot.yml` into a minor-and-patch group and a major group
  per ecosystem would let the routine updates arrive green.
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
- History fetches: measured on the real phone on 2026-11-20 for a 1:1
  conversation (91 MMS in 513 ms). Group threads still cost one query per
  MMS and were not measured: the only group thread the phone lists has two
  messages.
- CI's emulator is still API 34 only. An Android 17 job was tried on
  2026-11-14 and not added (PR #10, closed): Android 17 images exist and
  boot, but none behaves like the phone, so the job would test the image.
  The AOSP image lists new threads in the system's index, which the phone
  does not, and hides from the app the texts the app itself sent, which the
  phone shows. The Google images crash the emulated system when a text is
  sent. Details in DECISIONS.md, "Phase 4". The Android 17 thread-index
  behaviour therefore stays covered by JVM tests of the merge logic and by
  the real phone. Try again when a newer system image ships.
- One thing from that attempt to keep in mind: on the Android 17 emulator
  the stored copy of a text sent by an app that is not the default SMS app
  is marked `read_restriction = 65536` and withheld from that app. The phone
  did not do this as of 2026-11-14. If texts sent from the PC ever stop
  showing as one matched bubble after a phone update, look here first.
- Calls, phase 6: unproven on Android 17 and on the real PC. The phone app
  answers and ends calls through two `TelecomManager` methods deprecated
  since Android 10; they work on the Android 14 emulator and nobody knows yet
  whether Mickey's phone still honours them. Same for the caller's number
  arriving while it rings, and for a call started from the background
  service. The steps are Part A of [CALLS-DESK.md](CALLS-DESK.md), from
  step 2: the build is installed. It asks for nothing until he taps "Allow
  calls"; until then the PC says calls are not allowed on the phone and texts
  are untouched.
- Calls, phase 7: the Bluetooth experiment is not run. `tools/calls-probe` is
  built (`tools/calls-probe/target/release/calls-probe.exe` on this PC) and
  runs end to end with `--no-phone`. Known without the phone: the PC has a
  Bluetooth radio, no phone is paired to it, and Windows' calling classes
  answer from an unpackaged process. Part B of CALLS-DESK.md.
- Calls, not built: the PC cannot silence the ringer (needs the dialer role)
  and cannot reach voicemail. Built since: search over the call history by
  name or number, an All/Missed switch, and contact photos on call rows
  (5fecb06, so in dev builds after dev-a04794c). The status menu has "Pop-ups
  for calls", on by default; Mickey has not been asked whether he wants that
  default.
- The agent never places or answers a call on Mickey's phone. Call tests run
  on CI's emulator only; local connected tests stay forbidden while his phone
  is attached to adb, and on 2026-11-20 it was.
- The Windows installer is unsigned and dev APKs use a public dev key, by
  Mickey's choice of 2026-11-20. Signed releases are deferred until he wants
  to share the app; the next section is the list for that day.

## Signed releases: what Mickey must provide or decide

Deferred on 2026-11-20 (DECISIONS.md, "Phase 4 closed"): only for the day he
wants to share the app with friends. Do not raise it before then. Nothing
below can be done for him. Until it is, dev builds stay as they are.

Decided on 2026-11-20 (DECISIONS.md, "Release choices"): one app id for dev
and release (item 4), the Windows installer stays unsigned (items 5 and 6),
and a release is cut by a `v0.1.0`-style tag he pushes (item 8). Still
open: the Android key and its four secrets (items 1 and 2), "not now" on
2026-11-20, so do not raise it again until he does or until something needs
a release; the uninstall date (item 3) follows from the key. Item 7 was not
asked; the installer stays per user until he says otherwise.

Android

1. A release signing key that only he holds, made on his own machine, for
   example `keytool -genkeypair -keystore sms-bridge-release.jks -alias
   sms-bridge -keyalg RSA -keysize 4096 -validity 10000`. He chooses the
   passwords and keeps a backup offline: if the key is lost, no later build
   can ever be installed over a release build.
2. Four GitHub Actions secrets, entered by him in the repository settings:
   the keystore file as base64, the keystore password, the key alias, and the
   key password.
3. A date for one uninstall on his phone. Android will not install a
   release-signed APK over the dev-signed one, so the first release build
   means uninstalling the dev build, granting the permissions again, and
   pairing the PC again.
4. Whether dev builds keep the release application id (one app on the phone,
   and switching between dev and release means uninstalling) or get a `.dev`
   suffix (both installed side by side, each with its own pairing).

Windows

5. Whether to sign the installer at all. Unsigned works, and SmartScreen
   warns on every new build. Signing needs a certificate in his name: a paid
   code signing certificate (the key has to live on a hardware token or in a
   cloud key store), a hosted signing service such as Azure's, or SignPath's
   free programme for open source, by application. Each needs his identity,
   and all but the last his money.
6. If signing: the certificate or signing-service credentials as GitHub
   secrets, entered by him.
7. Installer behaviour: per user as now (no administrator prompt) or for the
   whole machine, and whether uninstalling should also delete the local
   message store and the "Start with Windows" entry.

Both

8. Version numbers and what makes a release. Proposal: he pushes a tag such
   as `v0.1.0`; CI builds both apps from it and publishes a GitHub release
   that is not marked prerelease. Dev builds continue on every green push.
