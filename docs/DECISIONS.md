# Decisions

Choices made during the build that the plan left open or that changed it.
Newest first.

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
