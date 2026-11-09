# sms-bridge protocol, version 1

This file is the contract between the Android app (the "phone") and the Windows
app (the "client"). JSON Schemas in `schema/` are normative for message shapes;
this file is normative for behavior. Fixtures in `fixtures/` are validated
against the schemas in CI and loaded by both apps' test suites.

## Transport

- One TLS listener on the phone, default port `8765`, bound to the Wi-Fi
  interface address only.
- The phone presents a self-signed ECDSA P-256 certificate. The client pins the
  SHA-256 fingerprint of the DER-encoded certificate, learned at pairing. A
  fingerprint mismatch is a hard failure. There is no CA validation and no
  "trust anyway" path.
- REST over HTTPS/1.1 for reads and pairing. One WebSocket at `/ws` for live
  events and sends.
- All JSON bodies are UTF-8 and carry `"protocol_version": 1`. A receiver that
  gets a different major version responds with error `unsupported_version` and
  closes the connection (WebSocket close code 4000).
- Discovery: the phone advertises mDNS service type `_smsbridge._tcp` with TXT
  records `v=1` and `fp=<first 16 hex chars of the fingerprint>`. The client
  uses the TXT `fp` to pick the right phone, then still verifies the full
  fingerprint on connect.

## Authentication

Every request except `POST /pair` carries `Authorization: Bearer <token>`. The
WebSocket upgrade request carries the same header. Missing or unknown tokens get
HTTP 401 with error `unauthorized`; the WebSocket upgrade is refused with 401.

### Pairing

1. The user taps "Pair" on the phone. The phone generates a 6-digit
   `pairing_code` valid for 120 seconds and single use, and shows a QR code
   encoding the `pairing-qr` schema: host, port, full fingerprint, code.
2. The client connects with the fingerprint pinned (from the QR, or typed by
   the user) and sends `POST /pair` with a `pair-request` body.
3. The phone shows a confirmation dialog naming `client_name`. On accept it
   responds 200 with a `pair-response` containing a 32-byte random token,
   base64url without padding (43 characters). On reject: 403
   `pairing_rejected`. Expired or wrong code: 403 `pairing_invalid`.
4. More than 5 failed `/pair` attempts in 10 minutes locks pairing for 10
   minutes: 429 `rate_limited`.
5. The phone stores only SHA-256 of the token. Revoking a client deletes its
   hash and closes its WebSocket with code 4001.

## Identifiers and cursors

- Android keeps SMS and MMS in separate tables whose row ids overlap, so a
  message `id` is a string: `"sms:<_id>"` or `"mms:<_id>"`. Clients treat it as
  opaque and use it only for equality.
- `thread_id` is the provider's integer thread id, shared by SMS and MMS.
- Paging uses opaque string cursors produced by the phone. Clients never build
  or parse cursors; they pass back exactly what they received. A cursor the
  phone cannot decode gets 400 `bad_cursor`, and the client restarts from no
  cursor.

Message dates are epoch milliseconds for both kinds. Android stores MMS dates in
seconds; the phone converts them. MMS part `size` is `null` when the phone
cannot tell the size without reading the part.

## REST endpoints

| Request | Success body (schema) | Notes |
| --- | --- | --- |
| `POST /pair` | `pair-response` | Body: `pair-request`. No auth. |
| `GET /status` | `status` | |
| `GET /threads?limit=&cursor=` | `threads-page` | Newest activity first. `limit` 1-200, default 50. |
| `GET /threads/{thread_id}/messages?limit=&cursor=` | `messages-page` | Newest first. `limit` 1-500, default 100. |
| `GET /messages?cursor=&limit=` | `sync-page` | Incremental sync. No `cursor` means "start from now": returns no messages and a cursor for the newest message. Messages come in id order within each kind (all new SMS, then all new MMS), not date order; clients sort by `date`. |
| `GET /contacts` | `contacts` | Contacts that have at least one phone number. |
| `GET /mms/{part_id}` | raw bytes | `Content-Type` is the part's MIME type. |

`next_cursor` is `null` when there are no more pages. In `sync-page`,
`has_more: true` means call again immediately with `next_cursor`; when
`has_more` is false, store `next_cursor` and wait for WebSocket events.

Errors on any endpoint use the `error` schema with these codes:
`bad_request`, `bad_cursor`, `unauthorized`, `pairing_invalid`,
`pairing_rejected`, `rate_limited`, `not_found`, `unsupported_version`,
`internal`.

## WebSocket `/ws`

Text frames only, one JSON object per frame, each with `protocol_version` and
`type`. Unknown `type` values from a newer phone are ignored by the client.

### Phone to client

| `type` | Schema | When |
| --- | --- | --- |
| `hello` | `ws-hello` | First frame after the upgrade. Carries device name and the current sync cursor. Sync from the client's stored cursor up to this point; everything after arrives as `message` frames. A message stored right as the socket opens can arrive both ways or only live, so clients upsert by `id`. |
| `message` | `ws-message` | A message appeared in the provider: inbound, sent from this client, or sent from the phone itself. |
| `send_result` | `ws-send-result` | Progress of a client `send`. Sent once per state change. |
| `thread_updated` | `ws-thread-updated` | Read state, snippet, or membership of a thread changed on the phone. Client refetches that thread's first page. |
| `status` | `ws-status` | Battery, signal, or SIM state changed. At most once per 30 seconds. |
| `pong` | `ws-pong` | Reply to `ping`. |
| `error` | `ws-error` | A client frame was rejected. Carries `client_id` when the rejected frame had one. |

### Client to phone

| `type` | Schema | Behavior |
| --- | --- | --- |
| `send` | `ws-send` | Send an SMS. Exactly one of `thread_id` or `to`. Bodies longer than one segment are sent as multipart SMS. |
| `mark_read` | `ws-mark-read` | Best effort. Only works when the phone app is the default SMS app; otherwise the phone replies `error` with code `not_default_sms_app`. |
| `ping` | `ws-ping` | Every 30 seconds. The client reconnects if no `pong` arrives within 10 seconds. |

### Sending and idempotency

1. The client creates a UUID v4 `client_id`, writes it to its outbox, then
   sends `send`.
2. The phone replies `send_result` with `status: "queued"` immediately, then
   `"sent"` when every part's sent-intent reports success, then `"delivered"`
   when every part's delivery report arrives, or `"failed"` with `error` at
   the first failed part.
3. When the provider row for the sent message appears, the phone emits a
   `message` frame with `client_id` set, and includes `message_id` on the next
   `send_result`.
4. The phone remembers `client_id` values for 24 hours. A repeated `send` with
   a known `client_id` is not sent again; the phone replies with the latest
   `send_result` for it. This makes reconnect replay safe.
5. If no delivery report arrives within 60 seconds of `"sent"`, the client
   treats `"sent"` as final.

### Close codes

| Code | Meaning |
| --- | --- |
| 1000 | Normal close |
| 1001 | Phone service stopping (Wi-Fi lost, app stopped) |
| 4000 | Unsupported protocol version |
| 4001 | Token revoked |
| 4002 | Heartbeat timeout |

## Versioning

Additive changes (new optional fields, new frame types) keep version 1. A
receiver ignores fields it does not know. The schemas are strict
(`additionalProperties: false`) because they describe what a version 1 sender
emits and catch typos in fixtures; parsers on both sides must stay lenient. Removing or changing the meaning of a
field requires version 2.
