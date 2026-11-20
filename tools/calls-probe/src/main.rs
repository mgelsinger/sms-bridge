//! Desk experiment for docs/PLAN.md, "Calls".
//!
//! Answers one question: can a program installed the way sms-bridge is
//! (unpackaged, per user) use the paired phone's calls through Windows'
//! Bluetooth hands-free support? It lists the phone, asks for access,
//! registers, connects, then watches calls and takes one-letter commands.
//!
//! It talks to nothing but the phone paired in Windows Settings. It never
//! dials on its own: a call is placed only by typing `d <number>`.
//! Numbers and names are masked in everything it prints and logs.

#[cfg(not(windows))]
fn main() {
    eprintln!("calls-probe only runs on Windows.");
}

#[cfg(windows)]
fn main() {
    std::process::exit(probe::run());
}

/// What may be shown of a phone number: how long it is and its last two
/// digits. Enough to tell two calls apart, not enough to identify anyone.
fn mask_number(number: &str) -> String {
    let digits: Vec<char> = number.chars().filter(char::is_ascii_digit).collect();
    match digits.len() {
        0 if number.trim().is_empty() => "no number".to_string(),
        0 => "a number without digits".to_string(),
        n if n < 4 => format!("{n} digits"),
        n => {
            let tail: String = digits[n - 2..].iter().collect();
            format!("{n} digits ending {tail}")
        }
    }
}

/// What may be shown of a name: whether there is one.
fn mask_name(name: &str) -> String {
    match name.trim().chars().count() {
        0 => "no name".to_string(),
        n => format!("a name of {n} characters"),
    }
}

/// Splits a typed line into a command letter and what follows it.
fn parse_command(line: &str) -> (String, String) {
    let line = line.trim();
    match line.split_once(char::is_whitespace) {
        Some((head, rest)) => (head.to_lowercase(), rest.trim().to_string()),
        None => (line.to_lowercase(), String::new()),
    }
}

#[cfg(windows)]
mod probe {
    use super::{mask_name, mask_number, parse_command};
    use std::collections::BTreeMap;
    use std::fs::File;
    use std::io::{self, BufRead, Write};
    use std::sync::mpsc::{self, RecvTimeoutError, Sender};
    use std::time::{Duration, Instant};
    use windows::core::{IInspectable, Result, GUID, HSTRING};
    use windows::ApplicationModel::Calls::{
        DtmfKey, DtmfToneAudioPlayback, PhoneCall, PhoneCallAudioDevice, PhoneCallDirection,
        PhoneCallManager, PhoneCallOperationStatus, PhoneCallStatus, PhoneLine, PhoneLineTransport,
        PhoneLineTransportDevice, PhoneLineWatcher, PhoneLineWatcherEventArgs,
        TransportDeviceAudioRoutingStatus,
    };
    use windows::Devices::Enumeration::{DeviceAccessStatus, DeviceInformation};
    use windows::Foundation::{EventHandler, TypedEventHandler};

    const HELP: &str = "\
Commands (type one, then Enter):
  s            show the calls the PC can see
  d <number>   dial this number through the phone
  a            answer the ringing call        r   reject it
  e            end the call
  h            hold                           u   resume
  m            mute                           n   unmute
  pc           move the voice to the PC       ph  move it back to the phone
  k <key>      send a keypad tone: 0-9, * or #
  ?            this list
  q            quit (undoes the registration this run made)";

    enum Event {
        Typed(String),
        Note(String),
        InputClosed,
    }

    struct Log {
        started: Instant,
        file: Option<File>,
    }

    impl Log {
        fn open() -> Self {
            let path = std::env::current_exe()
                .ok()
                .and_then(|exe| exe.parent().map(|dir| dir.join("calls-probe.log")));
            let file = path.as_ref().and_then(|p| File::create(p).ok());
            let mut log = Log {
                started: Instant::now(),
                file,
            };
            match (&path, log.file.is_some()) {
                (Some(p), true) => log.say(&format!("Writing a copy of this to {}", p.display())),
                _ => log.say("Could not open a log file; printing only."),
            }
            log
        }

        fn say(&mut self, text: &str) {
            let stamp = self.started.elapsed().as_secs_f32();
            for line in text.lines() {
                let line = format!("[{stamp:7.1}] {line}");
                println!("{line}");
                if let Some(file) = self.file.as_mut() {
                    let _ = writeln!(file, "{line}");
                }
            }
            let _ = io::stdout().flush();
        }

        fn result<T>(&mut self, what: &str, result: Result<T>) -> Option<T> {
            match result {
                Ok(value) => Some(value),
                Err(error) => {
                    self.say(&format!(
                        "FAILED {what}: {:#010x} {}",
                        error.code().0,
                        error.message()
                    ));
                    None
                }
            }
        }
    }

    fn access_name(status: DeviceAccessStatus) -> &'static str {
        match status {
            DeviceAccessStatus::Allowed => "allowed",
            DeviceAccessStatus::DeniedByUser => "denied by user",
            DeviceAccessStatus::DeniedBySystem => "denied by system",
            _ => "unspecified",
        }
    }

    fn routing_name(status: TransportDeviceAudioRoutingStatus) -> &'static str {
        match status {
            TransportDeviceAudioRoutingStatus::CanRouteToLocalDevice => {
                "can carry the voice on the PC"
            }
            TransportDeviceAudioRoutingStatus::CannotRouteToLocalDevice => {
                "cannot carry the voice on the PC"
            }
            _ => "unknown",
        }
    }

    fn transport_name(transport: PhoneLineTransport) -> &'static str {
        match transport {
            PhoneLineTransport::Bluetooth => "Bluetooth",
            PhoneLineTransport::Cellular => "cellular",
            PhoneLineTransport::VoipApp => "VoIP app",
            _ => "unknown",
        }
    }

    fn call_status_name(status: PhoneCallStatus) -> &'static str {
        match status {
            PhoneCallStatus::Incoming => "ringing",
            PhoneCallStatus::Dialing => "dialing",
            PhoneCallStatus::Talking => "talking",
            PhoneCallStatus::Held => "held",
            PhoneCallStatus::Ended => "ended",
            PhoneCallStatus::Lost => "lost",
            _ => "unknown",
        }
    }

    fn audio_name(device: PhoneCallAudioDevice) -> &'static str {
        match device {
            PhoneCallAudioDevice::LocalDevice => "voice on the PC",
            PhoneCallAudioDevice::RemoteDevice => "voice on the phone",
            _ => "voice route unknown",
        }
    }

    fn operation_name(status: PhoneCallOperationStatus) -> &'static str {
        match status {
            PhoneCallOperationStatus::Succeeded => "succeeded",
            PhoneCallOperationStatus::TimedOut => "timed out",
            PhoneCallOperationStatus::ConnectionLost => "connection lost",
            PhoneCallOperationStatus::InvalidCallState => "not possible in this call state",
            _ => "failed",
        }
    }

    fn dtmf_key(key: &str) -> Option<DtmfKey> {
        Some(match key {
            "0" => DtmfKey::D0,
            "1" => DtmfKey::D1,
            "2" => DtmfKey::D2,
            "3" => DtmfKey::D3,
            "4" => DtmfKey::D4,
            "5" => DtmfKey::D5,
            "6" => DtmfKey::D6,
            "7" => DtmfKey::D7,
            "8" => DtmfKey::D8,
            "9" => DtmfKey::D9,
            "*" => DtmfKey::Star,
            "#" => DtmfKey::Pound,
            _ => return None,
        })
    }

    /// One line about a call, with nothing that identifies the other party.
    fn describe(call: &PhoneCall) -> String {
        let status = call
            .Status()
            .map(call_status_name)
            .unwrap_or("status unreadable");
        let audio = call
            .AudioDevice()
            .map(audio_name)
            .unwrap_or("voice route unreadable");
        let muted = match call.IsMuted() {
            Ok(true) => ", muted",
            _ => "",
        };
        let who = match call.GetPhoneCallInfo() {
            Ok(info) => {
                let direction = match info.CallDirection() {
                    Ok(PhoneCallDirection::Incoming) => "incoming",
                    Ok(PhoneCallDirection::Outgoing) => "outgoing",
                    _ => "direction unknown",
                };
                let number = info.PhoneNumber().map(|n| mask_number(&n.to_string()));
                let name = info.DisplayName().map(|n| mask_name(&n.to_string()));
                let hold = match info.IsHoldSupported() {
                    Ok(true) => "hold supported",
                    Ok(false) => "hold not supported",
                    Err(_) => "hold unknown",
                };
                format!(
                    "{direction}, {}, {}, {hold}",
                    number.unwrap_or_else(|_| "number unreadable".to_string()),
                    name.unwrap_or_else(|_| "name unreadable".to_string()),
                )
            }
            Err(error) => format!("details unreadable ({:#010x})", error.code().0),
        };
        format!("{status}, {audio}{muted}; {who}")
    }

    fn find_lines(log: &mut Log) -> Vec<GUID> {
        let Some(store) = log.result(
            "opening the call store",
            PhoneCallManager::RequestStoreAsync().and_then(|op| op.join()),
        ) else {
            return Vec::new();
        };
        let Some(watcher) = log.result("creating the line watcher", store.RequestLineWatcher())
        else {
            return Vec::new();
        };
        let (tx, rx) = mpsc::channel::<Option<GUID>>();
        let added = tx.clone();
        let done = tx;
        let _ = log.result(
            "watching for lines",
            watcher.LineAdded(&TypedEventHandler::<
                PhoneLineWatcher,
                PhoneLineWatcherEventArgs,
            >::new(move |_, args| {
                if let Some(args) = args.as_ref() {
                    let _ = added.send(Some(args.LineId()?));
                }
                Ok(())
            })),
        );
        let _ = log.result(
            "watching for the end of the line list",
            watcher.EnumerationCompleted(
                &TypedEventHandler::<PhoneLineWatcher, IInspectable>::new(move |_, _| {
                    let _ = done.send(None);
                    Ok(())
                }),
            ),
        );
        if log
            .result("starting the line watcher", watcher.Start())
            .is_none()
        {
            return Vec::new();
        }
        let mut lines = Vec::new();
        let deadline = Instant::now() + Duration::from_secs(10);
        loop {
            let left = deadline.saturating_duration_since(Instant::now());
            match rx.recv_timeout(left) {
                Ok(Some(id)) => lines.push(id),
                Ok(None) => break,
                Err(_) => {
                    log.say("The list of phone lines did not finish within 10 seconds.");
                    break;
                }
            }
        }
        let _ = watcher.Stop();
        lines
    }

    struct Session {
        log: Log,
        line: Option<PhoneLine>,
        seen: BTreeMap<String, String>,
    }

    impl Session {
        fn calls(&mut self) -> Vec<PhoneCall> {
            let Some(line) = self.line.as_ref() else {
                return Vec::new();
            };
            match line
                .GetAllActivePhoneCalls()
                .and_then(|r| r.AllActivePhoneCalls())
            {
                Ok(calls) => calls.into_iter().collect(),
                Err(_) => Vec::new(),
            }
        }

        /// Prints each call whose description changed since the last look.
        fn report(&mut self, always: bool) {
            let calls = self.calls();
            let mut now = BTreeMap::new();
            for (index, call) in calls.iter().enumerate() {
                let id = call.CallId().map(|id| id.to_string()).unwrap_or_default();
                now.insert(format!("{index}:{id}"), describe(call));
            }
            if always && now.is_empty() {
                self.log.say("The PC sees no call.");
            }
            for (key, text) in &now {
                if always || self.seen.get(key) != Some(text) {
                    self.log.say(&format!("CALL {text}"));
                }
            }
            for key in self.seen.keys() {
                if !now.contains_key(key) {
                    self.log.say("CALL gone from the PC's list");
                }
            }
            self.seen = now;
        }

        fn first(&mut self, wanted: &[PhoneCallStatus]) -> Option<PhoneCall> {
            let found = self
                .calls()
                .into_iter()
                .find(|call| call.Status().is_ok_and(|s| wanted.contains(&s)));
            if found.is_none() {
                self.log.say("No call is in a state for that.");
            }
            found
        }

        fn act(
            &mut self,
            what: &str,
            wanted: &[PhoneCallStatus],
            action: impl Fn(&PhoneCall) -> Result<PhoneCallOperationStatus>,
        ) {
            if let Some(call) = self.first(wanted) {
                let outcome = action(&call);
                if let Some(status) = self.log.result(what, outcome) {
                    self.log.say(&format!("{what}: {}", operation_name(status)));
                }
            }
        }

        fn dial(&mut self, number: &str) {
            if number.is_empty() {
                self.log
                    .say("Type the number after d, for example: d 5855550123");
                return;
            }
            let Some(line) = self.line.clone() else {
                self.log.say("There is no phone line to dial on.");
                return;
            };
            self.log.say(&format!(
                "Dialing {} because you typed it.",
                mask_number(number)
            ));
            let outcome = line.DialWithResult(&HSTRING::from(number), &HSTRING::new());
            if let Some(result) = self.log.result("dialing", outcome) {
                match result.DialCallStatus() {
                    Ok(status) => self.log.say(&format!("dial: {}", operation_name(status))),
                    Err(error) => self
                        .log
                        .say(&format!("dial status unreadable: {:#010x}", error.code().0)),
                }
            }
        }

        fn command(&mut self, line: &str) -> bool {
            use PhoneCallStatus as S;
            let (command, rest) = parse_command(line);
            self.log.say(&format!(
                "> {command}{}",
                if rest.is_empty() { "" } else { " ..." }
            ));
            match command.as_str() {
                "" | "s" => self.report(true),
                "?" | "help" => self.log.say(HELP),
                "d" => self.dial(&rest),
                "a" => self.act("answer", &[S::Incoming], |c| c.AcceptIncoming()),
                "r" => self.act("reject", &[S::Incoming], |c| c.RejectIncoming()),
                "e" => self.act(
                    "end",
                    &[S::Talking, S::Dialing, S::Held, S::Incoming],
                    |c| c.End(),
                ),
                "h" => self.act("hold", &[S::Talking], |c| c.Hold()),
                "u" => self.act("resume", &[S::Held], |c| c.ResumeFromHold()),
                "m" => self.act("mute", &[S::Talking], |c| c.Mute()),
                "n" => self.act("unmute", &[S::Talking], |c| c.Unmute()),
                "pc" => self.act("voice to the PC", &[S::Talking, S::Dialing, S::Held], |c| {
                    c.ChangeAudioDevice(PhoneCallAudioDevice::LocalDevice)
                }),
                "ph" => self.act(
                    "voice to the phone",
                    &[S::Talking, S::Dialing, S::Held],
                    |c| c.ChangeAudioDevice(PhoneCallAudioDevice::RemoteDevice),
                ),
                "k" => match dtmf_key(&rest) {
                    Some(key) => self.act("keypad tone", &[S::Talking], move |c| {
                        c.SendDtmfKey(key, DtmfToneAudioPlayback::Play)
                    }),
                    None => self.log.say("Type one key after k: 0-9, * or #"),
                },
                "q" | "quit" | "exit" => return false,
                _ => self.log.say("Unknown command. Type ? for the list."),
            }
            true
        }
    }

    fn watch_input(tx: Sender<Event>) {
        std::thread::spawn(move || {
            for line in io::stdin().lock().lines() {
                match line {
                    Ok(line) => {
                        if tx.send(Event::Typed(line)).is_err() {
                            return;
                        }
                    }
                    Err(_) => break,
                }
            }
            let _ = tx.send(Event::InputClosed);
        });
    }

    struct Phone {
        device: PhoneLineTransportDevice,
        registered_here: bool,
    }

    /// Steps 2 and 3: ask for access, register, connect.
    fn open_phone(log: &mut Log, info: &DeviceInformation) -> Option<Phone> {
        let id = log.result("reading the device id", info.Id())?;
        let device = log.result(
            "opening the call device",
            PhoneLineTransportDevice::FromId(&id),
        )?;
        if let Ok(transport) = device.Transport() {
            log.say(&format!("        transport: {}", transport_name(transport)));
        }

        let access = device.RequestAccessAsync().and_then(|op| op.join());
        match log.result("asking for access", access) {
            Some(status) => log.say(&format!("STEP 2  access: {}", access_name(status))),
            None => log.say("STEP 2  access: the request itself failed"),
        }
        let was_registered = log
            .result("reading the registration", device.IsRegistered())
            .unwrap_or(false);
        log.say(&format!(
            "        registered before this run: {was_registered}"
        ));
        let mut registered_here = false;
        if !was_registered
            && log
                .result("registering for the phone's calls", device.RegisterApp())
                .is_some()
        {
            registered_here = true;
            log.say("        registered now: true");
        }
        let connected = device.ConnectAsync().and_then(|op| op.join());
        match log.result("connecting to the phone", connected) {
            Some(ok) => log.say(&format!("STEP 3  connected to the phone: {ok}")),
            None => log.say("STEP 3  connected to the phone: the request itself failed"),
        }
        if let Some(status) = log.result("reading the voice routing", device.AudioRoutingStatus()) {
            log.say(&format!("        voice routing: {}", routing_name(status)));
        }
        if let Some(ringing) = log.result("reading in-band ringing", device.InBandRingingEnabled())
        {
            log.say(&format!(
                "        phone plays its own ringtone through the PC: {ringing}"
            ));
        }
        Some(Phone {
            device,
            registered_here,
        })
    }

    pub fn run() -> i32 {
        let mut log = Log::open();
        log.say("calls-probe: can this PC use the phone's calls over Bluetooth?");
        // `--no-phone` carries on with no phone paired, to prove the rest of
        // the program runs before anyone sits down with a phone.
        let dry_run = std::env::args().any(|arg| arg == "--no-phone");

        // 1. Is the phone listed as a hands-free call device?
        let selector = PhoneLineTransportDevice::GetDeviceSelectorForPhoneLineTransport(
            PhoneLineTransport::Bluetooth,
        );
        let Some(selector) = log.result("building the device query", selector) else {
            return 1;
        };
        let found = DeviceInformation::FindAllAsyncAqsFilter(&selector).and_then(|op| op.join());
        let Some(found) = log.result("listing call devices", found) else {
            return 1;
        };
        let devices: Vec<DeviceInformation> = found.into_iter().collect();
        log.say(&format!(
            "STEP 1  Bluetooth call devices listed by Windows: {}",
            devices.len()
        ));
        if devices.len() > 1 {
            log.say("More than one; using the first. Unpair the others if that is the wrong one.");
        }
        let phone = match devices.first() {
            Some(info) => match open_phone(&mut log, info) {
                Some(phone) => Some(phone),
                None => return 1,
            },
            None if dry_run => {
                log.say("--no-phone: carrying on without a phone. Steps 2 and 3 are skipped.");
                None
            }
            None => {
                log.say(
                    "No phone is paired for calls. In Windows: Settings > Bluetooth & devices >\n\
                     Add device > Bluetooth, pick the phone, and accept the prompt on both.\n\
                     Then run this again. Nothing was changed.",
                );
                return 2;
            }
        };

        // 4. Which phone line belongs to it?
        let device_id = phone
            .as_ref()
            .and_then(|phone| phone.device.DeviceId().ok())
            .map(|id| id.to_string())
            .unwrap_or_default();
        let mut line = None;
        let ids = find_lines(&mut log);
        log.say(&format!("STEP 4  phone lines Windows knows: {}", ids.len()));
        for id in ids {
            let Some(candidate) = log.result(
                "opening a phone line",
                PhoneLine::FromIdAsync(id).and_then(|op| op.join()),
            ) else {
                continue;
            };
            let transport = candidate
                .Transport()
                .map(transport_name)
                .unwrap_or("unknown");
            let ours = candidate
                .TransportDeviceId()
                .is_ok_and(|other| !device_id.is_empty() && other == device_id);
            let can_dial = candidate.CanDial().unwrap_or(false);
            log.say(&format!(
                "        line: {transport}, belongs to this phone: {ours}, can dial: {can_dial}"
            ));
            if ours && line.is_none() {
                line = Some(candidate);
            }
        }
        if line.is_none() {
            log.say("        No line belongs to the paired phone. Watching anyway.");
        }

        // 5. Watch, and take commands.
        let (tx, rx) = mpsc::channel::<Event>();
        let state_tx = tx.clone();
        let _ = log.result(
            "watching call state",
            PhoneCallManager::CallStateChanged(&EventHandler::<IInspectable>::new(move |_, _| {
                let active = PhoneCallManager::IsCallActive().unwrap_or(false);
                let incoming = PhoneCallManager::IsCallIncoming().unwrap_or(false);
                let _ = state_tx.send(Event::Note(format!(
                    "EVENT call state changed: active={active} ringing={incoming}"
                )));
                Ok(())
            })),
        );
        if let Some(phone) = phone.as_ref() {
            let routing_tx = tx.clone();
            let _ = log.result(
                "watching voice routing",
                phone.device.AudioRoutingStatusChanged(&TypedEventHandler::<
                    PhoneLineTransportDevice,
                    IInspectable,
                >::new(
                    move |sender, _| {
                        if let Some(sender) = sender.as_ref() {
                            let status = sender
                                .AudioRoutingStatus()
                                .map(routing_name)
                                .unwrap_or("unreadable");
                            let _ = routing_tx
                                .send(Event::Note(format!("EVENT voice routing: {status}")));
                        }
                        Ok(())
                    },
                )),
            );
        }
        watch_input(tx);

        let mut session = Session {
            log,
            line,
            seen: BTreeMap::new(),
        };
        session
            .log
            .say("STEP 5  Watching calls. Place or receive a call now.");
        session.log.say(HELP);
        session.report(true);
        loop {
            match rx.recv_timeout(Duration::from_millis(500)) {
                Ok(Event::Typed(line)) => {
                    if !session.command(&line) {
                        break;
                    }
                }
                Ok(Event::Note(text)) => session.log.say(&text),
                Ok(Event::InputClosed) | Err(RecvTimeoutError::Disconnected) => break,
                Err(RecvTimeoutError::Timeout) => {}
            }
            session.report(false);
        }

        if let Some(phone) = phone.as_ref().filter(|phone| phone.registered_here) {
            let undone = session
                .log
                .result("undoing the registration", phone.device.UnregisterApp());
            session
                .log
                .say(&format!("Registration undone: {}", undone.is_some()));
        }
        session.log.say(
            "Done. The Bluetooth pairing itself is untouched; remove it in Windows Settings if you want.",
        );
        0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_number_is_shown_only_by_its_length_and_last_two_digits() {
        assert_eq!(mask_number("+1 (585) 555-0123"), "11 digits ending 23");
        assert_eq!(mask_number("911"), "3 digits");
        assert_eq!(mask_number(""), "no number");
        assert_eq!(mask_number("Unknown"), "a number without digits");
        assert!(!mask_number("+15855550123").contains("5855550"));
    }

    #[test]
    fn a_name_is_shown_only_by_its_length() {
        assert_eq!(mask_name("Ada Lovelace"), "a name of 12 characters");
        assert_eq!(mask_name("  "), "no name");
    }

    #[test]
    fn a_typed_line_is_a_command_and_the_rest() {
        assert_eq!(
            parse_command("d  585 555 0123 "),
            ("d".to_string(), "585 555 0123".to_string())
        );
        assert_eq!(parse_command(" PC"), ("pc".to_string(), String::new()));
        assert_eq!(parse_command(""), (String::new(), String::new()));
    }
}
