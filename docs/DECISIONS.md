# Decisions

Choices made during the build that the plan left open or that changed it.
Newest first.

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
