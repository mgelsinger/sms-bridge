# Status

Read this first in a new session. Update it whenever the state below changes.

Last updated: 2026-11-20.

## Where things stand

- Phases 0 to 3 are done and tagged. Phases 2 and 3 passed on Mickey's own
  hardware (Pixel 9 Pro Fold, Android 17, and his Windows PC); results and
  what was found on the way are in [PHASE-2.md](PHASE-2.md) and
  [PHASE-3.md](PHASE-3.md).
- Running on the phone: dev-969c085; on the PC: dev-0351f88, both since
  2026-11-20 (dev-5e591cf from 2026-11-11 before that), paired, and in
  ordinary daily use. Texts arrive overnight with the taskbar dot; texts sent
  from the PC go out once.
- Mickey will not run the Wi-Fi-drop-mid-send check on his real phone. It is
  accepted on emulator evidence (DECISIONS.md, "Phase 3 gate"). Do not ask
  again.
- Phase 4 (harden) from [PLAN.md](PLAN.md) is in progress. Its gate, a week
  of daily use without picking up the phone to text, passed: Mickey on
  2026-11-20, after using it since 2026-11-11, "Texting worked perfectly"
  (DECISIONS.md, "Phase 4 gate"). Already in place from earlier phases:
  start after boot, reconnect with a capped wait, tray, MMS images,
  read-only group threads, search. Built in phase 4: faster history pages,
  "Start with Windows" (off by default, in the status menu), FTS5 search,
  and search by contact name and number. What keeps phase 4 open is signed
  APK and installer, which waits on Mickey's decisions (last section).
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
  over Bluetooth hands-free). Plan only, no code. See PLAN.md, "Calls", and
  the open item below. Scope stays Android and Windows.
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

- "Start with Windows", the tray-only start at login, and the one-copy rule
  are proven only as far as CI goes (the registry round trip, the rules, the
  menu). Nobody has yet seen them on a real PC. To check at the desk: switch
  it on in the status menu, sign out and in, and confirm the app is in the
  tray without a window; then open it from the Start menu and confirm the
  existing copy comes to the front and Task Manager shows one
  `sms-bridge-desktop.exe`.
- Search changed with the FTS5 index: it matches whole words and word
  beginnings in any order, and no longer text in the middle of a word
  (DECISIONS.md, "Phase 4"). The store upgrade was seen working on the real
  PC on 2026-11-20. Since 2026-11-20 the search box also lists the
  conversations whose contact name or number matches, above the message
  hits. Installed on the PC as dev-0351f88 the same day and checked in the
  real window: a contact found by three letters of a surname, a number
  found typed as (xxx) xxx-xxxx, "Nothing matches." for gibberish. The
  phone stays on dev-969c085; nothing since needs a new APK.
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
- Calls: the experiment that opens phase 6 is not run. It needs Mickey at the
  desk: pair the phone with the PC over Bluetooth in Windows Settings, then
  place one call himself while a throwaway program on the PC watches. The
  program is not written yet. Known so far (2026-11-20, no phone involved):
  the PC has a Bluetooth radio, no phone is paired to it, and Windows' calling
  classes answer from an unpackaged process. Whether to run it before the
  phase 4 week is over is Mickey's call; it touches neither installed app.
  The agent never places or answers a call on his phone.
- The Windows installer is unsigned and dev APKs use a public dev key.
  Signed releases are not started; see the next section.

## Signed releases: what Mickey must provide or decide

Nothing below can be done for him. Until it is, dev builds stay as they are.

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
