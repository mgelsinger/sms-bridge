//! Every valid fixture in protocol/fixtures must decode into the Rust model and
//! encode back to exactly the same JSON.

use serde::{de::DeserializeOwned, Serialize};
use serde_json::Value;
use sms_bridge_core::protocol::*;
use std::fs;
use std::path::{Path, PathBuf};

fn fixture_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../protocol/fixtures")
}

fn round_trip<T: Serialize + DeserializeOwned>(text: &str) -> Result<(), String> {
    let expected: Value = serde_json::from_str(text).map_err(|e| e.to_string())?;
    let decoded: T = serde_json::from_str(text).map_err(|e| format!("decode: {e}"))?;
    let actual = serde_json::to_value(&decoded).map_err(|e| format!("encode: {e}"))?;
    if actual == expected {
        Ok(())
    } else {
        Err(format!(
            "re-encoded JSON differs\nexpected: {expected}\nactual:   {actual}"
        ))
    }
}

fn check(dir: &str, text: &str) -> Option<Result<(), String>> {
    Some(match dir {
        d if d.starts_with("ws-") => round_trip::<Envelope>(text),
        "message" => round_trip::<Message>(text),
        "thread" => round_trip::<ThreadSummary>(text),
        "contact" => round_trip::<Contact>(text),
        "contacts" => round_trip::<ContactsResponse>(text),
        "status" => round_trip::<StatusResponse>(text),
        "threads-page" => round_trip::<ThreadsPage>(text),
        "messages-page" => round_trip::<MessagesPage>(text),
        "sync-page" => round_trip::<SyncPage>(text),
        "pair-request" => round_trip::<PairRequest>(text),
        "pair-response" => round_trip::<PairResponse>(text),
        "pairing-qr" => round_trip::<PairingQr>(text),
        "error" => round_trip::<ErrorBody>(text),
        _ => return None,
    })
}

#[test]
fn every_valid_fixture_round_trips() {
    let mut failures = Vec::new();
    let mut checked = 0;
    for dir in fs::read_dir(fixture_root()).expect("fixtures dir") {
        let dir = dir.expect("entry").path();
        if !dir.is_dir() {
            continue;
        }
        let name = dir
            .file_name()
            .and_then(|n| n.to_str())
            .expect("utf8 name")
            .to_owned();
        for file in fs::read_dir(&dir).expect("fixture dir") {
            let file = file.expect("entry").path();
            if file.extension().and_then(|e| e.to_str()) != Some("json") {
                continue;
            }
            let text = fs::read_to_string(&file).expect("read fixture");
            match check(&name, &text) {
                None => failures.push(format!("{name}: no Rust model mapped")),
                Some(Err(e)) => failures.push(format!("{}: {e}", file.display())),
                Some(Ok(())) => checked += 1,
            }
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n\n"));
    assert!(
        checked >= 30,
        "expected the full fixture set, checked only {checked}"
    );
}

#[test]
fn send_frame_omits_the_unused_target() {
    let frame = Envelope::new(Frame::Send {
        client_id: "3f0e6c1a-9b2d-4c7e-8a51-2d6f0b9e4c13".into(),
        thread_id: Some(11),
        to: None,
        body: "hi".into(),
    });
    let json = serde_json::to_string(&frame).expect("encode");
    assert!(!json.contains("\"to\""), "{json}");
    assert!(json.contains("\"type\":\"send\""), "{json}");
}

#[test]
fn unknown_fields_from_a_newer_phone_are_ignored() {
    let text =
        r#"{"protocol_version":1,"type":"thread_updated","thread_id":4,"added_in_v1_5":true}"#;
    let env: Envelope = serde_json::from_str(text).expect("lenient parse");
    assert_eq!(env.frame, Frame::ThreadUpdated { thread_id: 4 });
}
