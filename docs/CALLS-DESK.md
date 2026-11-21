# Calls: what needs Mickey at the desk

Written 2026-11-20. Everything that does not need the real phone or a real
call is done and on `main`. This is the rest, in order. Part A is phase 6
(calls over the bridge, you talk on the phone). Part B is the experiment that
decides whether phase 7 (talking through the PC) is possible. They do not
depend on each other.

The agent never places or answers a call on your phone. Every call below is
one you make, or one somebody makes to you.

## Part A: calls over the bridge

1. **Install the newest dev build on both.** Done on 2026-11-20:
   dev-fcd3328 is on the phone and the PC, pairing and permissions kept, the
   PC store upgraded in place. Nothing for you here.
2. **Allow calls on the phone.** Open SMS Bridge, scroll to the Calls card
   under Paired PCs, tap "Allow calls", and accept what Android asks. Expect
   two prompts: Phone, and Call logs. If Android refuses Call logs, it is the
   same "Allow restricted settings" step the SMS permission needed. Texts keep
   working whatever you answer.
3. **Look at the PC.** A rail on the left now has Texts and Calls. Calls
   should list your call history. Compare the newest few entries with the
   phone's own Recents.
4. **A call in.** Have someone call you, or call from another phone. On the
   PC, within about 2 seconds: a bar across the top with the caller's name,
   and a Windows pop-up if the window is not in front. Click **Decline**. The
   phone should stop ringing.
5. **Answer from the PC.** Another call in. Click **Answer on phone**. The
   phone picks up; you talk on the phone. Click **Hang up** on the PC.
6. **A call out.** In Calls, click **Call** on a history entry of your
   choosing, or type a number and press Enter. The phone dials and shows its
   own call screen. Click **Hang up** on the PC.
7. **From a conversation.** Open a one-to-one conversation in Texts. The
   header has a Call button; it does the same.
8. **Afterwards.** The calls you just made are in the PC's history, with the
   one you declined marked Declined and a missed one (let one ring out)
   lighting the taskbar dot until you open Calls.

What to tell the agent: the step number and what happened instead. It can
read the phone's log over adb while you do this; the log names states and
outcomes, never numbers.

Unknowns this settles, all on Android 17, none of which CI can see:

- Whether Android 17 still lets an ordinary app answer and end calls
  (`acceptRingingCall`, `endCall`, deprecated since Android 10). They work on
  the Android 14 emulator.
- Whether the caller's number arrives while it rings.
- Whether a call can be started from the background service on your phone.

Do not test the emergency-number refusal with a real emergency number. It is
covered by tests on both sides.

## Part B: the Bluetooth experiment

Decides phase 7. It uses a throwaway program, not the app.

The program uses the Bluetooth hands-free support that is built into Windows
(the `Windows.ApplicationModel.Calls` API), which you accepted on 2026-11-20.
It needs no Microsoft account, does not involve Phone Link, and talks to
nothing but the paired phone.

1. On the PC: Settings, Bluetooth & devices, Add device, Bluetooth, pick the
   phone, accept on both. If the phone asks about sharing contacts and call
   history, either answer is fine.
2. Run `tools\calls-probe\target\release\calls-probe.exe` from the repo
   folder, in a terminal.
3. Call the phone from another phone. Wait for a `CALL ringing` line. Type
   `a` and Enter. Talk. Type `e` and Enter.
4. Type `d`, a space, a number of your choosing, Enter. Talk. Type `pc` then
   `ph` to move the voice between PC and phone. Type `e`.
5. Type `q`.

What only you can tell: whether you heard the other side through the PC's
speakers, whether they heard you through the PC's microphone, and whether
anything from Microsoft's Phone Link got in the way. The rest is in
`calls-probe.log` beside the exe, with numbers and names masked.

If step 2 prints `access: denied` or cannot register, phase 7 needs a
packaged build or is dropped, and PLAN.md will say which.
