# calls-probe

A throwaway Windows program for the desk experiment in
[docs/PLAN.md](../../docs/PLAN.md), "Calls". It is not part of either app and
nothing depends on it. Delete it once phase 7 is decided.

It answers: can a program installed the way sms-bridge is (unpackaged, per
user) use the paired phone's calls through Windows' Bluetooth hands-free
support, with the voice on the PC?

## What it does

1. Lists the Bluetooth call devices Windows knows (the paired phone).
2. Asks Windows for access to it and registers for its calls.
3. Connects to the phone.
4. Finds the phone line that belongs to it.
5. Watches calls and takes one-letter commands: answer, reject, end, hold,
   mute, move the voice between PC and phone, keypad tones, dial.

It never dials on its own; a call is placed only by typing `d <number>`.
Numbers and names are masked in what it prints and in `calls-probe.log`, which
it writes beside the exe. On `q` it undoes the registration it made. It does
not pair or unpair anything.

## At the desk

1. On the PC: Settings > Bluetooth & devices > Add device > Bluetooth, pick
   the phone, accept the prompt on both. If the phone asks whether to allow
   access to contacts and call history, either answer is fine for this.
2. Run it from the repo root: `tools\calls-probe\target\release\calls-probe.exe`
   (build first with `cargo build --release` in `tools/calls-probe`).
3. Call the phone from another phone. Watch for a `CALL ringing` line. Type
   `a` to answer, talk, check both directions through the PC, type `e`.
4. Type `d` and a number you choose, talk, type `pc` and `ph` to move the
   voice, type `e`.
5. Type `q`.

What to note by ear, since the log cannot hear: whether you heard the other
side through the PC's speakers, whether they heard you through the PC's
microphone, and whether the phone's own screen showed the call normally.

`--no-phone` runs everything that needs no phone, to check the program itself.
