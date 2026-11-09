//! Wire models for protocol v1. `protocol/PROTOCOL.md` and `protocol/schema`
//! are the source of truth; `tests/fixtures.rs` round-trips every fixture.
//!
//! Parsing is lenient (unknown fields are ignored) so a newer phone stays
//! readable; what we emit matches the strict schemas.

use serde::{Deserialize, Serialize};

pub const PROTOCOL_VERSION: u32 = 1;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MessageKind {
    Sms,
    Mms,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Direction {
    In,
    Out,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MessageStatus {
    Pending,
    Sent,
    Delivered,
    Failed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SendStatus {
    Queued,
    Sent,
    Delivered,
    Failed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SimState {
    Ready,
    Absent,
    Locked,
    Unknown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ErrorCode {
    BadRequest,
    BadCursor,
    Unauthorized,
    PairingInvalid,
    PairingRejected,
    RateLimited,
    NotFound,
    UnsupportedVersion,
    NotDefaultSmsApp,
    SendFailed,
    Internal,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MmsPart {
    pub part_id: u64,
    pub content_type: String,
    pub size: u64,
    pub name: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Message {
    /// Opaque, `sms:N` or `mms:N`. Compare for equality only.
    pub id: String,
    pub thread_id: u64,
    pub kind: MessageKind,
    pub direction: Direction,
    pub address: String,
    pub participants: Vec<String>,
    pub body: Option<String>,
    pub parts: Vec<MmsPart>,
    /// Epoch milliseconds.
    pub date: u64,
    pub read: bool,
    pub status: Option<MessageStatus>,
    pub client_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ThreadSummary {
    pub id: u64,
    pub participants: Vec<String>,
    pub snippet: Option<String>,
    pub date: u64,
    pub unread_count: u32,
    pub message_count: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Contact {
    pub id: u64,
    pub name: String,
    pub numbers: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DeviceStatus {
    pub device_name: String,
    pub battery_percent: Option<u8>,
    pub charging: Option<bool>,
    pub signal_level: Option<u8>,
    pub sim_state: SimState,
    pub is_default_sms_app: bool,
    pub uptime_s: u64,
    pub paired_clients: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StatusResponse {
    pub protocol_version: u32,
    #[serde(flatten)]
    pub status: DeviceStatus,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ThreadsPage {
    pub protocol_version: u32,
    pub threads: Vec<ThreadSummary>,
    pub next_cursor: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MessagesPage {
    pub protocol_version: u32,
    pub thread_id: u64,
    pub messages: Vec<Message>,
    pub next_cursor: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SyncPage {
    pub protocol_version: u32,
    pub messages: Vec<Message>,
    pub next_cursor: String,
    pub has_more: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ContactsResponse {
    pub protocol_version: u32,
    pub contacts: Vec<Contact>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PairRequest {
    pub protocol_version: u32,
    pub pairing_code: String,
    pub client_name: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PairResponse {
    pub protocol_version: u32,
    pub token: String,
    pub device_name: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PairingQr {
    pub protocol_version: u32,
    pub host: String,
    pub port: u16,
    /// Lowercase hex SHA-256 of the phone's DER certificate.
    pub fingerprint: String,
    pub pairing_code: String,
    pub device_name: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ErrorDetail {
    pub code: ErrorCode,
    pub message: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ErrorBody {
    pub protocol_version: u32,
    pub error: ErrorDetail,
}

/// A WebSocket frame in either direction.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Envelope {
    pub protocol_version: u32,
    #[serde(flatten)]
    pub frame: Frame,
}

impl Envelope {
    pub fn new(frame: Frame) -> Self {
        Self {
            protocol_version: PROTOCOL_VERSION,
            frame,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Frame {
    // Phone to client.
    Hello {
        device_name: String,
        sync_cursor: String,
        is_default_sms_app: bool,
    },
    Message {
        message: Message,
    },
    SendResult {
        client_id: String,
        message_id: Option<String>,
        status: SendStatus,
        #[serde(default)]
        error: Option<String>,
    },
    ThreadUpdated {
        thread_id: u64,
    },
    Status {
        status: DeviceStatus,
    },
    Pong,
    Error {
        code: ErrorCode,
        message: String,
        #[serde(default)]
        client_id: Option<String>,
    },
    // Client to phone.
    Send {
        client_id: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        thread_id: Option<u64>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        to: Option<Vec<String>>,
        body: String,
    },
    MarkRead {
        thread_id: u64,
    },
    Ping,
}
