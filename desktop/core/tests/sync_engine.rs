//! The sync engine against the Node mock phone.

mod common;

use common::{start_mock, CODE};
use sms_bridge_core::net::PhoneClient;
use sms_bridge_core::protocol::*;
use sms_bridge_core::store::Store;
use sms_bridge_core::sync::{self, Command, ConnState, Events};
use std::sync::{Arc, Mutex};
use std::time::Duration;

#[derive(Default)]
struct Recorder {
    states: Mutex<Vec<ConnState>>,
    inbound: Mutex<Vec<Message>>,
}

impl Events for Recorder {
    fn state(&self, state: &ConnState) {
        self.states.lock().expect("lock").push(state.clone());
    }
    fn threads_changed(&self) {}
    fn thread_changed(&self, _: u64) {}
    fn inbound(&self, message: &Message) {
        self.inbound.lock().expect("lock").push(message.clone());
    }
    fn status(&self, _: &DeviceStatus) {}
}

impl Recorder {
    fn last(&self) -> Option<ConnState> {
        self.states.lock().expect("lock").last().cloned()
    }
}

async fn eventually(what: &str, mut check: impl FnMut() -> bool) {
    for _ in 0..200 {
        if check() {
            return;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    panic!("timed out waiting for: {what}");
}

#[tokio::test(flavor = "multi_thread")]
async fn sync_receive_send_and_replay_after_reconnect() {
    let mock = start_mock(&[("MOCK_INBOUND_EVERY_S", "2")]);
    let token = PhoneClient::new(mock.address.clone(), None)
        .expect("client")
        .pair(CODE, "SYNC-TEST")
        .await
        .expect("pair")
        .token;
    let client = PhoneClient::new(mock.address.clone(), Some(token.clone())).expect("client");
    let store = Arc::new(Mutex::new(Store::open_in_memory().expect("store")));
    let events = Arc::new(Recorder::default());
    let commands = sync::spawn(client, store.clone(), events.clone());

    // Initial sync pulls every fixture thread and message.
    eventually("connected", || {
        matches!(events.last(), Some(ConnState::Connected { .. }))
    })
    .await;
    {
        let s = store.lock().expect("lock");
        let threads = s.threads().expect("threads");
        assert_eq!(
            threads.iter().map(|t| t.id).collect::<Vec<_>>(),
            vec![11, 13, 14, 12]
        );
        assert_eq!(threads[0].title, "Alex Rivera", "contact names are applied");
        assert_eq!(s.message_count().expect("count"), 8);
        assert!(s.meta(sync::META_SYNC_CURSOR).expect("meta").is_some());
    }

    // A live inbound text arrives and is reported for a notification.
    eventually("a simulated inbound text", || {
        !events.inbound.lock().expect("lock").is_empty()
    })
    .await;
    let first = events.inbound.lock().expect("lock")[0].clone();
    assert!(first
        .body
        .as_deref()
        .unwrap_or_default()
        .starts_with("Simulated text"));

    // Send through to delivered.
    commands
        .send(Command::Send {
            thread_id: Some(12),
            to: None,
            body: "Sent by the engine".into(),
        })
        .expect("command");
    eventually("delivered", || {
        let s = store.lock().expect("lock");
        s.messages(12, None, 10)
            .expect("messages")
            .first()
            .map(|m| (m.body.clone(), m.status))
            == Some((
                Some("Sent by the engine".into()),
                Some(MessageStatus::Delivered),
            ))
    })
    .await;

    // The phone goes away; a send made meanwhile waits in the outbox.
    let address = mock.address.clone();
    drop(mock);
    eventually("offline", || {
        matches!(events.last(), Some(ConnState::Offline { .. }))
    })
    .await;
    commands
        .send(Command::Send {
            thread_id: Some(12),
            to: None,
            body: "Written while offline".into(),
        })
        .expect("command");
    eventually("queued while offline", || {
        store
            .lock()
            .expect("lock")
            .outbox_unconfirmed()
            .expect("outbox")
            .len()
            == 1
    })
    .await;
    {
        let s = store.lock().expect("lock");
        let top = s.messages(12, None, 1).expect("messages");
        assert_eq!(top[0].status, Some(MessageStatus::Pending));
        assert!(top[0].id.starts_with("outbox:"));
    }

    // The phone comes back on the same address with the same pairing.
    let port = address.port.to_string();
    let _mock2 = start_mock(&[
        ("MOCK_PORT", port.as_str()),
        ("MOCK_TOKEN", token.as_str()),
        ("MOCK_FIRST_SMS_ID", "5000"),
    ]);
    commands.send(Command::Reconnect).expect("command");
    eventually("replayed and delivered", || {
        let s = store.lock().expect("lock");
        s.outbox_unconfirmed().expect("outbox").is_empty()
            && s.messages(12, None, 5).expect("messages").iter().any(|m| {
                m.body.as_deref() == Some("Written while offline")
                    && m.status == Some(MessageStatus::Delivered)
            })
    })
    .await;

    commands.send(Command::Stop).expect("stop");
    eventually("stopped", || events.last() == Some(ConnState::Stopped)).await;
}

#[tokio::test(flavor = "multi_thread")]
async fn a_revoked_token_stops_retrying_and_reports_unauthorized() {
    let mock = start_mock(&[]);
    let client = PhoneClient::new(mock.address.clone(), Some("C".repeat(43))).expect("client");
    let store = Arc::new(Mutex::new(Store::open_in_memory().expect("store")));
    let events = Arc::new(Recorder::default());
    let commands = sync::spawn(client, store, events.clone());
    eventually("unauthorized", || {
        events.last() == Some(ConnState::Unauthorized)
    })
    .await;
    tokio::time::sleep(Duration::from_millis(500)).await;
    let attempts = events
        .states
        .lock()
        .expect("lock")
        .iter()
        .filter(|s| **s == ConnState::Connecting)
        .count();
    assert_eq!(attempts, 1, "no automatic retries after a 401");
    commands.send(Command::Stop).expect("stop");
}
