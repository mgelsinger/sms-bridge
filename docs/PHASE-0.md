# Phase 0: scaffold

Tagged `phase-0`. Gate: CI green and the mock phone answers `GET /threads`
with fixture data. Both pass.

## Built

- **Protocol v1** in `protocol/`: spec, 22 JSON Schemas, 36 valid and 10
  invalid fixtures. `npm run protocol:check` validates them and fails if any
  schema lacks a fixture.
- **Mock phone** in `tools/mock-phone/`: TLS server seeded from the fixtures,
  implementing pairing, every REST endpoint, and the WebSocket send flow with
  queued, sent, and delivered results. It validates everything it sends and
  receives against the schemas. `POST /_mock/inbound` simulates a text.
- **Android scaffold**: Gradle wrapper, Compose shell, Kotlin protocol models.
- **Desktop scaffold**: Tauri 2 shell with a React UI and a platform-independent
  `sms-bridge-core` crate holding the Rust protocol models, plus the SMS
  segment counter the composer will use.
- **CI**: protocol, Android (unit tests and debug APK), desktop (core tests,
  fmt, clippy, typecheck, frontend tests, Windows NSIS installer).

## Tested

| Check | Result |
| --- | --- |
| Schema and fixture validation | 46 checks pass |
| Mock phone tests | 13 pass: pairing, lockout, auth, pinning, paging, sync, send, replay dedupe, version mismatch |
| Kotlin fixture round trip | Every fixture decodes and re-encodes to identical JSON |
| Rust fixture round trip | Same, plus lenient parsing of unknown fields |
| Segment counter | 6 tests: GSM-7, extension characters, UCS-2, emoji |
| Windows installer | Builds in CI |
| Gate, by hand | `curl --cacert` to the mock CLI returns the 4 fixture threads; no token gives 401; plaintext HTTP is refused |

## Found and fixed

- The mock echoed a malformed `client_id` back in an `error` frame, which broke
  the schema. Strict self-validation caught it; it now echoes only valid ids.
- The Android fixture test located fixtures through the merged test resources
  folder. It now gets the path from a Gradle system property.

## Next

Phase 1, the phone read path. Its emulator tests run in CI, so work continues
without a pause.
