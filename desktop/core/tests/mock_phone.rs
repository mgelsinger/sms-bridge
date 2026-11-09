//! The Rust client against the Node mock phone in tools/mock-phone: two
//! independent implementations of protocol v1 checked against each other.
//! Needs Node 20+ and `npm ci` at the repository root.

use futures_util::{SinkExt, StreamExt};
use sms_bridge_core::net::{probe_fingerprint, NetError, PhoneAddress, PhoneClient};
use sms_bridge_core::protocol::*;
use std::time::Duration;
use tokio_tungstenite::tungstenite::Message as WsMessage;

mod common;
use common::{start_mock, CODE};

async fn next_frame(ws: &mut sms_bridge_core::net::PhoneSocket) -> Frame {
    loop {
        let msg = tokio::time::timeout(Duration::from_secs(5), ws.next())
            .await
            .expect("frame within 5 s")
            .expect("socket open")
            .expect("frame ok");
        if let WsMessage::Text(text) = msg {
            let env: Envelope = serde_json::from_str(&text).expect("valid frame");
            assert_eq!(env.protocol_version, PROTOCOL_VERSION);
            return env.frame;
        }
    }
}

#[tokio::test]
async fn pair_read_and_send_against_the_mock_phone() {
    let mock = start_mock(&[]);

    // Pair without a token.
    let anonymous = PhoneClient::new(mock.address.clone(), None).expect("client");
    let paired = anonymous
        .pair(CODE, "RUST-TEST")
        .await
        .expect("pairing succeeds");
    assert_eq!(paired.token.len(), 43);
    let again = anonymous.pair(CODE, "RUST-TEST").await;
    assert!(
        matches!(
            again,
            Err(NetError::Refused {
                code: ErrorCode::PairingInvalid,
                ..
            })
        ),
        "a code works once: {again:?}"
    );

    let client = PhoneClient::new(mock.address.clone(), Some(paired.token)).expect("client");

    // Reads.
    let status = client.status().await.expect("status");
    assert_eq!(status.status.paired_clients, 1);
    let threads = client.threads(50, None).await.expect("threads");
    assert_eq!(
        threads.threads.iter().map(|t| t.id).collect::<Vec<_>>(),
        vec![11, 13, 14, 12]
    );
    let first = client.threads(3, None).await.expect("page 1");
    let rest = client
        .threads(3, first.next_cursor.as_deref())
        .await
        .expect("page 2");
    assert_eq!(rest.threads.len(), 1);
    let group = client
        .thread_messages(13, 100, None)
        .await
        .expect("thread 13");
    let part = &group
        .messages
        .iter()
        .flat_map(|m| &m.parts)
        .next()
        .expect("an MMS part")
        .clone();
    let (ct, bytes) = client.mms_part(part.part_id).await.expect("part bytes");
    assert_eq!(ct, part.content_type);
    assert_eq!(Some(bytes.len() as u64), part.size);
    assert_eq!(client.contacts().await.expect("contacts").contacts.len(), 3);
    let start = client.sync(None, 100).await.expect("sync from now");
    assert!(start.messages.is_empty());

    // WebSocket: hello, ping, send to a thread.
    let mut ws = client.websocket().await.expect("websocket");
    let Frame::Hello { sync_cursor, .. } = next_frame(&mut ws).await else {
        panic!("hello first")
    };
    assert_eq!(sync_cursor, start.next_cursor);
    ws.send(WsMessage::text(
        serde_json::to_string(&Envelope::new(Frame::Ping)).expect("json"),
    ))
    .await
    .expect("send ping");
    assert_eq!(next_frame(&mut ws).await, Frame::Pong);

    let client_id = uuid::Uuid::new_v4().to_string();
    let send = Envelope::new(Frame::Send {
        client_id: client_id.clone(),
        thread_id: Some(11),
        to: None,
        body: "From Rust".into(),
    });
    ws.send(WsMessage::text(serde_json::to_string(&send).expect("json")))
        .await
        .expect("send");
    let mut statuses = Vec::new();
    let mut echoed = None;
    while statuses.last() != Some(&SendStatus::Delivered) {
        match next_frame(&mut ws).await {
            Frame::SendResult {
                client_id: id,
                status,
                ..
            } if id == client_id => statuses.push(status),
            Frame::Message { message } => echoed = Some(message),
            other => panic!("unexpected frame {other:?}"),
        }
    }
    assert_eq!(
        statuses,
        vec![SendStatus::Queued, SendStatus::Sent, SendStatus::Delivered]
    );
    let echoed = echoed.expect("the sent message is echoed");
    assert_eq!(echoed.client_id.as_deref(), Some(client_id.as_str()));
    assert_eq!(echoed.direction, Direction::Out);

    // The sent message shows up in incremental sync too.
    let after = client
        .sync(Some(&start.next_cursor), 100)
        .await
        .expect("sync");
    assert_eq!(
        after
            .messages
            .iter()
            .map(|m| m.id.clone())
            .collect::<Vec<_>>(),
        vec![echoed.id]
    );
}

#[tokio::test]
async fn a_different_certificate_is_refused() {
    let mock = start_mock(&[]);
    let wrong = PhoneAddress {
        fingerprint: "0".repeat(64),
        ..mock.address.clone()
    };
    let client = PhoneClient::new(wrong, None).expect("client");
    let err = client.status().await.expect_err("must refuse");
    assert!(matches!(err, NetError::FingerprintMismatch), "{err:?}");
    let err = client.websocket().await.expect_err("must refuse");
    assert!(matches!(err, NetError::FingerprintMismatch), "{err:?}");
}

#[tokio::test]
async fn an_unknown_token_is_unauthorized() {
    let mock = start_mock(&[]);
    let client = PhoneClient::new(mock.address.clone(), Some("A".repeat(43))).expect("client");
    let err = client.threads(10, None).await.expect_err("401");
    assert!(err.is_unauthorized(), "{err:?}");
    let err = client.websocket().await.expect_err("401");
    assert!(err.is_unauthorized(), "{err:?}");
}

#[tokio::test]
async fn probing_reads_the_fingerprint_without_sending_anything() {
    let mock = start_mock(&[]);
    let seen = probe_fingerprint(&mock.address.host, mock.address.port)
        .await
        .expect("probe");
    assert_eq!(seen, mock.address.fingerprint);
}
