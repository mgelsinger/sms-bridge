# Desk session checklist

Everything CI can prove is proven: the protocol, both apps' logic, the phone app
on an Android 14 emulator (pairing, live inbound, sending, duplicate-send
protection), the Windows client against the mock phone, and the Windows build.
This checklist covers what only your phone and your PC can show. It closes the
phase 2 and phase 3 gates in one sitting, about 20 minutes.

Use the newest **Dev build** on the
[Releases page](https://github.com/mgelsinger/sms-bridge/releases): one `.apk`
and one `-setup.exe` from the same build. Note the build name (`dev-xxxxxxx`)
when you report back.

## 0. Before you start

- **RCS chats are off** in Google Messages (Settings, RCS chats). Done on
  2026-11-09. With RCS on, those conversations never reach the SMS store and
  would not show on the PC.
- Phone and PC are on the same Wi-Fi network, not a guest network. Guest
  networks usually block devices from reaching each other.

## 1. Phone app (can be done from the phone alone)

1. Open the `.apk` on the phone and install it.
2. Open SMS Bridge, tap **Grant permissions**, and allow SMS, contacts, and
   notifications. If Android refuses the SMS permission: App info, the
   three-dot menu, **Allow restricted settings**, then grant again.
3. Tap **Allow background use** and accept.
4. Tap **Turn on**. Expected: "On at 192.168.x.x:8765" and a fingerprint.

Report: Android version, phone model, whether the restricted-settings step was
needed, and the status line.

## 2. Windows app and pairing

1. Run the `-setup.exe`. It is not code-signed yet, so SmartScreen will warn:
   **More info**, then **Run anyway**.
2. Start SMS Bridge. If Windows Firewall asks about private networks, allow it
   (it is for finding the phone; without it, type the address instead).
3. On the phone, tap **Pair a PC**.
4. On the PC, choose the phone from the list (or type the address shown on the
   phone). Compare the four lines of the fingerprint with the phone, tick the
   box, enter the 6-digit code, and click **Pair**.
5. On the phone, tap **Allow**.

Expected: the PC shows your conversations within a few seconds, with contact
names.

Report: whether the phone appeared in the list on its own, and how long the
first sync took.

## 3. Receiving (phase 2 gate)

1. Put the phone across the room, screen off.
2. From another phone, text yours.

Expected: the conversation updates within about 2 seconds, with the sender's
name, and a red dot appears on the SMS Bridge taskbar icon until you read it.
Pop-up notifications are off by default; the three-dot menu by the status
line turns them on. A text you send to your own number may not come back as an
incoming one (it depends on the carrier), so use another phone.

Report: roughly how many seconds it took, and whether the dot showed.

## 4. Sending (phase 3 gate)

1. Reply from the PC.
2. Expected on the PC: "Sending", then "Sent", then "Delivered" (delivered
   needs carrier delivery reports; if it stays at Sent, note your carrier).
3. Expected on the other phone: the text arrives from your number.
4. Expected on your phone: the text appears in your normal messaging app's
   conversation, as if you had typed it there.
5. Start a new conversation with Ctrl+N to a number you have not texted before.

Report: each of the above, yes or no.

## 5. Dropping the connection mid-send

1. On the PC, type a message but do not send it.
2. Turn the phone's Wi-Fi off, then press Enter on the PC. The bubble should
   say "Sending".
3. Turn Wi-Fi back on.

Expected: the PC reconnects on its own (or use the three-dot menu by the status
line, **Reconnect now**), the message goes out once, and the other phone gets
exactly one copy.

Report: number of copies received.

## 6. Background survival (start of phase 4)

Leave the phone idle with the screen off for an hour, then text it. Expected:
the PC still gets the notification.

Report: yes or no, and phone model if no (some manufacturers kill background
apps; see dontkillmyapp.com).

## If something goes wrong

Stop at the step that failed and send back:

- The step number and what you saw instead.
- **Phone:** the lines under "Recent events" in SMS Bridge (they never contain
  message text).
- **PC:** the status line at the bottom left of the window, and any red error
  text.

Common causes:

| Symptom | Likely cause |
| --- | --- |
| Phone not in the PC's list | Windows Firewall blocked discovery, or the router blocks mDNS. Type the address the phone shows instead. |
| "Could not reach the phone" after typing the address | Guest Wi-Fi or router "client isolation", or the phone app is off |
| Fingerprints differ | Stop. Do not pair. Report it. |
| Phone status stays "On, waiting for Wi-Fi" | The phone is on mobile data or a VPN is active |
| A contact's replies never show on the PC | That chat is still RCS on their side; it falls back to SMS after their phone notices yours has RCS off, which can take a few messages |

