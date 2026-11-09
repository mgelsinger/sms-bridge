//! Keeps the local store in step with the phone and carries sends out.
//!
//! One long-running task per paired phone: connect the WebSocket, read
//! `hello`, catch up over REST from the stored cursor, replay unconfirmed sends,
//! then apply live frames until the connection drops. Reconnects with backoff.

use crate::net::{NetError, PhoneClient, PhoneSocket};
use crate::protocol::*;
use crate::store::Store;
use futures_util::stream::{SplitSink, SplitStream};
use futures_util::{SinkExt, StreamExt};
use serde::Serialize;
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::sync::mpsc;
use tokio::time::{interval, timeout, Instant, MissedTickBehavior};
use tokio_tungstenite::tungstenite::Message as WsMessage;

const PING_EVERY: Duration = Duration::from_secs(30);
const PONG_WITHIN: Duration = Duration::from_secs(10);
const MAX_BACKOFF: Duration = Duration::from_secs(60);
const SYNC_PAGE: u32 = 200;
const INITIAL_MESSAGES_PER_THREAD: u32 = 100;
pub const META_SYNC_CURSOR: &str = "sync_cursor";

pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "state", rename_all = "snake_case")]
pub enum ConnState {
    Connecting,
    Syncing,
    Connected {
        device_name: String,
    },
    Offline {
        reason: String,
        retry_in_s: u64,
    },
    /// The phone presented a different certificate. Never retried automatically.
    FingerprintMismatch,
    /// The phone no longer knows this PC's token. Pair again.
    Unauthorized,
    Stopped,
}

/// What the engine tells the app about. Implementations must not block.
pub trait Events: Send + Sync + 'static {
    fn state(&self, state: &ConnState);
    fn threads_changed(&self);
    fn thread_changed(&self, thread_id: u64);
    /// A new inbound message, for notifications.
    fn inbound(&self, message: &Message);
    fn status(&self, status: &DeviceStatus);
}

#[derive(Debug)]
pub enum Command {
    Send {
        thread_id: Option<u64>,
        to: Option<String>,
        body: String,
    },
    /// Re-send a failed message under a new client_id (the phone would otherwise
    /// return the remembered failure).
    Retry {
        client_id: String,
    },
    /// Skip the backoff wait, or reconnect after a fingerprint or token problem.
    Reconnect,
    Stop,
}

#[derive(Debug)]
enum End {
    Retry(String),
    Fatal(ConnState),
    Reconnect,
    Stop,
}

impl From<NetError> for End {
    fn from(e: NetError) -> Self {
        match e {
            NetError::FingerprintMismatch => End::Fatal(ConnState::FingerprintMismatch),
            e if e.is_unauthorized() => End::Fatal(ConnState::Unauthorized),
            e => End::Retry(e.to_string()),
        }
    }
}

impl From<rusqlite::Error> for End {
    fn from(e: rusqlite::Error) -> Self {
        End::Retry(format!("local database: {e}"))
    }
}

pub struct Engine {
    client: PhoneClient,
    store: Arc<Mutex<Store>>,
    events: Arc<dyn Events>,
    commands: mpsc::UnboundedReceiver<Command>,
}

/// Builds an engine without starting it, for hosts with their own runtime handle.
pub fn engine(
    client: PhoneClient,
    store: Arc<Mutex<Store>>,
    events: Arc<dyn Events>,
) -> (
    mpsc::UnboundedSender<Command>,
    impl std::future::Future<Output = ()> + Send + 'static,
) {
    let (tx, rx) = mpsc::unbounded_channel();
    let engine = Engine {
        client,
        store,
        events,
        commands: rx,
    };
    (tx, engine.run())
}

/// Builds and spawns an engine on the current Tokio runtime.
pub fn spawn(
    client: PhoneClient,
    store: Arc<Mutex<Store>>,
    events: Arc<dyn Events>,
) -> mpsc::UnboundedSender<Command> {
    let (tx, run) = engine(client, store, events);
    tokio::spawn(run);
    tx
}

type Sink = SplitSink<PhoneSocket, WsMessage>;
type Stream = SplitStream<PhoneSocket>;

impl Engine {
    fn db<T>(&self, f: impl FnOnce(&mut Store) -> rusqlite::Result<T>) -> rusqlite::Result<T> {
        let mut guard = self.store.lock().unwrap_or_else(|p| p.into_inner());
        f(&mut guard)
    }

    pub async fn run(mut self) {
        let mut backoff = Duration::from_secs(1);
        loop {
            self.events.state(&ConnState::Connecting);
            let started = Instant::now();
            let end = self.session().await;
            if started.elapsed() > Duration::from_secs(60) {
                backoff = Duration::from_secs(1);
            }
            match end {
                End::Stop => {
                    self.events.state(&ConnState::Stopped);
                    return;
                }
                End::Reconnect => continue,
                End::Fatal(state) => {
                    self.events.state(&state);
                    // Wait for the user: retrying a wrong certificate or a revoked token cannot help.
                    if !self.wait(None).await {
                        self.events.state(&ConnState::Stopped);
                        return;
                    }
                }
                End::Retry(reason) => {
                    log::info!("disconnected: {reason}");
                    self.events.state(&ConnState::Offline {
                        reason,
                        retry_in_s: backoff.as_secs(),
                    });
                    if !self.wait(Some(backoff)).await {
                        self.events.state(&ConnState::Stopped);
                        return;
                    }
                    backoff = (backoff * 2).min(MAX_BACKOFF);
                }
            }
        }
    }

    /// Waits out the backoff while still accepting sends. Returns false on Stop.
    async fn wait(&mut self, delay: Option<Duration>) -> bool {
        let deadline = delay.map(|d| Instant::now() + d);
        loop {
            let cmd = match deadline {
                Some(at) => tokio::select! {
                    _ = tokio::time::sleep_until(at) => return true,
                    cmd = self.commands.recv() => cmd,
                },
                None => self.commands.recv().await,
            };
            match cmd {
                None | Some(Command::Stop) => return false,
                Some(Command::Reconnect) => return true,
                Some(other) => {
                    // Queued now, sent on reconnect.
                    if let Err(e) = self.queue(other) {
                        log::warn!("could not queue a send: {e}");
                    }
                }
            }
        }
    }

    /// Records a send in the outbox. Returns the frame to send now, if connected.
    fn queue(&self, cmd: Command) -> rusqlite::Result<Option<Envelope>> {
        let (thread_id, to, body) = match cmd {
            Command::Send {
                thread_id,
                to,
                body,
            } => (thread_id, to, body),
            Command::Retry { client_id } => {
                let Some(old) = self.db(|s| s.outbox_get(&client_id))? else {
                    return Ok(None);
                };
                self.db(|s| s.outbox_remove(&client_id))?;
                (old.thread_id, old.to, old.body)
            }
            _ => return Ok(None),
        };
        let client_id = uuid::Uuid::new_v4().to_string();
        self.db(|s| s.outbox_add(&client_id, thread_id, to.as_deref(), &body, now_ms()))?;
        if let Some(t) = thread_id {
            self.events.thread_changed(t);
        }
        Ok(Some(send_frame(client_id, thread_id, to, body)))
    }

    async fn session(&mut self) -> End {
        let socket = match self.client.websocket().await {
            Ok(s) => s,
            Err(e) => return e.into(),
        };
        let (mut sink, mut stream) = socket.split();

        let hello = match timeout(Duration::from_secs(10), next_envelope(&mut stream)).await {
            Ok(Ok(Some(Envelope {
                frame:
                    Frame::Hello {
                        device_name,
                        sync_cursor,
                        ..
                    },
                ..
            }))) => (device_name, sync_cursor),
            Ok(Ok(_)) => return End::Retry("the phone did not say hello".into()),
            Ok(Err(e)) => return e,
            Err(_) => return End::Retry("no hello within 10 s".into()),
        };

        self.events.state(&ConnState::Syncing);
        if let Err(e) = self.catch_up(&hello.1).await {
            return e;
        }
        if let Err(e) = self.replay_outbox(&mut sink).await {
            return e;
        }
        self.events.state(&ConnState::Connected {
            device_name: hello.0,
        });

        let mut ping = interval(PING_EVERY);
        ping.set_missed_tick_behavior(MissedTickBehavior::Delay);
        ping.tick().await;
        let mut pong_deadline: Option<Instant> = None;

        loop {
            let deadline = pong_deadline;
            let pong_wait = async move {
                match deadline {
                    Some(at) => tokio::time::sleep_until(at).await,
                    None => std::future::pending().await,
                }
            };
            tokio::select! {
                frame = next_envelope(&mut stream) => match frame {
                    Ok(Some(env)) => {
                        if matches!(env.frame, Frame::Pong) {
                            pong_deadline = None;
                        } else if let Err(e) = self.apply(env.frame).await {
                            return e;
                        }
                    }
                    Ok(None) => return End::Retry("the phone closed the connection".into()),
                    Err(e) => return e,
                },
                _ = ping.tick() => {
                    if pong_deadline.is_none() {
                        pong_deadline = Some(Instant::now() + PONG_WITHIN);
                    }
                    if let Err(e) = send(&mut sink, &Envelope::new(Frame::Ping)).await { return e; }
                }
                _ = pong_wait => return End::Retry("the phone stopped answering pings".into()),
                cmd = self.commands.recv() => match cmd {
                    None | Some(Command::Stop) => {
                        let _ = sink.close().await;
                        return End::Stop;
                    }
                    Some(Command::Reconnect) => return End::Reconnect,
                    Some(other) => match self.queue(other) {
                        Ok(Some(frame)) => if let Err(e) = send(&mut sink, &frame).await { return e; },
                        Ok(None) => {}
                        Err(e) => return e.into(),
                    },
                },
            }
        }
    }

    /// Brings the store up to the phone's `hello` cursor.
    async fn catch_up(&self, hello_cursor: &str) -> Result<(), End> {
        let stored = self.db(|s| s.meta(META_SYNC_CURSOR))?;
        match stored {
            None => self.initial_sync().await?,
            Some(mut cursor) => loop {
                let page = match self.client.sync(Some(&cursor), SYNC_PAGE).await {
                    // The phone could not read our cursor (reinstalled app, new phone): start over.
                    Err(NetError::Refused {
                        code: ErrorCode::BadCursor,
                        ..
                    }) => {
                        self.db(|s| s.delete_meta(META_SYNC_CURSOR))?;
                        return Box::pin(self.catch_up(hello_cursor)).await;
                    }
                    other => other?,
                };
                self.db(|s| s.upsert_messages(&page.messages))?;
                self.db(|s| s.set_meta(META_SYNC_CURSOR, &page.next_cursor))?;
                cursor = page.next_cursor;
                if !page.has_more {
                    break;
                }
            },
        }
        // Thread snippets and unread counts can change without new messages.
        let threads = self.client.threads(200, None).await?;
        self.db(|s| s.upsert_threads(&threads.threads))?;
        if let Ok(contacts) = self.client.contacts().await {
            self.db(|s| s.replace_contacts(&contacts.contacts))?;
        }
        self.events.threads_changed();
        Ok(())
    }

    async fn initial_sync(&self) -> Result<(), End> {
        // Take the cursor first: anything newer arrives live or on the next catch-up.
        let start = self.client.sync(None, 1).await?;
        let mut cursor: Option<String> = None;
        loop {
            let page = self.client.threads(200, cursor.as_deref()).await?;
            self.db(|s| s.upsert_threads(&page.threads))?;
            for t in &page.threads {
                let msgs = self
                    .client
                    .thread_messages(t.id, INITIAL_MESSAGES_PER_THREAD, None)
                    .await?;
                self.db(|s| s.upsert_messages(&msgs.messages))?;
                if let Some(next) = &msgs.next_cursor {
                    self.db(|s| s.set_meta(&older_key(t.id), next))?;
                }
            }
            self.events.threads_changed();
            match page.next_cursor {
                Some(next) => cursor = Some(next),
                None => break,
            }
        }
        self.db(|s| s.set_meta(META_SYNC_CURSOR, &start.next_cursor))?;
        Ok(())
    }

    async fn replay_outbox(&self, sink: &mut Sink) -> Result<(), End> {
        for item in self.db(|s| s.outbox_unconfirmed())? {
            send(
                sink,
                &send_frame(item.client_id, item.thread_id, item.to, item.body),
            )
            .await?;
        }
        Ok(())
    }

    async fn apply(&self, frame: Frame) -> Result<(), End> {
        match frame {
            Frame::Message { message } => {
                let new = self.db(|s| s.upsert_messages(std::slice::from_ref(&message)))?;
                if !new.is_empty() && message.direction == Direction::In {
                    self.events.inbound(&message);
                }
                self.events.thread_changed(message.thread_id);
                self.events.threads_changed();
            }
            Frame::SendResult {
                client_id,
                message_id,
                status,
                error,
            } => {
                self.db(|s| {
                    s.outbox_update(&client_id, status, message_id.as_deref(), error.as_deref())
                })?;
                if let Some(id) = &message_id {
                    let ms = match status {
                        SendStatus::Queued => MessageStatus::Pending,
                        SendStatus::Sent => MessageStatus::Sent,
                        SendStatus::Delivered => MessageStatus::Delivered,
                        SendStatus::Failed => MessageStatus::Failed,
                    };
                    self.db(|s| s.set_message_status(id, ms))?;
                }
                if let Some(item) = self.db(|s| s.outbox_get(&client_id))? {
                    if let Some(t) = item.thread_id {
                        self.events.thread_changed(t);
                    }
                }
            }
            Frame::ThreadUpdated { thread_id } => {
                let msgs = self.client.thread_messages(thread_id, 50, None).await?;
                self.db(|s| s.upsert_messages(&msgs.messages))?;
                let threads = self.client.threads(200, None).await?;
                self.db(|s| s.upsert_threads(&threads.threads))?;
                self.events.thread_changed(thread_id);
                self.events.threads_changed();
            }
            Frame::Status { status } => self.events.status(&status),
            Frame::Error {
                code,
                message,
                client_id,
            } => {
                log::warn!("phone rejected a frame: {code:?}");
                if let Some(id) = client_id {
                    self.db(|s| s.outbox_update(&id, SendStatus::Failed, None, Some(&message)))?;
                    if let Some(item) = self.db(|s| s.outbox_get(&id))? {
                        if let Some(t) = item.thread_id {
                            self.events.thread_changed(t);
                        }
                    }
                }
            }
            // Frames a phone does not send, or that were handled already.
            Frame::Hello { .. }
            | Frame::Pong
            | Frame::Ping
            | Frame::Send { .. }
            | Frame::MarkRead { .. } => {}
        }
        Ok(())
    }
}

pub fn older_key(thread_id: u64) -> String {
    format!("older_cursor:{thread_id}")
}

/// Fetches the next older page of a thread from the phone into the store.
/// Returns false when the thread's start has been reached.
pub async fn fetch_older(
    client: &PhoneClient,
    store: &Arc<Mutex<Store>>,
    thread_id: u64,
) -> Result<bool, NetError> {
    let cursor = {
        let s = store.lock().unwrap_or_else(|p| p.into_inner());
        s.meta(&older_key(thread_id)).ok().flatten()
    };
    let Some(cursor) = cursor else {
        return Ok(false);
    };
    let page = client
        .thread_messages(thread_id, 100, Some(&cursor))
        .await?;
    let mut s = store.lock().unwrap_or_else(|p| p.into_inner());
    let _ = s.upsert_messages(&page.messages);
    match &page.next_cursor {
        Some(next) => {
            let _ = s.set_meta(&older_key(thread_id), next);
            Ok(true)
        }
        None => {
            let _ = s.delete_meta(&older_key(thread_id));
            Ok(false)
        }
    }
}

fn send_frame(
    client_id: String,
    thread_id: Option<u64>,
    to: Option<String>,
    body: String,
) -> Envelope {
    let (thread_id, to) = match (thread_id, to) {
        (Some(t), _) => (Some(t), None),
        (None, Some(addr)) => (None, Some(vec![addr])),
        (None, None) => (None, Some(Vec::new())), // Rejected by the phone; recorded as failed.
    };
    Envelope::new(Frame::Send {
        client_id,
        thread_id,
        to,
        body,
    })
}

async fn send(sink: &mut Sink, env: &Envelope) -> Result<(), End> {
    let text = serde_json::to_string(env).map_err(|e| End::Retry(e.to_string()))?;
    sink.send(WsMessage::text(text))
        .await
        .map_err(|e| End::Retry(format!("send failed: {e}")))
}

/// Next protocol frame, skipping control frames. `Ok(None)` when the socket closed.
async fn next_envelope(stream: &mut Stream) -> Result<Option<Envelope>, End> {
    while let Some(msg) = stream.next().await {
        match msg {
            Ok(WsMessage::Text(text)) => match serde_json::from_str::<Envelope>(&text) {
                Ok(env) if env.protocol_version == PROTOCOL_VERSION => return Ok(Some(env)),
                Ok(_) => {
                    return Err(End::Retry(
                        "the phone speaks a different protocol version".into(),
                    ))
                }
                // Unknown frame types from a newer phone are skipped.
                Err(_) => continue,
            },
            Ok(WsMessage::Close(frame)) => {
                let code = frame.as_ref().map(|f| u16::from(f.code));
                return match code {
                    Some(4001) => Err(End::Fatal(ConnState::Unauthorized)),
                    Some(4000) => Err(End::Retry(
                        "the phone speaks a different protocol version".into(),
                    )),
                    _ => Ok(None),
                };
            }
            Ok(_) => continue,
            Err(e) => return Err(End::Retry(format!("connection lost: {e}"))),
        }
    }
    Ok(None)
}
