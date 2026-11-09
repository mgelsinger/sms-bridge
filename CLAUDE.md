# sms-bridge

Self-hosted Android-to-Windows SMS bridge. The phone is the source of truth;
the PC is a paired LAN client. Read docs/PLAN.md before any work.

## Non-negotiables
- No network calls to anything but the paired phone. No telemetry, no update
  checks, no crash reporting, no Google Play Services or Firebase.
- TLS everywhere, fingerprint pinned on the PC. Never add a "skip verification"
  option, even for development. Use tools/mock-phone with its own test cert.
- Message bodies never appear in logs at the default level.
- Do not make the app the default SMS handler unless the task says phase 5.

## How to work
- Work one phase at a time as defined in docs/PLAN.md. Finish the phase, run its
  acceptance checks, write a short PHASE-N.md summary of what was built and what
  was tested, commit with a tag `phase-N`, then stop and wait for review.
- Before adding a dependency, state what it is for and why the standard library
  or an existing dependency does not cover it. Prefer fewer, well-known crates
  and libraries.
- The protocol in protocol/PROTOCOL.md is the contract. If an implementation
  need requires changing it, change the spec, schemas, and fixtures first, in
  their own commit, then the code.
- Every bug fix gets a regression test. Every protocol message gets a fixture.
- Ask when the plan is ambiguous or when a decision cannot be reversed without
  data loss. Otherwise choose and record the choice in docs/DECISIONS.md.

## Layout
- protocol/   spec, JSON schemas, fixtures (shared by both apps' tests)
- android/    Kotlin, Ktor, Compose. Build: `./gradlew assembleDebug`
- desktop/    Tauri 2 (Rust) + React/TS. Build: `npm run tauri dev`
- tools/      mock-phone server, adb scripts, release scripts

## Commands
- Protocol check:   `npm run protocol:check` (validates fixtures against schemas)
- Android tests:    `./gradlew test` (JVM) and `./gradlew connectedCheck` (emulator)
- Desktop tests:    `cargo test` in desktop/src-tauri and `npm test` in desktop
- Install to phone: `tools/scripts/install.sh` (wraps adb install -r)
- Inject a text on the emulator: `adb emu sms send +15555550100 "hello"`

## Style
- Kotlin: official style, coroutines over callbacks, no RxJava.
- Rust: clippy clean with `-D warnings`, no `unwrap()` outside tests.
- TypeScript: strict mode, no `any`, plain CSS modules, no UI component library.
- Hyphens, not em dashes, in all prose and comments.
