# Security

## Threat model

SMS Bridge exposes your text messages on your local network. The design
assumes a hostile device on the same Wi-Fi: it can see the phone's mDNS
advertisement, reach its port, and observe or tamper with traffic.

Out of scope: an attacker with control of the unlocked phone or the logged-in
Windows account. Either already has your messages.

## Controls

| Risk | Control |
| --- | --- |
| Eavesdropping or tampering on the LAN | TLS 1.2+ on every connection. No plaintext listener. |
| Impersonating the phone | The PC pins the SHA-256 fingerprint of the phone's certificate at pairing. A mismatch is a hard failure; there is no "continue anyway". |
| Unpaired devices reading messages | Every request needs a bearer token issued only through pairing. |
| Brute-forcing pairing | 6-digit single-use code, valid 2 minutes, plus an on-phone confirmation naming the PC. 5 failures in 10 minutes locks pairing for 10 minutes. |
| Token theft from the phone | The phone stores only SHA-256 hashes of tokens. Revoking a PC closes its connection immediately. |
| Token theft from the PC | Stored in Windows Credential Manager, not in the app database. |
| Exposure on other networks | The server binds to the Wi-Fi interface only and stops when Wi-Fi drops. |
| Messages leaking into logs | Message bodies are never logged at the default level. |
| Data leaving the network | No telemetry, crash reporting, update checks, or third-party services. The desktop UI's content security policy blocks all network access from the web view; only the Rust core talks to the phone. |
| Supply chain | Pinned lockfiles, Dependabot, and a small dependency set recorded with reasons in `docs/DECISIONS.md`. |

## Known gaps

- The PC's message cache is a plain SQLite file under `%LOCALAPPDATA%`,
  protected by Windows account permissions. An encrypted build (SQLCipher)
  is planned.
- `tools/mock-phone/certs/` contains a **test-only** private key, committed on
  purpose so development needs no setup. It is never used by the real apps;
  each phone generates its own key in Android Keystore.

## Reporting

Open a GitHub security advisory on this repository rather than a public issue.
