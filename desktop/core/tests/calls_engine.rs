//! Calls through the sync engine, against the Node mock phone.

mod common;

use common::{start_mock, MockPhone, CODE};
use sms_bridge_core::net::{PhoneAddress, PhoneClient};
use sms_bridge_core::protocol::*;
use sms_bridge_core::store::Store;
use sms_bridge_core::sync::{self, Command, ConnState, Events, Line};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::mpsc::UnboundedSender;

#[derive(Default)]
struct Recorder {
    states: Mutex<Vec<ConnState>>,
    lines: Mutex<Vec<Line>>,
    missed: Mutex<Vec<Call>>,
    results: Mutex<Vec<(String, Option<String>)>>,
    calls_changed: Mutex<u32>,
}

impl Events for Recorder {
    fn state(&self, state: &ConnState) {
        self.states.lock().expect("lock").push(state.clone());
    }
    fn threads_changed(&self) {}
    fn thread_changed(&self, _: u64) {}
    fn inbound(&self, _: &Message) {}
    fn status(&self, _: &DeviceStatus) {}
    fn calls_changed(&self) {
        *self.calls_changed.lock().expect("lock") += 1;
    }
    fn line(&self, line: &Line) {
        self.lines.lock().expect("lock").push(line.clone());
    }
    fn missed_call(&self, call: &Call) {
        self.missed.lock().expect("lock").push(call.clone());
    }
    fn call_result(&self, client_id: &str, error: Option<&str>) {
        self.results
            .lock()
            .expect("lock")
            .push((client_id.to_owned(), error.map(str::to_owned)));
    }
}

impl Recorder {
    fn connected(&self) -> bool {
        matches!(
            self.states.lock().expect("lock").last(),
            Some(ConnState::Connected { .. })
        )
    }
    fn line_state(&self) -> Option<LineState> {
        self.lines.lock().expect("lock").last().map(|l| l.state)
    }
    fn result_of(&self, client_id: &str) -> Option<Option<String>> {
        self.results
            .lock()
            .expect("lock")
            .iter()
            .find(|(id, _)| id == client_id)
            .map(|(_, error)| error.clone())
    }
}

async fn eventually(what: &str, mut check: impl FnMut() -> bool) {
    for _ in 0..300 {
        if check() {
            return;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    panic!("timed out waiting for: {what}");
}

struct Rig {
    _mock: MockPhone,
    store: Arc<Mutex<Store>>,
    events: Arc<Recorder>,
    commands: UnboundedSender<Command>,
}

async fn rig(env: &[(&str, &str)]) -> Rig {
    let mock = start_mock(env);
    let token = PhoneClient::new(mock.address.clone(), None)
        .expect("client")
        .pair(CODE, "CALLS-TEST")
        .await
        .expect("pair")
        .token;
    let client = PhoneClient::new(mock.address.clone(), Some(token)).expect("client");
    let store = Arc::new(Mutex::new(Store::open_in_memory().expect("store")));
    let events = Arc::new(Recorder::default());
    let commands = sync::spawn(client, store.clone(), events.clone());
    eventually("connected", || events.connected()).await;
    Rig {
        _mock: mock,
        store,
        events,
        commands,
    }
}

fn id(n: u8) -> String {
    format!("00000000-0000-4000-8000-0000000000{n:02x}")
}

#[tokio::test(flavor = "multi_thread")]
async fn the_history_is_synced_once_and_old_calls_are_not_news() {
    let rig = rig(&[]).await;
    {
        let s = rig.store.lock().expect("lock");
        let calls = s.calls(None, 50).expect("calls");
        assert_eq!(
            calls.iter().map(|c| c.id.as_str()).collect::<Vec<_>>(),
            ["call:415", "call:414", "call:413", "call:412"],
            "newest first"
        );
        assert_eq!(calls[3].name.as_deref(), None);
        assert_eq!(
            s.missed_calls_unseen().expect("count"),
            0,
            "a missed call in the history of a newly paired phone is not counted"
        );
        assert_eq!(
            s.meta(sync::META_CALLS_CURSOR).expect("meta").as_deref(),
            Some("c415")
        );
    }
    assert_eq!(*rig.events.calls_changed.lock().expect("lock"), 1);
    assert_eq!(
        rig.events.line_state(),
        Some(LineState::Idle),
        "the line is known right after connecting"
    );
    assert!(rig.events.missed.lock().expect("lock").is_empty());
    rig.commands.send(Command::Stop).expect("stop");
}

#[tokio::test(flavor = "multi_thread")]
async fn a_ringing_call_is_answered_and_ended_from_the_pc() {
    let rig = rig(&[("MOCK_RING_EVERY_S", "2"), ("MOCK_RING_FOR_S", "30")]).await;
    eventually("the phone rings", || {
        rig.events.line_state() == Some(LineState::Ringing)
    })
    .await;
    {
        let lines = rig.events.lines.lock().expect("lock");
        let ringing = lines.last().expect("line");
        assert_eq!(ringing.direction, Some(Direction::In));
        assert_eq!(ringing.number.as_deref(), Some("+15855550101"));
    }
    let before = rig
        .store
        .lock()
        .expect("lock")
        .calls(None, 50)
        .expect("calls")
        .len();

    rig.commands
        .send(Command::CallAction {
            client_id: id(1),
            action: CallAction::Answer,
        })
        .expect("answer");
    eventually("the answer is confirmed", || {
        rig.events.result_of(&id(1)).is_some()
    })
    .await;
    assert_eq!(rig.events.result_of(&id(1)), Some(None), "no error");
    eventually("the call is active", || {
        rig.events.line_state() == Some(LineState::Active)
    })
    .await;

    rig.commands
        .send(Command::CallAction {
            client_id: id(2),
            action: CallAction::End,
        })
        .expect("end");
    eventually("the line is idle again", || {
        rig.events.line_state() == Some(LineState::Idle)
    })
    .await;
    eventually("the call is in the history", || {
        rig.store
            .lock()
            .expect("lock")
            .calls(None, 50)
            .expect("calls")
            .len()
            == before + 1
    })
    .await;
    let newest = rig
        .store
        .lock()
        .expect("lock")
        .calls(None, 1)
        .expect("calls");
    assert_eq!(newest[0].kind, CallKind::In);
    assert_eq!(newest[0].number.as_deref(), Some("+15855550101"));
    assert!(
        rig.events.missed.lock().expect("lock").is_empty(),
        "an answered call is not a missed call"
    );
    rig.commands.send(Command::Stop).expect("stop");
}

#[tokio::test(flavor = "multi_thread")]
async fn a_call_nobody_answers_counts_as_missed_until_the_history_is_opened() {
    let rig = rig(&[("MOCK_RING_EVERY_S", "1"), ("MOCK_RING_FOR_S", "1")]).await;
    eventually("a missed call is reported", || {
        !rig.events.missed.lock().expect("lock").is_empty()
    })
    .await;
    let missed = rig.events.missed.lock().expect("lock")[0].clone();
    assert_eq!(missed.kind, CallKind::Missed);
    let s = rig.store.lock().expect("lock");
    assert!(s.missed_calls_unseen().expect("count") >= 1);
    s.mark_calls_seen().expect("seen");
    assert_eq!(s.missed_calls_unseen().expect("count"), 0);
    drop(s);
    rig.commands.send(Command::Stop).expect("stop");
}

#[tokio::test(flavor = "multi_thread")]
async fn dialing_places_a_call_and_the_phone_refuses_emergency_numbers() {
    let rig = rig(&[]).await;
    rig.commands
        .send(Command::Dial {
            client_id: id(3),
            to: "911".into(),
        })
        .expect("dial");
    eventually("the refusal arrives", || {
        rig.events.result_of(&id(3)).is_some()
    })
    .await;
    assert_eq!(
        rig.events.result_of(&id(3)),
        Some(Some("emergency numbers are dialed on the phone".into()))
    );
    assert_eq!(rig.events.line_state(), Some(LineState::Idle));

    rig.commands
        .send(Command::Dial {
            client_id: id(4),
            to: "+15855550177".into(),
        })
        .expect("dial");
    eventually("the dial is confirmed", || {
        rig.events.result_of(&id(4)).is_some()
    })
    .await;
    assert_eq!(rig.events.result_of(&id(4)), Some(None));
    eventually("the line is off the hook", || {
        rig.events.line_state() == Some(LineState::Active)
    })
    .await;
    {
        let lines = rig.events.lines.lock().expect("lock");
        let active = lines.last().expect("line");
        assert_eq!(active.direction, Some(Direction::Out));
        assert_eq!(active.number.as_deref(), Some("+15855550177"));
    }

    // Losing the connection must not leave a call on screen.
    rig.commands.send(Command::Stop).expect("stop");
    eventually("stopped", || {
        rig.events.states.lock().expect("lock").last() == Some(&ConnState::Stopped)
    })
    .await;
    assert_eq!(rig.events.line_state(), Some(LineState::Idle));
}

/// A dial is never kept for later: with no connection it fails at once, and
/// nothing is sent when the phone comes back.
#[tokio::test(flavor = "multi_thread")]
async fn a_dial_made_while_offline_fails_at_once_and_is_never_sent_later() {
    // Nothing listens here, so the engine never connects.
    let nowhere = PhoneAddress {
        host: "127.0.0.1".into(),
        port: 9,
        fingerprint: "0".repeat(64),
    };
    let client = PhoneClient::new(nowhere, Some("D".repeat(43)))
        .expect("client")
        .with_timeouts(Duration::from_secs(1), Duration::from_secs(1));
    let store = Arc::new(Mutex::new(Store::open_in_memory().expect("store")));
    let events = Arc::new(Recorder::default());
    let commands = sync::spawn(client, store, events.clone());

    commands
        .send(Command::Dial {
            client_id: id(5),
            to: "+15855550177".into(),
        })
        .expect("dial");
    commands
        .send(Command::CallAction {
            client_id: id(6),
            action: CallAction::End,
        })
        .expect("end");
    eventually("both are refused", || {
        events.result_of(&id(5)).is_some() && events.result_of(&id(6)).is_some()
    })
    .await;
    assert_eq!(
        events.result_of(&id(5)),
        Some(Some("not connected to the phone".into()))
    );
    assert_eq!(
        events.result_of(&id(6)),
        Some(Some("not connected to the phone".into()))
    );
    commands.send(Command::Stop).expect("stop");
}

#[tokio::test(flavor = "multi_thread")]
async fn a_phone_from_before_calls_still_connects_and_has_no_calls() {
    let rig = rig(&[("MOCK_NO_CALLS", "1")]).await;
    {
        let s = rig.store.lock().expect("lock");
        assert!(s.calls(None, 10).expect("calls").is_empty());
        assert_eq!(s.meta(sync::META_CALLS_CURSOR).expect("meta"), None);
        assert_eq!(s.message_count().expect("count"), 8, "texts work as before");
    }
    assert!(rig.events.lines.lock().expect("lock").is_empty());
    assert_eq!(*rig.events.calls_changed.lock().expect("lock"), 0);
    rig.commands.send(Command::Stop).expect("stop");
}
