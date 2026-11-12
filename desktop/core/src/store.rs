//! The PC's local copy of the phone's messages, so history survives the phone
//! leaving the room. Plain SQLite in WAL mode; the bearer token is NOT stored
//! here (it lives in Windows Credential Manager).

use crate::protocol::{
    Contact, Direction, Message, MessageKind, MessageStatus, MmsPart, SendStatus, ThreadSummary,
};
use rusqlite::{params, Connection, OptionalExtension};
use serde::Serialize;
use std::collections::HashMap;
use std::path::Path;

pub type Result<T> = rusqlite::Result<T>;

const SCHEMA_VERSION: i64 = 1;

/// Preference: raise a pop-up notification for inbound texts. Off unless set.
pub const PREF_POPUPS: &str = "pref:popups";

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
}

#[derive(Debug, Clone, PartialEq)]
pub struct OutboxItem {
    pub client_id: String,
    pub thread_id: Option<u64>,
    pub to: Option<String>,
    pub body: String,
    pub created_at: u64,
    pub status: SendStatus,
    pub message_id: Option<String>,
    pub error: Option<String>,
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

    fn init(conn: Connection) -> Result<Self> {
        conn.pragma_update(None, "foreign_keys", "ON")?;
        let version: i64 = conn.pragma_query_value(None, "user_version", |r| r.get(0))?;
        if version < 1 {
            conn.execute_batch(
                "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
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
                 );",
            )?;
            conn.pragma_update(None, "user_version", SCHEMA_VERSION)?;
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
            let mut stmt =
                tx.prepare("INSERT OR REPLACE INTO contacts (number, name) VALUES (?1, ?2)")?;
            for c in contacts {
                for n in &c.numbers {
                    stmt.execute([n, &c.name])?;
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
        now: u64,
    ) -> Result<()> {
        self.conn.execute(
            "INSERT INTO outbox (client_id, thread_id, to_address, body, created_at, status) VALUES (?1, ?2, ?3, ?4, ?5, 'queued')",
            params![client_id, thread_id, to, body, now],
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
            "SELECT {OUTBOX_COLUMNS} FROM outbox WHERE status = 'queued' ORDER BY created_at"
        ))?;
        let rows = stmt.query_map([], outbox_row)?;
        rows.collect()
    }

    /// Removes a failed send the user chose to discard.
    pub fn outbox_remove(&self, client_id: &str) -> Result<()> {
        self.conn
            .execute("DELETE FROM outbox WHERE client_id = ?1", [client_id])?;
        Ok(())
    }

    // Reads -----------------------------------------------------------------

    fn names(&self) -> Result<HashMap<String, String>> {
        let mut stmt = self.conn.prepare("SELECT number, name FROM contacts")?;
        let rows = stmt.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))?;
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
        let mut stmt = self.conn.prepare(
            "SELECT t.id, t.participants, t.snippet, t.date, t.phone_unread, t.local_read_at,
                    (SELECT COUNT(*) FROM messages m WHERE m.thread_id = t.id AND m.direction = 'in' AND m.date > t.local_read_at)
             FROM threads t ORDER BY t.date DESC, t.id DESC",
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
            Ok(ThreadView {
                id: r.get(0)?,
                participants,
                title,
                snippet: r.get(2)?,
                date: r.get(3)?,
                unread,
            })
        })?;
        rows.collect()
    }

    /// Unread texts across all threads, counted the way the thread list shows them.
    pub fn unread_total(&self) -> Result<u32> {
        Ok(self.threads()?.iter().map(|t| t.unread).sum())
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
        let mut stmt = self.conn.prepare(
            "SELECT m.id, m.thread_id, m.kind, m.direction, m.address, m.body, m.parts, m.date, m.status, m.client_id, o.error
             FROM messages m LEFT JOIN outbox o ON o.client_id = m.client_id
             WHERE m.thread_id = ?1 AND m.date < ?2
             ORDER BY m.date DESC, m.id DESC LIMIT ?3",
        )?;
        let rows = stmt.query_map(
            params![thread_id, before.unwrap_or(u64::MAX >> 1), limit],
            |r| {
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
                })
            },
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
                kind: MessageKind::Sms,
                direction: Direction::Out,
                address: o.to.clone().unwrap_or_default(),
                sender_name: None,
                body: Some(o.body.clone()),
                parts: Vec::new(),
                date: o.created_at,
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

    /// Case-insensitive substring search over message bodies, newest first.
    pub fn search(&self, query: &str, limit: u32) -> Result<Vec<MessageView>> {
        let pattern = format!(
            "%{}%",
            query
                .replace('\\', "\\\\")
                .replace('%', "\\%")
                .replace('_', "\\_")
        );
        let mut stmt = self.conn.prepare(
            "SELECT id, thread_id FROM messages WHERE body LIKE ?1 ESCAPE '\\' ORDER BY date DESC LIMIT ?2",
        )?;
        let hits: Vec<(String, u64)> = stmt
            .query_map(params![pattern, limit], |r| Ok((r.get(0)?, r.get(1)?)))?
            .collect::<Result<_>>()?;
        let mut out = Vec::new();
        for (id, thread) in hits {
            let date: u64 =
                self.conn
                    .query_row("SELECT date FROM messages WHERE id = ?1", [&id], |r| {
                        r.get(0)
                    })?;
            out.extend(
                self.messages(thread, Some(date + 1), 1)?
                    .into_iter()
                    .filter(|m| m.id == id),
            );
        }
        Ok(out)
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

const OUTBOX_COLUMNS: &str =
    "client_id, thread_id, to_address, body, created_at, status, message_id, error";

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
        }])
        .expect("contacts");
        s
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
    fn outbox_entries_show_until_the_phone_echoes_them() {
        let mut s = store();
        s.upsert_messages(&[msg("sms:1", 11, Direction::In, 100, "hi")])
            .expect("upsert");
        s.outbox_add("c1", Some(11), None, "on my way", 500)
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
        s.outbox_add("c2", Some(11), None, "nope", 500)
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
        s.outbox_add("c3", Some(11), None, "x", 500)
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

    #[test]
    fn search_escapes_wildcards() {
        let mut s = store();
        s.upsert_messages(&[
            msg("sms:1", 11, Direction::In, 100, "50% off"),
            msg("sms:2", 11, Direction::In, 200, "500 off"),
        ])
        .expect("upsert");
        assert_eq!(s.search("0%", 10).expect("search").len(), 1);
        assert_eq!(s.search("OFF", 10).expect("search").len(), 2);
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
