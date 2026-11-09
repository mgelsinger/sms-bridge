# SMS Bridge

Text from your Android phone's number on your Windows PC, over your own Wi-Fi.
No cloud relay, no Google or Microsoft account, no telemetry.

The phone runs a small service that reads and sends SMS through its own SIM.
The Windows app pairs with it once (QR code plus a pinned certificate) and keeps
a local copy of your conversations.

**Status:** early development. Phase 0 (protocol, mock phone, CI) is done; see
[docs/PLAN.md](docs/PLAN.md) for the roadmap.

## What it will not do

- **RCS chats.** Google Messages keeps RCS in its own private store, so only SMS
  and MMS are visible to other apps. RCS conversations will not appear.
- **Work outside your network** unless you add your own VPN (Tailscale or
  WireGuard) so the PC can reach the phone.

## Repository layout

| Path | What |
| --- | --- |
| `protocol/` | The wire contract: spec, JSON Schemas, fixtures |
| `android/` | Phone app (Kotlin, Ktor, Compose) |
| `desktop/` | Windows app (Tauri 2, Rust, React) |
| `tools/mock-phone/` | Fake phone for desktop development without a device |

## Developing without a phone

```sh
npm ci
npm run mock-phone          # prints the fingerprint and pairing code
npm test                    # schema checks + mock phone tests
cd desktop && cargo test -p sms-bridge-core && npm ci && npm test
```

See [SECURITY.md](SECURITY.md) for the threat model.
