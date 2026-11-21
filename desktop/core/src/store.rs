//! The PC's local copy of the phone's messages, so history survives the phone
//! leaving the room. Plain SQLite in WAL mode; the bearer token is NOT stored
//! here (it lives in Windows Credential Manager).

use crate::protocol::{
    Call, CallKind, Contact, Direction, Message, MessageKind, MessageStatus, MmsPart,
    OutgoingAttachment, SendStatus, ThreadSummary,
};
use rusqlite::{params, Connection, OptionalExtension};
use serde::Serialize;
use std::collections::HashMap;
use std::path::Path;

pub type Result<T> = rusqlite::Result<T>;

/// The schema, one step per version. A store is brought up to date by running
/// the steps it has not seen, each in its own transaction, so `user_version`
/// always says exactly which steps are in the file.
const MIGRATIONS: [&str; 7] = [
    SCHEMA_V1, SCHEMA_V2, SCHEMA_V3, SCHEMA_V4, SCHEMA_V5, SCHEMA_V6, SCHEMA_V7,
];

const SCHEMA_V1: &str = "
    CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS threads (
        id INTEGER PRIMARY KEY,
        participants TEXT NOT NULL,
        snippet TEXT,
        date INTEGER NOT NULL,
        phone_unread INTEGER NOT NULL DEFAULT 0,
        message_count INTEGER NOT NULL DEFAULT 0,
        local_read_at INTEGER
    );
    CREATE TABLE IF NOT EXISTS messages (
        id TEXT PRIMARY KEY,
        thread_id INTEGER NOT NULL,
        kind TEXT NOT NULL,
        direction TEXT NOT NULL,
        address TEXT NOT NULL,
        participants TEXT NOT NULL,
        body TEXT,
        parts TEXT NOT NULL,
        date INTEGER NOT NULL,
        read INTEGER NOT NULL,
        status TEXT,
        client_id TEXT
    );
    CREATE INDEX IF NOT EXISTS messages_by_thread ON messages (thread_id, date DESC);
    CREATE TABLE IF NOT EXISTS contacts (number TEXT PRIMARY KEY, name TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS outbox (
        client_id TEXT PRIMARY KEY,
        thread_id INTEGER,
        to_address TEXT,
        body TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        status TEXT NOT NULL,
        message_id TEXT,
        error TEXT
    );";

/// Version 2: a full-text index over message bodies (SQLite FTS5). The index
/// holds no copy of the text; it points at rows of `messages` and is kept in
/// step by triggers, so every way a message is written or removed is covered.
/// Words are compared without case or accents. The last statement indexes the
/// messages a store from version 1 already holds.
///
/// The index is keyed by the `messages` table's rowid, which only a VACUUM
/// could renumber. This code never runs one; anything that does must follow it
/// with the `rebuild` command below.
const SCHEMA_V2: &str = "
    CREATE VIRTUAL TABLE messages_fts USING fts5(
        body, content='messages', content_rowid='rowid', tokenize='unicode61 remove_diacritics 2'
    );
    CREATE TRIGGER messages_fts_insert AFTER INSERT ON messages BEGIN
        INSERT INTO messages_fts (rowid, body) VALUES (new.rowid, new.body);
    END;
    CREATE TRIGGER messages_fts_delete AFTER DELETE ON messages BEGIN
        INSERT INTO messages_fts (messages_fts, rowid, body) VALUES ('delete', old.rowid, old.body);
    END;
    CREATE TRIGGER messages_fts_update AFTER UPDATE OF body ON messages BEGIN
        INSERT INTO messages_fts (messages_fts, rowid, body) VALUES ('delete', old.rowid, old.body);
        INSERT INTO messages_fts (rowid, body) VALUES (new.rowid, new.body);
    END;
    INSERT INTO messages_fts (messages_fts) VALUES ('rebuild');";

/// Version 3: the phone's call history. `seen` is this PC's own note that the
/// user has looked at the entry; it drives the missed-call count and never
/// goes back to the phone.
const SCHEMA_V3: &str = "
    CREATE TABLE calls (
        id TEXT PRIMARY KEY,
        number TEXT,
        kind TEXT NOT NULL,
        date INTEGER NOT NULL,
        duration_s INTEGER NOT NULL,
        seen INTEGER NOT NULL DEFAULT 0
    );
    CREATE INDEX calls_by_date ON calls (date DESC);";

/// Version 4: attachments waiting in the outbox, as the JSON array of
/// `OutgoingAttachment` that goes into the `send` frame, so a picture queued
/// while the phone is away is replayed like any text. An empty `body` means the
/// send is attachments only.
const SCHEMA_V4: &str = "ALTER TABLE outbox ADD COLUMN attachments TEXT;";

/// Version 5: which contact a number belongs to and whether it has a photo, so
/// a conversation can show the picture. The photo itself stays on the phone and
/// is fetched when shown.
const SCHEMA_V5: &str = "
    ALTER TABLE contacts ADD COLUMN contact_id INTEGER;
    ALTER TABLE contacts ADD COLUMN photo INTEGER NOT NULL DEFAULT 0;";

/// Version 6: a send the user scheduled carries the time it may go
/// (`send_at`, ms). Until then it is kept out of the replay after reconnect;
/// when due, the engine clears `send_at` and sends it like any queued text.
const SCHEMA_V6: &str = "ALTER TABLE outbox ADD COLUMN send_at INTEGER;";

/// Version 7: this PC's own choices per conversation. Pinned ones sort first;
/// muted ones count for nothing in the taskbar dot and raise no pop-up. The
/// phone never hears of either.
const SCHEMA_V7: &str = "
    ALTER TABLE threads ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0;
    ALTER TABLE threads ADD COLUMN muted INTEGER NOT NULL DEFAULT 0;";

/// Preference: raise a pop-up notification for inbound texts. Off unless set.
pub const PREF_POPUPS: &str = "pref:popups";

/// Preference: no pop-up for a ringing or missed call. Call pop-ups are on
/// unless this is set, the other way round from texts: a call cannot wait.
pub const PREF_CALL_POPUPS_OFF: &str = "pref:call_popups_off";

/// Preference: start with Windows, in the tray. Off unless set.
pub const PREF_AUTOSTART: &str = "pref:autostart";

pub struct Store {
    conn: Connection,
}

/// A thread as the UI shows it.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct ThreadView {
    pub id: u64,
    pub participants: Vec<String>,
    /// Contact names where known, otherwise the numbers.
    pub title: String,
    /// For a one-to-one thread whose contact has a photo: that contact's id.
    pub photo_contact: Option<u64>,
    /// Kept at the top of the list (this PC only).
    pub pinned: bool,
    /// No taskbar dot or pop-up for it (this PC only).
    pub muted: bool,
    pub snippet: Option<String>,
    pub date: u64,
    pub unread: u32,
}

/// A message bubble. Unsent outbox entries appear with `id` = `outbox:<client_id>`.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct MessageView {
    pub id: String,
    pub thread_id: u64,
    pub kind: MessageKind,
    pub direction: Direction,
    pub address: String,
    pub sender_name: Option<String>,
    pub body: Option<String>,
    pub parts: Vec<MmsPart>,
    pub date: u64,
    /// For outbound: pending, sent, delivered, or failed.
    pub status: Option<MessageStatus>,
    pub client_id: Option<String>,
    pub error: Option<String>,
    /// For an unsent outbox entry: how many attachments it carries, which the
    /// phone has not stored yet and so have no parts to show.
    pub pending_attachments: u32,
    /// For a scheduled send not yet released: when it will go (ms).
    pub scheduled_for: Option<u64>,
}

/// A call history entry as the UI shows it.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct CallView {
    pub id: String,
    /// `None` when the other party withheld the number.
    pub number: Option<String>,
    /// The contact's name, when the number belongs to one.
    pub name: Option<String>,
    pub kind: CallKind,
    pub date: u64,
    pub duration_s: u64,
    pub seen: bool,
    /// When the number's contact has a photo on the phone: that contact's id.
    pub photo_contact: Option<u64>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct OutboxItem {
    pub client_id: String,
    pub thread_id: Option<u64>,
    pub to: Option<String>,
    /// Empty for a send of attachments only.
    pub body: String,
    pub created_at: u64,
    pub status: SendStatus,
    pub message_id: Option<String>,
    pub error: Option<String>,
    pub attachments: Vec<OutgoingAttachment>,
    /// Set while a scheduled send waits for its time.
    pub send_at: Option<u64>,
}

fn enum_str<T: Serialize>(v: &T) -> String {
    serde_json::to_value(v)
        .ok()
        .and_then(|j| j.as_str().map(str::to_owned))
        .unwrap_or_default()
}

fn enum_parse<T: serde::de::DeserializeOwned>(s: &str) -> Option<T> {
    serde_json::from_value(serde_json::Value::String(s.to_owned())).ok()
}

fn json_vec<T: serde::de::DeserializeOwned>(s: &str) -> Vec<T> {
    serde_json::from_str(s).unwrap_or_default()
}

fn to_json<T: Serialize>(v: &T) -> String {
    serde_json::to_string(v).unwrap_or_else(|_| "[]".into())
}

impl Store {
    pub fn open(path: &Path) -> Result<Self> {
        let conn = Connection::open(path)?;
        conn.pragma_update(None, "journal_mode", "WAL")?;
        Self::init(conn)
    }

    pub fn open_in_memory() -> Result<Self> {
        Self::init(Connection::open_in_memory()?)
    }

    fn init(mut conn: Connection) -> Result<Self> {
        conn.pragma_update(None, "foreign_keys", "ON")?;
        let version: i64 = conn.pragma_query_value(None, "user_version", |r| r.get(0))?;
        for (step, sql) in (1i64..).zip(MIGRATIONS) {
            if version < step {
                let tx = conn.transaction()?;
                tx.execute_batch(sql)?;
                tx.pragma_update(None, "user_version", step)?;
                tx.commit()?;
            }
        }
        Ok(Self { conn })
    }

    // Meta ------------------------------------------------------------------

    pub fn meta(&self, key: &str) -> Result<Option<String>> {
        self.conn
            .query_row("SELECT value FROM meta WHERE key = ?1", [key], |r| r.get(0))
            .optional()
    }

    pub fn set_meta(&self, key: &str, value: &str) -> Result<()> {
        self.conn
            .execute("INSERT INTO meta (key, value) VALUES (?1, ?2) ON CONFLICT(key) DO UPDATE SET value = excluded.value", [key, value])?;
        Ok(())
    }

    pub fn delete_meta(&self, key: &str) -> Result<()> {
        self.conn
            .execute("DELETE FROM meta WHERE key = ?1", [key])?;
        Ok(())
    }

    /// Forgets everything about the current phone, for pairing a different one.
    /// The user's own preferences (`pref:` keys) belong to this PC and are kept.
    pub fn clear_phone_data(&self) -> Result<()> {
        self.conn.execute_batch(
            "DELETE FROM threads; DELETE FROM messages; DELETE FROM contacts; DELETE FROM outbox;
             DELETE FROM calls;
             DELETE FROM meta WHERE key NOT LIKE 'pref:%';",
        )
    }

    /// A yes/no preference of this PC's user. Unset means no.
    pub fn pref(&self, key: &str) -> Result<bool> {
        Ok(self.meta(key)?.as_deref() == Some("1"))
    }

    pub fn set_pref(&self, key: &str, on: bool) -> Result<()> {
        self.set_meta(key, if on { "1" } else { "0" })
    }

    // Writes ----------------------------------------------------------------

    /// Replaces thread summaries from the phone. Local read state is kept.
    pub fn upsert_threads(&mut self, threads: &[ThreadSummary]) -> Result<()> {
        let tx = self.conn.transaction()?;
        {
            let mut stmt = tx.prepare(
                "INSERT INTO threads (id, participants, snippet, date, phone_unread, message_count)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6)
                 ON CONFLICT(id) DO UPDATE SET participants = excluded.participants, snippet = excluded.snippet,
                     date = MAX(threads.date, excluded.date), phone_unread = excluded.phone_unread,
                     message_count = excluded.message_count",
            )?;
            for t in threads {
                stmt.execute(params![
                    t.id,
                    to_json(&t.participants),
                    t.snippet,
                    t.date,
                    t.unread_count,
                    t.message_count
                ])?;
            }
        }
        tx.commit()
    }

    /// Stores messages and keeps each thread's snippet and date current.
    /// Returns the ids that were not already stored.
    pub fn upsert_messages(&mut self, messages: &[Message]) -> Result<Vec<String>> {
        let tx = self.conn.transaction()?;
        let mut new_ids = Vec::new();
        {
            let mut exists = tx.prepare("SELECT 1 FROM messages WHERE id = ?1")?;
            let mut upsert = tx.prepare(
                "INSERT INTO messages (id, thread_id, kind, direction, address, participants, body, parts, date, read, status, client_id)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)
                 ON CONFLICT(id) DO UPDATE SET thread_id = excluded.thread_id, kind = excluded.kind,
                     direction = excluded.direction, address = excluded.address, participants = excluded.participants,
                     body = excluded.body, parts = excluded.parts, date = excluded.date, read = excluded.read,
                     status = excluded.status, client_id = COALESCE(excluded.client_id, messages.client_id)",
            )?;
            let mut thread = tx.prepare(
                "INSERT INTO threads (id, participants, snippet, date, phone_unread, message_count)
                 VALUES (?1, ?2, ?3, ?4, ?5, 1)
                 ON CONFLICT(id) DO UPDATE SET
                     snippet = CASE WHEN excluded.date >= threads.date THEN excluded.snippet ELSE threads.snippet END,
                     date = MAX(threads.date, excluded.date),
                     phone_unread = threads.phone_unread + excluded.phone_unread,
                     message_count = threads.message_count + 1",
            )?;
            for m in messages {
                let is_new = !exists.exists([&m.id])?;
                upsert.execute(params![
                    m.id,
                    m.thread_id,
                    enum_str(&m.kind),
                    enum_str(&m.direction),
                    m.address,
                    to_json(&m.participants),
                    m.body,
                    to_json(&m.parts),
                    m.date,
                    m.read,
                    m.status.as_ref().map(enum_str),
                    m.client_id,
                ])?;
                if is_new {
                    let snippet = m
                        .body
                        .clone()
                        .or_else(|| (!m.parts.is_empty()).then(|| "Attachment".to_owned()));
                    let unread = u32::from(m.direction == Direction::In && !m.read);
                    thread.execute(params![
                        m.thread_id,
                        to_json(&m.participants),
                        snippet,
                        m.date,
                        unread
                    ])?;
                    new_ids.push(m.id.clone());
                }
            }
        }
        tx.commit()?;
        Ok(new_ids)
    }

    pub fn replace_contacts(&mut self, contacts: &[Contact]) -> Result<()> {
        let tx = self.conn.transaction()?;
        tx.execute("DELETE FROM contacts", [])?;
        {
            let mut stmt = tx.prepare(
                "INSERT OR REPLACE INTO contacts (number, name, contact_id, photo) VALUES (?1, ?2, ?3, ?4)",
            )?;
            for c in contacts {
                for n in &c.numbers {
                    stmt.execute(params![n, &c.name, c.id, c.photo == Some(true)])?;
                }
            }
        }
        tx.commit()
    }

    /// Read state is kept on the PC: Android lets only the default SMS app mark
    /// messages read.
    pub fn mark_read_locally(&self, thread_id: u64, now: u64) -> Result<()> {
        self.conn.execute(
            "UPDATE threads SET local_read_at = ?2 WHERE id = ?1",
            params![thread_id, now],
        )?;
        Ok(())
    }

    /// Status only moves forward: pending, sent, delivered; failed is final.
    pub fn set_message_status(&self, id: &str, status: MessageStatus) -> Result<()> {
        self.conn.execute(
            "UPDATE messages SET status = ?2 WHERE id = ?1 AND ?3 >= CASE status
                 WHEN 'pending' THEN 0 WHEN 'sent' THEN 1 WHEN 'delivered' THEN 2 WHEN 'failed' THEN 3 ELSE -1 END",
            params![id, enum_str(&status), message_rank(status)],
        )?;
        Ok(())
    }

    // Outbox ----------------------------------------------------------------

    pub fn outbox_add(
        &self,
        client_id: &str,
        thread_id: Option<u64>,
        to: Option<&str>,
        body: &str,
        attachments: &[OutgoingAttachment],
        now: u64,
    ) -> Result<()> {
        let attachments = (!attachments.is_empty()).then(|| to_json(&attachments));
        self.conn.execute(
            "INSERT INTO outbox (client_id, thread_id, to_address, body, created_at, status, attachments) VALUES (?1, ?2, ?3, ?4, ?5, 'queued', ?6)",
            params![client_id, thread_id, to, body, now, attachments],
        )?;
        Ok(())
    }

    /// Applies a send result. Status only moves forward (queued, sent, delivered;
    /// failed is final), so a late or repeated result cannot undo progress.
    pub fn outbox_update(
        &self,
        client_id: &str,
        status: SendStatus,
        message_id: Option<&str>,
        error: Option<&str>,
    ) -> Result<bool> {
        self.conn.execute(
            "UPDATE outbox SET message_id = COALESCE(message_id, ?2) WHERE client_id = ?1",
            params![client_id, message_id],
        )?;
        let n = self.conn.execute(
            "UPDATE outbox SET status = ?2, error = ?3 WHERE client_id = ?1 AND ?4 >= CASE status
                 WHEN 'queued' THEN 0 WHEN 'sent' THEN 1 WHEN 'delivered' THEN 2 ELSE 3 END",
            params![client_id, enum_str(&status), error, send_rank(status)],
        )?;
        Ok(n > 0)
    }
    pub fn outbox_get(&self, client_id: &str) -> Result<Option<OutboxItem>> {
        self.conn
            .query_row(
                &format!("SELECT {OUTBOX_COLUMNS} FROM outbox WHERE client_id = ?1"),
                [client_id],
                outbox_row,
            )
            .optional()
    }

    /// Sends the phone has not confirmed as sent. Replayed after every reconnect;
    /// the phone ignores client_ids it has already handled.
    pub fn outbox_unconfirmed(&self) -> Result<Vec<OutboxItem>> {
        let mut stmt = self.conn.prepare(&format!(
            "SELECT {OUTBOX_COLUMNS} FROM outbox WHERE status = 'queued' AND send_at IS NULL ORDER BY created_at"
        ))?;
        let rows = stmt.query_map([], outbox_row)?;
        rows.collect()
    }

    /// Holds a queued send back until `send_at` (ms).
    pub fn outbox_schedule(&self, client_id: &str, send_at: u64) -> Result<()> {
        self.conn.execute(
            "UPDATE outbox SET send_at = ?2 WHERE client_id = ?1",
            params![client_id, send_at],
        )?;
        Ok(())
    }

    /// Scheduled sends whose time has come, released: from now on they are
    /// ordinary queued sends, replayed after every reconnect until confirmed.
    pub fn outbox_release_due(&mut self, now: u64) -> Result<Vec<OutboxItem>> {
        let tx = self.conn.transaction()?;
        let due = {
            let mut stmt = tx.prepare(&format!(
                "SELECT {OUTBOX_COLUMNS} FROM outbox WHERE status = 'queued' AND send_at IS NOT NULL AND send_at <= ?1 ORDER BY send_at"
            ))?;
            let rows = stmt.query_map([now], outbox_row)?;
            rows.collect::<Result<Vec<_>>>()?
        };
        tx.execute(
            "UPDATE outbox SET send_at = NULL WHERE status = 'queued' AND send_at IS NOT NULL AND send_at <= ?1",
            [now],
        )?;
        tx.commit()?;
        Ok(due)
    }

    /// Removes a failed send the user chose to discard.
    pub fn outbox_remove(&self, client_id: &str) -> Result<()> {
        self.conn
            .execute("DELETE FROM outbox WHERE client_id = ?1", [client_id])?;
        Ok(())
    }

    // Calls -----------------------------------------------------------------

    /// Stores call history entries and returns the ids that were not here
    /// before. `seen` is given to new entries only: history fetched when the
    /// phone is first paired is old news, a call that arrives later is not.
    pub fn upsert_calls(&mut self, calls: &[Call], seen: bool) -> Result<Vec<String>> {
        let tx = self.conn.transaction()?;
        let mut new = Vec::new();
        {
            let mut exists = tx.prepare("SELECT 1 FROM calls WHERE id = ?1")?;
            let mut insert = tx.prepare(
                "INSERT INTO calls (id, number, kind, date, duration_s, seen) VALUES (?1, ?2, ?3, ?4, ?5, ?6)
                 ON CONFLICT(id) DO UPDATE SET number = excluded.number, kind = excluded.kind,
                     date = excluded.date, duration_s = excluded.duration_s",
            )?;
            for call in calls {
                if !exists.exists([&call.id])? {
                    new.push(call.id.clone());
                }
                insert.execute(params![
                    call.id,
                    call.number,
                    call.kind.as_str(),
                    call.date,
                    call.duration_s,
                    seen
                ])?;
            }
        }
        tx.commit()?;
        Ok(new)
    }

    /// Call history, newest first. `before` pages back by date.
    pub fn calls(&self, before: Option<u64>, limit: u32) -> Result<Vec<CallView>> {
        let names = self.names()?;
        let photos = self.photos()?;
        let mut stmt = self.conn.prepare(
            "SELECT id, number, kind, date, duration_s, seen FROM calls
             WHERE ?1 IS NULL OR date < ?1 ORDER BY date DESC, id DESC LIMIT ?2",
        )?;
        let rows = stmt.query_map(params![before, limit], |r| {
            let number: Option<String> = r.get(1)?;
            let kind: String = r.get(2)?;
            Ok(CallView {
                id: r.get(0)?,
                name: number.as_ref().and_then(|n| names.get(n).cloned()),
                photo_contact: number.as_ref().and_then(|n| photos.get(n).copied()),
                number,
                // A kind from a newer phone that this build does not know is shown as a plain inbound call.
                kind: CallKind::parse(&kind).unwrap_or(CallKind::In),
                date: r.get(3)?,
                duration_s: r.get(4)?,
                seen: r.get(5)?,
            })
        })?;
        rows.collect()
    }

    /// Missed calls the user has not looked at yet.
    pub fn missed_calls_unseen(&self) -> Result<u32> {
        self.conn.query_row(
            "SELECT COUNT(*) FROM calls WHERE kind = 'missed' AND seen = 0",
            [],
            |r| r.get(0),
        )
    }

    /// The user opened the call history.
    pub fn mark_calls_seen(&self) -> Result<()> {
        self.conn
            .execute("UPDATE calls SET seen = 1 WHERE seen = 0", [])?;
        Ok(())
    }

    // Reads -----------------------------------------------------------------

    fn names(&self) -> Result<HashMap<String, String>> {
        let mut stmt = self.conn.prepare("SELECT number, name FROM contacts")?;
        let rows = stmt.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))?;
        rows.collect()
    }

    /// Numbers whose contact has a photo, with that contact's id.
    fn photos(&self) -> Result<HashMap<String, u64>> {
        let mut stmt = self.conn.prepare(
            "SELECT number, contact_id FROM contacts WHERE photo = 1 AND contact_id IS NOT NULL",
        )?;
        let rows = stmt.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, u64>(1)?)))?;
        rows.collect()
    }

    /// Every (name, number) pair, for the new-message picker.
    pub fn contacts(&self) -> Result<Vec<(String, String)>> {
        let mut stmt = self
            .conn
            .prepare("SELECT name, number FROM contacts ORDER BY name COLLATE NOCASE, number")?;
        let rows = stmt.query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?;
        rows.collect()
    }

    pub fn display_name(&self, address: &str) -> Result<Option<String>> {
        self.conn
            .query_row(
                "SELECT name FROM contacts WHERE number = ?1",
                [address],
                |r| r.get(0),
            )
            .optional()
    }

    pub fn threads(&self) -> Result<Vec<ThreadView>> {
        let names = self.names()?;
        let photos = self.photos()?;
        let mut stmt = self.conn.prepare(
            "SELECT t.id, t.participants, t.snippet, t.date, t.phone_unread, t.local_read_at,
                    (SELECT COUNT(*) FROM messages m WHERE m.thread_id = t.id AND m.direction = 'in' AND m.date > t.local_read_at),
                    t.pinned, t.muted
             FROM threads t ORDER BY t.pinned DESC, t.date DESC, t.id DESC",
        )?;
        let rows = stmt.query_map([], |r| {
            let participants: Vec<String> = json_vec(&r.get::<_, String>(1)?);
            let local_read_at: Option<u64> = r.get(5)?;
            let unread: u32 = if local_read_at.is_some() {
                r.get(6)?
            } else {
                r.get(4)?
            };
            let title = participants
                .iter()
                .map(|p| names.get(p).cloned().unwrap_or_else(|| p.clone()))
                .collect::<Vec<_>>()
                .join(", ");
            let photo_contact = match participants.as_slice() {
                [one] => photos.get(one).copied(),
                _ => None,
            };
            Ok(ThreadView {
                id: r.get(0)?,
                participants,
                title,
                photo_contact,
                snippet: r.get(2)?,
                date: r.get(3)?,
                unread,
                pinned: r.get(7)?,
                muted: r.get(8)?,
            })
        })?;
        rows.collect()
    }

    /// Unread texts across all threads, counted the way the thread list shows
    /// them, leaving out muted conversations.
    pub fn unread_total(&self) -> Result<u32> {
        Ok(self
            .threads()?
            .iter()
            .filter(|t| !t.muted)
            .map(|t| t.unread)
            .sum())
    }

    pub fn set_pinned(&self, thread_id: u64, pinned: bool) -> Result<()> {
        self.conn.execute(
            "UPDATE threads SET pinned = ?2 WHERE id = ?1",
            params![thread_id, pinned],
        )?;
        Ok(())
    }

    pub fn set_muted(&self, thread_id: u64, muted: bool) -> Result<()> {
        self.conn.execute(
            "UPDATE threads SET muted = ?2 WHERE id = ?1",
            params![thread_id, muted],
        )?;
        Ok(())
    }

    pub fn is_muted(&self, thread_id: u64) -> Result<bool> {
        Ok(self
            .conn
            .query_row(
                "SELECT muted FROM threads WHERE id = ?1",
                [thread_id],
                |r| r.get(0),
            )
            .optional()?
            .unwrap_or(false))
    }

    /// Makes the newest incoming text unread again on this PC.
    pub fn mark_unread_locally(&self, thread_id: u64) -> Result<()> {
        self.conn.execute(
            "UPDATE threads SET local_read_at = (
                 SELECT MAX(date) - 1 FROM messages WHERE thread_id = ?1 AND direction = 'in')
             WHERE id = ?1
               AND EXISTS (SELECT 1 FROM messages WHERE thread_id = ?1 AND direction = 'in')",
            [thread_id],
        )?;
        Ok(())
    }

    /// Threads the phone lists as having messages while none of them are stored here,
    /// newest first. Their history was never fetched.
    pub fn threads_without_messages(&self) -> Result<Vec<u64>> {
        let mut stmt = self.conn.prepare(
            "SELECT t.id FROM threads t
             WHERE t.message_count > 0 AND NOT EXISTS (SELECT 1 FROM messages m WHERE m.thread_id = t.id)
             ORDER BY t.date DESC, t.id DESC",
        )?;
        let rows = stmt.query_map([], |r| r.get(0))?;
        rows.collect()
    }

    /// Newest first, then the unconfirmed outbox entries for this thread at the end
    /// (they are the newest). `before` pages back by date.
    pub fn messages(
        &self,
        thread_id: u64,
        before: Option<u64>,
        limit: u32,
    ) -> Result<Vec<MessageView>> {
        let names = self.names()?;
        let mut stmt = self.conn.prepare(&format!(
            "SELECT {MESSAGE_COLUMNS}
             FROM messages m LEFT JOIN outbox o ON o.client_id = m.client_id
             WHERE m.thread_id = ?1 AND m.date < ?2
             ORDER BY m.date DESC, m.id DESC LIMIT ?3"
        ))?;
        let rows = stmt.query_map(
            params![thread_id, before.unwrap_or(u64::MAX >> 1), limit],
            |r| message_view(&names, r),
        )?;
        let mut out: Vec<MessageView> = rows.collect::<Result<_>>()?;
        if before.is_none() {
            let mut pending = self.conn.prepare(&format!(
                "SELECT {OUTBOX_COLUMNS} FROM outbox o WHERE o.thread_id = ?1
                 AND NOT EXISTS (SELECT 1 FROM messages m WHERE m.client_id = o.client_id) ORDER BY created_at DESC"
            ))?;
            let items = pending
                .query_map([thread_id], outbox_row)?
                .collect::<Result<Vec<_>>>()?;
            let views = items.into_iter().map(|o| MessageView {
                id: format!("outbox:{}", o.client_id),
                thread_id,
                kind: if o.attachments.is_empty() {
                    MessageKind::Sms
                } else {
                    MessageKind::Mms
                },
                direction: Direction::Out,
                address: o.to.clone().unwrap_or_default(),
                sender_name: None,
                body: (!o.body.is_empty()).then(|| o.body.clone()),
                pending_attachments: u32::try_from(o.attachments.len()).unwrap_or(u32::MAX),
                scheduled_for: o.send_at,
                parts: Vec::new(),
                // A scheduled send sits where it will appear once sent.
                date: o.send_at.unwrap_or(o.created_at),
                status: Some(if o.status == SendStatus::Failed {
                    MessageStatus::Failed
                } else {
                    MessageStatus::Pending
                }),
                client_id: Some(o.client_id.clone()),
                error: o.error.clone(),
            });
            let mut merged: Vec<MessageView> = views.chain(out).collect();
            merged.sort_by_key(|m| std::cmp::Reverse(m.date));
            out = merged;
        }
        Ok(out)
    }

    /// Full-text search over message bodies, newest first. A message matches
    /// when it has every word of `query`, as a whole word or the beginning of
    /// one, in any order, whatever the case or accents.
    pub fn search(&self, query: &str, limit: u32) -> Result<Vec<MessageView>> {
        let Some(fts) = fts_query(query) else {
            return Ok(Vec::new());
        };
        let names = self.names()?;
        let mut stmt = self.conn.prepare(&format!(
            "SELECT {MESSAGE_COLUMNS}
             FROM messages_fts
             JOIN messages m ON m.rowid = messages_fts.rowid
             LEFT JOIN outbox o ON o.client_id = m.client_id
             WHERE messages_fts MATCH ?1
             ORDER BY m.date DESC, m.id DESC LIMIT ?2"
        ))?;
        let rows = stmt.query_map(params![fts, limit], |r| message_view(&names, r))?;
        rows.collect()
    }

    pub fn message_count(&self) -> Result<u64> {
        self.conn
            .query_row("SELECT COUNT(*) FROM messages", [], |r| r.get(0))
    }
}

fn send_rank(s: SendStatus) -> i64 {
    match s {
        SendStatus::Queued => 0,
        SendStatus::Sent => 1,
        SendStatus::Delivered => 2,
        SendStatus::Failed => 3,
    }
}

fn message_rank(s: MessageStatus) -> i64 {
    match s {
        MessageStatus::Pending => 0,
        MessageStatus::Sent => 1,
        MessageStatus::Delivered => 2,
        MessageStatus::Failed => 3,
    }
}

/// What [`message_view`] reads, for a query over `messages m` joined to `outbox o`.
const MESSAGE_COLUMNS: &str = "m.id, m.thread_id, m.kind, m.direction, m.address, m.body, \
    m.parts, m.date, m.status, m.client_id, o.error";

fn message_view(names: &HashMap<String, String>, r: &rusqlite::Row<'_>) -> Result<MessageView> {
    let address: String = r.get(4)?;
    let direction = enum_parse(&r.get::<_, String>(3)?).unwrap_or(Direction::In);
    Ok(MessageView {
        id: r.get(0)?,
        thread_id: r.get(1)?,
        kind: enum_parse(&r.get::<_, String>(2)?).unwrap_or(MessageKind::Sms),
        sender_name: if direction == Direction::In {
            names.get(&address).cloned()
        } else {
            None
        },
        direction,
        address,
        body: r.get(5)?,
        parts: json_vec(&r.get::<_, String>(6)?),
        date: r.get(7)?,
        status: r.get::<_, Option<String>>(8)?.and_then(|s| enum_parse(&s)),
        client_id: r.get(9)?,
        error: r.get(10)?,
        pending_attachments: 0,
        scheduled_for: None,
    })
}

/// Turns what the user typed into an FTS5 query: every word is required, as a
/// whole word or the beginning of one. Each word is quoted, so nothing typed
/// is read as FTS5 syntax. Words without a letter or digit cannot match
/// anything and are dropped; `None` when no word is left.
fn fts_query(input: &str) -> Option<String> {
    let words: Vec<String> = input
        .split_whitespace()
        .filter(|w| w.chars().any(char::is_alphanumeric))
        .map(|w| format!("\"{}\"*", w.replace('"', "\"\"")))
        .collect();
    (!words.is_empty()).then(|| words.join(" "))
}

const OUTBOX_COLUMNS: &str =
    "client_id, thread_id, to_address, body, created_at, status, message_id, error, attachments, send_at";

fn outbox_row(r: &rusqlite::Row<'_>) -> Result<OutboxItem> {
    Ok(OutboxItem {
        client_id: r.get(0)?,
        thread_id: r.get(1)?,
        to: r.get(2)?,
        body: r.get(3)?,
        created_at: r.get(4)?,
        status: enum_parse(&r.get::<_, String>(5)?).unwrap_or(SendStatus::Queued),
        message_id: r.get(6)?,
        error: r.get(7)?,
        attachments: r
            .get::<_, Option<String>>(8)?
            .map(|s| json_vec(&s))
            .unwrap_or_default(),
        send_at: r.get(9)?,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn msg(id: &str, thread: u64, direction: Direction, date: u64, body: &str) -> Message {
        Message {
            id: id.into(),
            thread_id: thread,
            kind: MessageKind::Sms,
            direction,
            address: "+15855550101".into(),
            participants: vec!["+15855550101".into()],
            body: Some(body.into()),
            parts: vec![],
            date,
            read: direction == Direction::Out,
            status: None,
            client_id: None,
        }
    }

    fn store() -> Store {
        let mut s = Store::open_in_memory().expect("store");
        s.replace_contacts(&[Contact {
            id: 1,
            name: "Alex Rivera".into(),
            numbers: vec!["+15855550101".into()],
            photo: None,
        }])
        .expect("contacts");
        s
    }

    #[test]
    fn a_call_row_points_at_its_contacts_photo() {
        let mut s = Store::open_in_memory().expect("store");
        s.replace_contacts(&[
            Contact {
                id: 7,
                name: "Alex Rivera".into(),
                numbers: vec!["+15855550101".into()],
                photo: Some(true),
            },
            Contact {
                id: 8,
                name: "Sam".into(),
                numbers: vec!["+15855550103".into()],
                photo: None,
            },
        ])
        .expect("contacts");
        s.upsert_calls(
            &[
                call("call:1", Some("+15855550101"), CallKind::In, 100),
                call("call:2", Some("+15855550103"), CallKind::Out, 200),
                call("call:3", None, CallKind::Missed, 300),
            ],
            true,
        )
        .expect("calls");
        let photo = |id: &str| {
            s.calls(None, 10)
                .expect("calls")
                .into_iter()
                .find(|c| c.id == id)
                .and_then(|c| c.photo_contact)
        };
        assert_eq!(photo("call:1"), Some(7));
        assert_eq!(photo("call:2"), None, "a contact without a photo");
        assert_eq!(
            photo("call:3"),
            None,
            "a withheld number has no one's photo"
        );
    }

    #[test]
    fn a_one_to_one_thread_points_at_its_contacts_photo() {
        let mut s = Store::open_in_memory().expect("store");
        s.replace_contacts(&[
            Contact {
                id: 7,
                name: "Alex Rivera".into(),
                numbers: vec!["+15855550101".into()],
                photo: Some(true),
            },
            Contact {
                id: 8,
                name: "Sam".into(),
                numbers: vec!["+15855550103".into()],
                photo: None,
            },
        ])
        .expect("contacts");
        s.upsert_messages(&[
            msg("sms:1", 11, Direction::In, 100, "hi"),
            Message {
                address: "+15855550103".into(),
                participants: vec!["+15855550103".into()],
                ..msg("sms:2", 12, Direction::In, 200, "yo")
            },
            Message {
                participants: vec!["+15855550101".into(), "+15855550103".into()],
                ..msg("sms:3", 13, Direction::In, 300, "all")
            },
        ])
        .expect("upsert");
        let photo = |id: u64| {
            s.threads()
                .expect("threads")
                .into_iter()
                .find(|t| t.id == id)
                .and_then(|t| t.photo_contact)
        };
        assert_eq!(photo(11), Some(7));
        assert_eq!(photo(12), None, "no photo");
        assert_eq!(photo(13), None, "a group shows no one's photo");
    }

    #[test]
    fn messages_create_threads_and_resolve_contact_names() {
        let mut s = store();
        let new = s
            .upsert_messages(&[
                msg("sms:1", 11, Direction::In, 100, "hi"),
                msg("sms:2", 11, Direction::Out, 200, "yo"),
            ])
            .expect("upsert");
        assert_eq!(new, vec!["sms:1", "sms:2"]);
        let threads = s.threads().expect("threads");
        assert_eq!(threads.len(), 1);
        assert_eq!(threads[0].title, "Alex Rivera");
        assert_eq!(threads[0].snippet.as_deref(), Some("yo"));
        assert_eq!(threads[0].unread, 1);
        let m = s.messages(11, None, 50).expect("messages");
        assert_eq!(
            m.iter().map(|x| x.id.as_str()).collect::<Vec<_>>(),
            vec!["sms:2", "sms:1"]
        );
        assert_eq!(m[1].sender_name.as_deref(), Some("Alex Rivera"));
    }

    #[test]
    fn upserting_the_same_message_twice_changes_nothing() {
        let mut s = store();
        s.upsert_messages(&[msg("sms:1", 11, Direction::In, 100, "hi")])
            .expect("first");
        let again = s
            .upsert_messages(&[msg("sms:1", 11, Direction::In, 100, "hi")])
            .expect("second");
        assert!(again.is_empty());
        assert_eq!(
            s.threads().expect("threads")[0].unread,
            1,
            "unread is not double counted"
        );
        assert_eq!(s.message_count().expect("count"), 1);
    }

    #[test]
    fn the_phone_is_authoritative_when_an_id_comes_back_different() {
        let mut s = store();
        s.upsert_messages(&[msg("sms:7", 11, Direction::In, 100, "old")])
            .expect("first");
        let mut changed = msg("sms:7", 12, Direction::Out, 200, "new");
        changed.client_id = Some("c9".into());
        s.upsert_messages(&[changed]).expect("second");
        assert!(s.messages(11, None, 10).expect("thread 11").is_empty());
        let m = s.messages(12, None, 10).expect("thread 12");
        assert_eq!(
            (
                m[0].body.as_deref(),
                m[0].direction,
                m[0].client_id.as_deref()
            ),
            (Some("new"), Direction::Out, Some("c9"))
        );
    }

    #[test]
    fn preferences_default_to_off_and_survive_unpairing() {
        let s = store();
        assert!(
            !s.pref(PREF_POPUPS).expect("pref"),
            "pop-ups are off until asked for"
        );
        s.set_pref(PREF_POPUPS, true).expect("set");
        s.set_meta("phone_host", "192.168.1.20").expect("meta");
        s.clear_phone_data().expect("clear");
        assert!(
            s.pref(PREF_POPUPS).expect("pref"),
            "a preference outlives the pairing"
        );
        assert_eq!(s.meta("phone_host").expect("meta"), None);
        s.set_pref(PREF_POPUPS, false).expect("set");
        assert!(!s.pref(PREF_POPUPS).expect("pref"));
    }

    #[test]
    fn starting_with_windows_is_off_until_asked_for_and_survives_unpairing() {
        let s = store();
        assert!(!s.pref(PREF_AUTOSTART).expect("pref"));
        s.set_pref(PREF_AUTOSTART, true).expect("set");
        assert!(
            !s.pref(PREF_POPUPS).expect("pref"),
            "one preference does not switch another"
        );
        s.clear_phone_data().expect("clear");
        assert!(s.pref(PREF_AUTOSTART).expect("pref"));
    }

    #[test]
    fn local_read_state_survives_phone_refreshes() {
        let mut s = store();
        s.upsert_messages(&[msg("sms:1", 11, Direction::In, 100, "hi")])
            .expect("upsert");
        s.mark_read_locally(11, 150).expect("read");
        let phone_view = ThreadSummary {
            id: 11,
            participants: vec!["+15855550101".into()],
            snippet: Some("hi".into()),
            date: 100,
            unread_count: 1,
            message_count: 1,
        };
        s.upsert_threads(&[phone_view]).expect("refresh");
        assert_eq!(
            s.threads().expect("threads")[0].unread,
            0,
            "the phone still says unread; the PC remembers it was read"
        );
        s.upsert_messages(&[msg("sms:2", 11, Direction::In, 300, "again")])
            .expect("new");
        assert_eq!(s.threads().expect("threads")[0].unread, 1);
    }

    #[test]
    fn pinned_sort_first_muted_count_for_nothing_and_unread_comes_back() {
        let mut s = Store::open_in_memory().expect("open");
        s.upsert_messages(&[
            msg("sms:1", 11, Direction::In, 100, "old"),
            msg("sms:2", 12, Direction::In, 200, "new"),
        ])
        .expect("upsert");
        let order = |s: &Store| {
            s.threads()
                .expect("threads")
                .iter()
                .map(|t| t.id)
                .collect::<Vec<_>>()
        };
        assert_eq!(order(&s), [12, 11]);
        s.set_pinned(11, true).expect("pin");
        assert_eq!(order(&s), [11, 12], "pinned first");

        assert_eq!(s.unread_total().expect("unread"), 2);
        s.set_muted(12, true).expect("mute");
        assert!(s.is_muted(12).expect("muted"));
        assert_eq!(s.unread_total().expect("unread"), 1, "muted do not count");

        s.mark_read_locally(11, 1_000).expect("read");
        assert_eq!(s.threads().expect("threads")[0].unread, 0);
        s.mark_unread_locally(11).expect("unread again");
        assert_eq!(s.threads().expect("threads")[0].unread, 1);
        // A thread with nothing incoming is left as it is.
        s.mark_unread_locally(99).expect("no such thread");
    }

    #[test]
    fn a_scheduled_send_waits_for_its_time_then_goes_once() {
        let mut s = Store::open_in_memory().expect("open");
        s.outbox_add("c1", Some(11), None, "happy birthday", &[], 100)
            .expect("outbox");
        s.outbox_schedule("c1", 5_000).expect("schedule");
        assert!(
            s.outbox_unconfirmed().expect("pending").is_empty(),
            "not replayed before its time"
        );
        let m = s.messages(11, None, 10).expect("messages");
        assert_eq!(m[0].scheduled_for, Some(5_000));
        assert_eq!(m[0].date, 5_000, "shown where it will land");
        assert!(s.outbox_release_due(4_999).expect("early").is_empty());
        let due = s.outbox_release_due(5_000).expect("due");
        assert_eq!(due.len(), 1);
        assert!(
            s.outbox_release_due(9_000).expect("again").is_empty(),
            "released once"
        );
        assert_eq!(
            s.outbox_unconfirmed().expect("pending").len(),
            1,
            "an ordinary queued send now"
        );
        assert_eq!(
            s.messages(11, None, 10).expect("messages")[0].scheduled_for,
            None
        );
    }

    #[test]
    fn a_picture_waits_in_the_outbox_with_its_bytes() {
        let s = Store::open_in_memory().expect("open");
        let pic = OutgoingAttachment {
            content_type: "image/jpeg".into(),
            name: Some("IMG_1.jpg".into()),
            data: "AAAA".into(),
        };
        s.outbox_add("c9", Some(11), None, "", std::slice::from_ref(&pic), 500)
            .expect("outbox");
        let pending = s.outbox_unconfirmed().expect("pending");
        assert_eq!(pending[0].attachments, vec![pic]);
        assert_eq!(pending[0].body, "");
        let m = s.messages(11, None, 10).expect("messages");
        assert_eq!(m[0].kind, MessageKind::Mms);
        assert_eq!(m[0].body, None, "a picture alone has no text bubble");
        assert_eq!(m[0].pending_attachments, 1);
        // A text-only send carries no attachments and stays an SMS.
        s.outbox_add("c10", Some(11), None, "hi", &[], 600)
            .expect("outbox");
        let m = s.messages(11, None, 10).expect("messages");
        assert_eq!((m[0].kind, m[0].pending_attachments), (MessageKind::Sms, 0));
    }

    #[test]
    fn outbox_entries_show_until_the_phone_echoes_them() {
        let mut s = store();
        s.upsert_messages(&[msg("sms:1", 11, Direction::In, 100, "hi")])
            .expect("upsert");
        s.outbox_add("c1", Some(11), None, "on my way", &[], 500)
            .expect("outbox");
        let m = s.messages(11, None, 50).expect("messages");
        assert_eq!(m[0].id, "outbox:c1");
        assert_eq!(m[0].status, Some(MessageStatus::Pending));
        assert_eq!(s.outbox_unconfirmed().expect("pending").len(), 1);

        s.outbox_update("c1", SendStatus::Sent, Some("sms:9"), None)
            .expect("sent");
        assert!(s.outbox_unconfirmed().expect("pending").is_empty());
        let mut echo = msg("sms:9", 11, Direction::Out, 510, "on my way");
        echo.client_id = Some("c1".into());
        echo.status = Some(MessageStatus::Sent);
        s.upsert_messages(&[echo]).expect("echo");
        let m = s.messages(11, None, 50).expect("messages");
        assert_eq!(
            m.iter()
                .filter(|x| x.body.as_deref() == Some("on my way"))
                .count(),
            1,
            "no duplicate bubble"
        );
        assert_eq!(m[0].id, "sms:9");
    }

    #[test]
    fn failed_sends_stay_visible_with_their_error() {
        let s = store();
        s.outbox_add("c2", Some(11), None, "nope", &[], 500)
            .expect("outbox");
        s.outbox_update(
            "c2",
            SendStatus::Failed,
            None,
            Some("RESULT_ERROR_NO_SERVICE"),
        )
        .expect("fail");
        let m = s.messages(11, None, 50).expect("messages");
        assert_eq!(m[0].status, Some(MessageStatus::Failed));
        assert_eq!(m[0].error.as_deref(), Some("RESULT_ERROR_NO_SERVICE"));
        assert!(
            s.outbox_unconfirmed().expect("pending").is_empty(),
            "failed sends are not replayed automatically"
        );
    }

    #[test]
    fn send_status_never_moves_backwards() {
        let mut s = store();
        s.outbox_add("c3", Some(11), None, "x", &[], 500)
            .expect("outbox");
        s.outbox_update("c3", SendStatus::Sent, Some("sms:5"), None)
            .expect("sent");
        s.outbox_update("c3", SendStatus::Queued, None, None)
            .expect("late queued");
        assert_eq!(
            s.outbox_get("c3").expect("get").map(|o| o.status),
            Some(SendStatus::Sent)
        );
        assert!(
            s.outbox_unconfirmed().expect("pending").is_empty(),
            "a late queued must not make it replay"
        );
        s.outbox_update("c3", SendStatus::Delivered, None, None)
            .expect("delivered");
        s.outbox_update("c3", SendStatus::Sent, None, None)
            .expect("repeat sent");
        assert_eq!(
            s.outbox_get("c3").expect("get").map(|o| o.status),
            Some(SendStatus::Delivered)
        );
        assert_eq!(
            s.outbox_get("c3").expect("get").and_then(|o| o.message_id),
            Some("sms:5".into())
        );

        let mut echo = msg("sms:5", 11, Direction::Out, 510, "x");
        echo.status = Some(MessageStatus::Sent);
        s.upsert_messages(&[echo]).expect("echo");
        s.set_message_status("sms:5", MessageStatus::Delivered)
            .expect("delivered");
        s.set_message_status("sms:5", MessageStatus::Sent)
            .expect("late sent");
        assert_eq!(
            s.messages(11, None, 5).expect("messages")[0].status,
            Some(MessageStatus::Delivered)
        );
    }

    fn found(s: &Store, query: &str) -> Vec<String> {
        s.search(query, 10)
            .expect("search")
            .into_iter()
            .map(|m| m.id)
            .collect()
    }

    /// The index and the messages it points at agree. FTS5 checks this itself.
    fn assert_index_is_sound(s: &Store) {
        s.conn
            .execute(
                "INSERT INTO messages_fts (messages_fts, rank) VALUES ('integrity-check', 1)",
                [],
            )
            .expect("the search index matches the messages");
    }

    #[test]
    fn search_finds_words_and_word_beginnings_in_any_order_newest_first() {
        let mut s = store();
        s.upsert_messages(&[
            msg("sms:1", 11, Direction::In, 100, "Dinner on Friday?"),
            msg(
                "sms:2",
                11,
                Direction::Out,
                200,
                "friday works, dinner at 7",
            ),
            msg("sms:3", 12, Direction::In, 300, "Running late"),
            msg("sms:4", 12, Direction::In, 400, "the dinosaur exhibit"),
        ])
        .expect("upsert");
        assert_eq!(found(&s, "dinner"), ["sms:2", "sms:1"]);
        assert_eq!(found(&s, "FRIDAY din"), ["sms:2", "sms:1"]);
        assert_eq!(found(&s, "din"), ["sms:4", "sms:2", "sms:1"]);
        assert_eq!(found(&s, "late run"), ["sms:3"]);
        assert_eq!(
            found(&s, "dinner late"),
            [] as [&str; 0],
            "every word is required"
        );
        assert_eq!(
            found(&s, "inner"),
            [] as [&str; 0],
            "not the middle of a word"
        );
        assert_eq!(s.search("din", 2).expect("search").len(), 2);
        let hit = &s.search("exhibit", 10).expect("search")[0];
        assert_eq!(
            (
                hit.thread_id,
                hit.body.as_deref(),
                hit.sender_name.as_deref()
            ),
            (12, Some("the dinosaur exhibit"), Some("Alex Rivera"))
        );
    }

    #[test]
    fn search_ignores_case_and_accents() {
        let mut s = store();
        s.upsert_messages(&[
            msg(
                "sms:1",
                11,
                Direction::In,
                100,
                "Meet at the CAF\u{c9} on Main",
            ),
            msg("sms:2", 11, Direction::In, 200, "jalape\u{f1}o poppers"),
        ])
        .expect("upsert");
        assert_eq!(found(&s, "cafe"), ["sms:1"]);
        assert_eq!(found(&s, "caf\u{e9}"), ["sms:1"]);
        assert_eq!(found(&s, "Jalapeno"), ["sms:2"]);
    }

    #[test]
    fn search_reads_what_is_typed_as_text_never_as_query_syntax() {
        let mut s = store();
        s.upsert_messages(&[
            msg("sms:1", 11, Direction::In, 100, "50% off today"),
            msg(
                "sms:2",
                11,
                Direction::In,
                200,
                "don't forget: gate code 4410",
            ),
            msg("sms:3", 11, Direction::In, 300, "this and that or not"),
        ])
        .expect("upsert");
        assert_eq!(found(&s, "50%"), ["sms:1"]);
        assert_eq!(found(&s, "don't"), ["sms:2"]);
        assert_eq!(found(&s, "code: 44"), ["sms:2"]);
        assert_eq!(found(&s, "AND"), ["sms:3"]);
        assert_eq!(found(&s, "NOT"), ["sms:3"]);
        assert_eq!(found(&s, "that OR"), ["sms:3"]);
        for typed in [
            "\"",
            "\"gate",
            "gate\"code",
            "*",
            "gate*",
            "(",
            ")",
            "-",
            "-gate",
            "^gate",
            "+",
            "%",
            "_",
            "body:gate",
            "NEAR(gate code)",
            "{gate}",
            "gate AND",
            "\\",
            "'",
            "",
            "   ",
            "\u{1f600}",
        ] {
            assert!(
                s.search(typed, 10).is_ok(),
                "typing {typed:?} must not fail"
            );
        }
        assert_eq!(found(&s, "\"gate"), ["sms:2"]);
        assert_eq!(found(&s, "% - *"), [] as [&str; 0], "nothing to look for");
    }

    #[test]
    fn search_follows_every_change_to_the_messages() {
        let mut s = store();
        let mut picture = msg("mms:1", 11, Direction::In, 50, "");
        picture.body = None;
        s.upsert_messages(&[
            picture,
            msg("sms:7", 11, Direction::In, 100, "see you at noon"),
        ])
        .expect("upsert");
        assert_eq!(found(&s, "noon"), ["sms:7"]);

        // The phone is authoritative: the same id can come back with other text.
        s.upsert_messages(&[msg("sms:7", 11, Direction::In, 100, "see you at midnight")])
            .expect("changed");
        assert_eq!(found(&s, "noon"), [] as [&str; 0]);
        assert_eq!(found(&s, "midnight"), ["sms:7"]);
        s.upsert_messages(&[msg("sms:7", 11, Direction::In, 100, "see you at midnight")])
            .expect("unchanged");
        s.set_message_status("sms:7", MessageStatus::Sent)
            .expect("status");
        assert_eq!(found(&s, "midnight"), ["sms:7"]);
        assert_index_is_sound(&s);

        s.clear_phone_data().expect("unpair");
        assert_eq!(found(&s, "midnight"), [] as [&str; 0]);
        assert_index_is_sound(&s);
        s.upsert_messages(&[msg(
            "sms:1",
            11,
            Direction::In,
            100,
            "a new phone at midnight",
        )])
        .expect("new phone");
        assert_eq!(found(&s, "midnight"), ["sms:1"]);
        assert_index_is_sound(&s);
    }

    /// A store written before search was indexed (schema version 1) gains the
    /// index, with the messages it already holds, the first time it is opened.
    #[test]
    fn a_store_from_before_the_search_index_is_migrated_with_its_messages() {
        let conn = Connection::open_in_memory().expect("open");
        conn.execute_batch(SCHEMA_V1).expect("version 1 schema");
        conn.execute_batch(
            "INSERT INTO messages (id, thread_id, kind, direction, address, participants, body, parts, date, read)
             VALUES ('sms:1', 11, 'sms', 'in', '+15855550101', '[\"+15855550101\"]', 'old message about pizza', '[]', 100, 1),
                    ('mms:2', 11, 'mms', 'in', '+15855550101', '[\"+15855550101\"]', NULL, '[]', 200, 1);
             INSERT INTO threads (id, participants, snippet, date) VALUES (11, '[\"+15855550101\"]', 'x', 200);
             INSERT INTO meta (key, value) VALUES ('pref:popups', '1'), ('sync_cursor', 's1.m2');
             PRAGMA user_version = 1;",
        )
        .expect("version 1 contents");

        let mut s = Store::init(conn).expect("migrates");
        let version: i64 = s
            .conn
            .pragma_query_value(None, "user_version", |r| r.get(0))
            .expect("version");
        assert_eq!(version, MIGRATIONS.len() as i64);
        assert!(s.calls(None, 10).expect("calls").is_empty());
        assert!(s
            .outbox_unconfirmed()
            .expect("outbox with attachments column")
            .is_empty());
        assert_eq!(found(&s, "pizza"), ["sms:1"]);
        assert_eq!(s.message_count().expect("count"), 2, "nothing was lost");
        assert_eq!(
            s.meta("sync_cursor").expect("meta").as_deref(),
            Some("s1.m2")
        );
        assert!(s.pref(PREF_POPUPS).expect("pref"));
        s.upsert_messages(&[msg("sms:3", 11, Direction::In, 300, "more pizza")])
            .expect("upsert");
        assert_eq!(found(&s, "pizza"), ["sms:3", "sms:1"]);
        assert_index_is_sound(&s);
    }

    /// Opening an up-to-date store runs no migration again.
    #[test]
    fn opening_a_current_store_again_changes_nothing() {
        let dir =
            std::env::temp_dir().join(format!("sms-bridge-store-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("dir");
        let path = dir.join("reopen.db");
        let _ = std::fs::remove_file(&path);
        {
            let mut s = Store::open(&path).expect("create");
            s.upsert_messages(&[msg("sms:1", 11, Direction::In, 100, "kept across restarts")])
                .expect("upsert");
        }
        let s = Store::open(&path).expect("reopen");
        assert_eq!(found(&s, "restarts"), ["sms:1"]);
        assert_index_is_sound(&s);
        drop(s);
        let _ = std::fs::remove_dir_all(&dir);
    }

    fn call(id: &str, number: Option<&str>, kind: CallKind, date: u64) -> Call {
        Call {
            id: id.into(),
            number: number.map(Into::into),
            kind,
            date,
            duration_s: if kind == CallKind::Missed { 0 } else { 60 },
        }
    }

    #[test]
    fn calls_are_listed_newest_first_with_contact_names() {
        let mut s = store();
        let new = s
            .upsert_calls(
                &[
                    call("call:1", Some("+15855550101"), CallKind::In, 100),
                    call("call:2", Some("+15855550199"), CallKind::Out, 300),
                    call("call:3", None, CallKind::Rejected, 200),
                ],
                true,
            )
            .expect("upsert");
        assert_eq!(new, ["call:1", "call:2", "call:3"]);
        let listed = s.calls(None, 10).expect("calls");
        assert_eq!(
            listed.iter().map(|c| c.id.as_str()).collect::<Vec<_>>(),
            ["call:2", "call:3", "call:1"]
        );
        assert_eq!(listed[2].name.as_deref(), Some("Alex Rivera"));
        assert_eq!(listed[0].name, None, "a number with no contact has no name");
        assert_eq!(listed[1].number, None, "a withheld number stays withheld");
        let older = s.calls(Some(200), 10).expect("page");
        assert_eq!(older.len(), 1);
        assert_eq!(older[0].id, "call:1");
    }

    #[test]
    fn a_call_seen_twice_is_stored_once_and_keeps_its_seen_mark() {
        let mut s = store();
        let missed = call("call:7", Some("+15855550101"), CallKind::Missed, 100);
        assert_eq!(
            s.upsert_calls(std::slice::from_ref(&missed), false)
                .expect("first"),
            ["call:7"]
        );
        assert_eq!(s.missed_calls_unseen().expect("count"), 1);
        s.mark_calls_seen().expect("seen");
        assert_eq!(s.missed_calls_unseen().expect("count"), 0);
        // The same entry again, as catch-up after a live frame delivers it.
        let again = s
            .upsert_calls(std::slice::from_ref(&missed), false)
            .expect("second");
        assert!(again.is_empty(), "not new the second time");
        assert_eq!(s.calls(None, 10).expect("calls").len(), 1);
        assert_eq!(
            s.missed_calls_unseen().expect("count"),
            0,
            "seen stays seen"
        );
    }

    #[test]
    fn only_missed_calls_nobody_looked_at_are_counted() {
        let mut s = store();
        s.upsert_calls(
            &[call("call:1", Some("+15855550101"), CallKind::Missed, 100)],
            true,
        )
        .expect("history from the first sync");
        s.upsert_calls(
            &[
                call("call:2", Some("+15855550101"), CallKind::Missed, 200),
                call("call:3", Some("+15855550101"), CallKind::In, 300),
                call("call:4", Some("+15855550101"), CallKind::Rejected, 400),
            ],
            false,
        )
        .expect("calls that came in later");
        assert_eq!(s.missed_calls_unseen().expect("count"), 1);
    }

    #[test]
    fn pairing_another_phone_forgets_the_call_history() {
        let mut s = store();
        s.upsert_calls(
            &[call("call:1", Some("+15855550101"), CallKind::Missed, 100)],
            false,
        )
        .expect("upsert");
        s.clear_phone_data().expect("clear");
        assert!(s.calls(None, 10).expect("calls").is_empty());
        assert_eq!(s.missed_calls_unseen().expect("count"), 0);
    }

    #[test]
    fn paging_back_by_date() {
        let mut s = store();
        let many: Vec<Message> = (1..=5)
            .map(|i| msg(&format!("sms:{i}"), 11, Direction::In, i * 10, "x"))
            .collect();
        s.upsert_messages(&many).expect("upsert");
        let first = s.messages(11, None, 2).expect("page");
        assert_eq!(
            first.iter().map(|m| m.date).collect::<Vec<_>>(),
            vec![50, 40]
        );
        let next = s.messages(11, Some(40), 2).expect("page");
        assert_eq!(
            next.iter().map(|m| m.date).collect::<Vec<_>>(),
            vec![30, 20]
        );
    }
}
