# Phase 3: send and reply

Gate: a message typed on the PC arrives on another phone, shows its delivery
state on the PC, appears in the phone's own messaging app, and a disconnect
mid-send produces exactly one copy. Passed on 2026-11-14: the sending parts on
Mickey's Pixel 9 Pro Fold (Android 17) and PC with dev build dev-5e591cf, the
disconnect part on the emulator (see DECISIONS.md, "Phase 3 gate").

## Built

- **Sending on the phone**: `SmsManager` with sent and delivered intents,
  multipart texts, one recipient per send (group sends need MMS, phase 5).
- **Exactly-once sends**: every send carries a `client_id`. The phone
  remembers the result per `client_id` and answers a replay with it instead of
  sending again. The PC keeps an outbox and replays unconfirmed entries after
  each reconnect.
- **Sent-message matching**: Android stores the sent text in the provider
  itself; the phone ties that row to the pending send, so the PC shows one
  bubble, not two.
- **Composer**: Enter to send, Shift+Enter for a new line, live segment count
  with the GSM-7 or Unicode encoding, group threads read-only with an
  explanation.
- **New conversation** with Ctrl+N: a number or a contact by name.
- **Bubble states**: Sending, Sent, Delivered, Failed with retry and discard.
- **Sends are accepted at any time**: while connecting, syncing, or offline
  they go to the outbox at once and show as "Sending".

## Tested

| Check | Where | Result |
| --- | --- | --- |
| Send tracking, result ordering, replay of a known `client_id` | JVM, CI | pass |
| Send to the emulator's own number, stored copy carries the `client_id`, replay sends nothing | Android 14 emulator, CI | passes |
| Outbox, statuses, queue while connecting, replay after reconnect | Rust unit and integration tests against the mock phone, CI on Linux and Windows | pass |
| Reply, new conversation, Sending to Sent to Delivered | installed build against the emulator, 2026-11-11 | passes, Sent in 0.3 s |
| **Disconnect mid-send: bridge off, traffic silently dropped for 70 s, send during reconnect** | installed build against the emulator | one copy each time |
| **Ctrl+N to a new number from the PC** | real phone and PC | one copy, bubble matched, shows in the phone's messaging app |
| **Text from the PC to another person's phone** | real phone and PC, 2026-11-13 and 14 | arrived; confirmed by Mickey |
| Texts sent from the PC in ordinary use | real phone and PC | all "Sent", none failed, no `client_id` stored twice |

## Found and fixed on the way

- Send results could arrive out of order (fixed before the desk session).
- A send made while the PC was still connecting was not recorded until the
  attempt ended, up to 10 seconds, and was lost if the app closed meanwhile.
- Replies into conversations created under Android 17 would have failed,
  because the phone did not list those threads. Fixed with the thread list
  change described in PHASE-2.md.

## Known limits

- Mickey's carrier returns no delivery reports, so bubbles end at "Sent" and
  never reach "Delivered" on his phone.
- A text to his own number does not come back as an inbound message.
- The disconnect-mid-send check was not run on the real phone and will not be.
- Sending MMS, group replies, and attachments are phase 5.
