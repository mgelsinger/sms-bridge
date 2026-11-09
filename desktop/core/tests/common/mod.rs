//! Launches tools/mock-phone for integration tests.
#![allow(dead_code)]

use sms_bridge_core::net::PhoneAddress;
use std::io::{BufRead, BufReader};
use std::path::Path;
use std::process::{Child, Command, Stdio};

pub const CODE: &str = "135790";

pub struct MockPhone {
    child: Child,
    pub address: PhoneAddress,
}

impl Drop for MockPhone {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

pub fn start_mock(extra_env: &[(&str, &str)]) -> MockPhone {
    let server =
        Path::new(env!("CARGO_MANIFEST_DIR")).join("../../tools/mock-phone/src/server.mjs");
    let mut cmd = Command::new("node");
    cmd.arg(&server)
        .env("MOCK_PORT", "0")
        .env("MOCK_PAIRING_CODE", CODE)
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit());
    for (k, v) in extra_env {
        cmd.env(k, v);
    }
    let mut child = cmd
        .spawn()
        .expect("node must be installed to run these tests");
    let stdout = child.stdout.take().expect("stdout");
    let mut lines = BufReader::new(stdout).lines();
    let (mut port, mut fingerprint) = (None, None);
    while port.is_none() || fingerprint.is_none() {
        let line = lines
            .next()
            .expect("mock phone exited early")
            .expect("read line");
        if let Some(rest) = line.split("https://127.0.0.1:").nth(1) {
            port = rest.trim().parse::<u16>().ok();
        }
        if let Some(fp) = line.strip_prefix("fingerprint") {
            fingerprint = Some(fp.trim().to_owned());
        }
    }
    // Keep draining stdout so the mock never blocks on a full pipe.
    std::thread::spawn(move || for _ in lines {});
    MockPhone {
        child,
        address: PhoneAddress {
            host: "127.0.0.1".into(),
            port: port.expect("port"),
            fingerprint: fingerprint.expect("fingerprint"),
        },
    }
}
