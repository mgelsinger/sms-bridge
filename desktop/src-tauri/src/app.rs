//! App-wide state: the store, the paired phone, and the running sync engine.

use serde::Serialize;
use sms_bridge_core::attention;
use sms_bridge_core::discovery;
use sms_bridge_core::net::{probe_fingerprint, PhoneAddress, PhoneClient};
use sms_bridge_core::protocol::{DeviceStatus, Message};
use sms_bridge_core::store::{Store, PREF_POPUPS};
use sms_bridge_core::sync::{self, Command, ConnState, Events};
use std::path::Path;
use std::sync::{Arc, Mutex, MutexGuard, Weak};
use std::time::{Duration, Instant};
use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_notification::NotificationExt;
use tokio::sync::mpsc::UnboundedSender;

const KEYRING_SERVICE: &str = "io.github.mgelsinger.smsbridge";
const KEYRING_USER: &str = "phone-token";
const REDISCOVER_EVERY: Duration = Duration::from_secs(30);
/// Windows scales the taskbar overlay down to 16 pixels; 32 keeps it sharp on high-DPI screens.
#[cfg(target_os = "windows")]
const DOT_SIZE: u32 = 32;

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Paired {
    pub device_name: String,
    pub host: String,
    pub port: u16,
    pub fingerprint: String,
}

#[derive(Default)]
pub struct Inner {
    pub paired: Option<Paired>,
    pub conn: Option<ConnState>,
    commands: Option<UnboundedSender<Command>>,
    client: Option<PhoneClient>,
    open_thread: Option<u64>,
    last_rediscovery: Option<Instant>,
    /// The unread count the taskbar dot and tray tooltip currently reflect.
    shown_unread: Option<u32>,
}

pub struct AppState {
    pub store: Arc<Mutex<Store>>,
    handle: AppHandle,
    inner: Mutex<Inner>,
}

fn token_entry() -> keyring::Result<keyring::Entry> {
    keyring::Entry::new(KEYRING_SERVICE, KEYRING_USER)
}

impl AppState {
    pub fn open(handle: AppHandle, db: &Path) -> Result<Arc<Self>, Box<dyn std::error::Error>> {
        let store = Store::open(db)?;
        let paired = (|| {
            Some(Paired {
                device_name: store.meta("phone_name").ok()??,
                host: store.meta("phone_host").ok()??,
                port: store.meta("phone_port").ok()??.parse().ok()?,
                fingerprint: store.meta("phone_fingerprint").ok()??,
            })
        })();
        Ok(Arc::new(Self {
            store: Arc::new(Mutex::new(store)),
            handle,
            inner: Mutex::new(Inner {
                paired,
                ..Inner::default()
            }),
        }))
    }

    pub fn lock(&self) -> MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(|p| p.into_inner())
    }

    pub fn snapshot(&self) -> (Option<Paired>, Option<ConnState>) {
        let inner = self.lock();
        (inner.paired.clone(), inner.conn.clone())
    }

    pub fn db<T>(
        &self,
        f: impl FnOnce(&mut Store) -> rusqlite_result::Result<T>,
    ) -> rusqlite_result::Result<T> {
        let mut guard = self.store.lock().unwrap_or_else(|p| p.into_inner());
        f(&mut guard)
    }

    pub fn client(&self) -> Option<PhoneClient> {
        self.lock().client.clone()
    }

    pub fn command(&self, cmd: Command) -> Result<(), String> {
        match &self.lock().commands {
            Some(tx) => tx
                .send(cmd)
                .map_err(|_| "the connection to the phone is not running".to_owned()),
            None => Err("Pair with your phone first.".into()),
        }
    }

    pub fn set_open_thread(&self, thread_id: Option<u64>) {
        self.lock().open_thread = thread_id;
    }

    pub fn emit_threads_changed(&self) {
        let _ = self.handle.emit("threads-changed", ());
        self.refresh_attention();
    }

    /// Puts a dot on the taskbar icon while anything is unread, and the count in
    /// the tray tooltip for when the window is hidden. This is how new texts are
    /// announced by default; pop-ups are opt-in.
    pub fn refresh_attention(&self) {
        let unread = self.db(|s| s.unread_total()).unwrap_or(0);
        {
            let mut inner = self.lock();
            if inner.shown_unread == Some(unread) {
                return;
            }
            inner.shown_unread = Some(unread);
        }
        if let Some(window) = self.handle.get_webview_window("main") {
            #[cfg(target_os = "windows")]
            {
                let dot = attention::unread_dot_rgba(DOT_SIZE);
                let icon = (unread > 0).then(|| tauri::image::Image::new(&dot, DOT_SIZE, DOT_SIZE));
                let _ = window.set_overlay_icon(icon);
            }
            #[cfg(not(target_os = "windows"))]
            let _ = window.set_badge_count((unread > 0).then_some(i64::from(unread)));
        }
        if let Some(tray) = self.handle.tray_by_id("main") {
            let _ = tray.set_tooltip(Some(attention::tray_tooltip(unread)));
        }
    }

    /// Applies the dot again from scratch. A window that was hidden gets a new
    /// taskbar button when it is shown, without the overlay.
    pub fn reapply_attention(&self) {
        self.lock().shown_unread = None;
        self.refresh_attention();
    }

    /// Starts syncing with the stored phone, if there is one.
    pub fn start_if_paired(self: &Arc<Self>) {
        let Some(paired) = self.lock().paired.clone() else {
            return;
        };
        match token_entry().and_then(|e| e.get_password()) {
            Ok(token) => self.restart_engine(&paired, &token),
            Err(_) => {
                // Paired before, but the token is gone (credential manager cleared).
                self.set_conn(ConnState::Unauthorized);
            }
        }
    }

    /// Saves a newly paired phone and starts syncing with it.
    pub fn adopt(self: &Arc<Self>, paired: Paired, token: &str) -> Result<(), String> {
        let previous = self.lock().paired.clone();
        if previous.as_ref().map(|p| &p.fingerprint) != Some(&paired.fingerprint) {
            // A different phone: its messages, ids, and cursors are unrelated to what we hold.
            self.db(|s| s.clear_phone_data())
                .map_err(|e| e.to_string())?;
        }
        self.save_address(&paired).map_err(|e| e.to_string())?;
        token_entry()
            .and_then(|e| e.set_password(token))
            .map_err(|e| format!("could not store the token: {e}"))?;
        self.lock().paired = Some(paired.clone());
        self.restart_engine(&paired, token);
        self.emit_threads_changed();
        Ok(())
    }

    /// Unpairs: stops syncing, deletes the token, and clears the local copy.
    pub fn forget(&self) -> Result<(), String> {
        {
            let mut inner = self.lock();
            if let Some(tx) = inner.commands.take() {
                let _ = tx.send(Command::Stop);
            }
            inner.client = None;
            inner.paired = None;
            inner.conn = None;
        }
        let _ = token_entry().and_then(|e| e.delete_credential());
        self.db(|s| s.clear_phone_data())
            .map_err(|e| e.to_string())?;
        let _ = self.handle.emit("conn-state", Option::<ConnState>::None);
        self.emit_threads_changed();
        Ok(())
    }

    fn save_address(&self, p: &Paired) -> rusqlite_result::Result<()> {
        self.db(|s| {
            s.set_meta("phone_name", &p.device_name)?;
            s.set_meta("phone_host", &p.host)?;
            s.set_meta("phone_port", &p.port.to_string())?;
            s.set_meta("phone_fingerprint", &p.fingerprint)
        })
    }

    fn restart_engine(self: &Arc<Self>, paired: &Paired, token: &str) {
        let address = PhoneAddress {
            host: paired.host.clone(),
            port: paired.port,
            fingerprint: paired.fingerprint.clone(),
        };
        let client = match PhoneClient::new(address, Some(token.to_owned())) {
            Ok(c) => c,
            Err(e) => {
                log::error!("bad stored phone address: {e}");
                self.set_conn(ConnState::FingerprintMismatch);
                return;
            }
        };
        let events = Arc::new(Bridge {
            state: Arc::downgrade(self),
        });
        let (tx, engine) = sync::engine(client.clone(), self.store.clone(), events);
        {
            let mut inner = self.lock();
            if let Some(old) = inner.commands.replace(tx) {
                let _ = old.send(Command::Stop);
            }
            inner.client = Some(client);
        }
        tauri::async_runtime::spawn(engine);
    }

    fn set_conn(&self, state: ConnState) {
        self.lock().conn = Some(state.clone());
        let _ = self.handle.emit("conn-state", Some(state));
    }

    /// The phone may have a new IP address after a DHCP renewal. Look for it by
    /// its advertised fingerprint prefix, confirm the full fingerprint, and move.
    fn maybe_rediscover(self: &Arc<Self>) {
        let paired = {
            let mut inner = self.lock();
            let due = inner
                .last_rediscovery
                .is_none_or(|t| t.elapsed() >= REDISCOVER_EVERY);
            if !due {
                return;
            }
            inner.last_rediscovery = Some(Instant::now());
            match inner.paired.clone() {
                Some(p) => p,
                None => return,
            }
        };
        let me = Arc::clone(self);
        tauri::async_runtime::spawn(async move {
            let Ok(Ok(found)) =
                tauri::async_runtime::spawn_blocking(|| discovery::browse(Duration::from_secs(3)))
                    .await
            else {
                return;
            };
            let Some(phone) = found.into_iter().find(|p| {
                p.fingerprint_prefix
                    .as_deref()
                    .is_some_and(|fp| paired.fingerprint.starts_with(fp))
                    && (p.host != paired.host || p.port != paired.port)
            }) else {
                return;
            };
            // Only move if the phone at the new address proves it holds the paired key.
            if probe_fingerprint(&phone.host, phone.port)
                .await
                .ok()
                .as_deref()
                != Some(paired.fingerprint.as_str())
            {
                return;
            }
            let moved = Paired {
                host: phone.host,
                port: phone.port,
                ..paired
            };
            log::info!("phone moved to {}:{}", moved.host, moved.port);
            if me.save_address(&moved).is_err() {
                return;
            }
            me.lock().paired = Some(moved.clone());
            if let Ok(token) = token_entry().and_then(|e| e.get_password()) {
                me.restart_engine(&moved, &token);
            }
        });
    }
}

/// Re-exported so this file reads without pulling rusqlite into the Tauri crate.
mod rusqlite_result {
    pub type Result<T> = sms_bridge_core::store::Result<T>;
}

/// Forwards engine events to the window and to Windows notifications.
struct Bridge {
    state: Weak<AppState>,
}

impl Events for Bridge {
    fn state(&self, state: &ConnState) {
        let Some(app) = self.state.upgrade() else {
            return;
        };
        app.set_conn(state.clone());
        if matches!(state, ConnState::Offline { .. }) {
            app.maybe_rediscover();
        }
    }

    fn threads_changed(&self) {
        if let Some(app) = self.state.upgrade() {
            app.emit_threads_changed();
        }
    }

    fn thread_changed(&self, thread_id: u64) {
        if let Some(app) = self.state.upgrade() {
            let _ = app.handle.emit("thread-changed", thread_id);
        }
    }

    fn inbound(&self, message: &Message) {
        let Some(app) = self.state.upgrade() else {
            return;
        };
        let enabled = app.db(|s| s.pref(PREF_POPUPS)).unwrap_or(false);
        let focused = app
            .handle
            .get_webview_window("main")
            .and_then(|w| w.is_focused().ok())
            .unwrap_or(false);
        let open_thread = app.lock().open_thread;
        if !attention::wants_popup(enabled, focused, open_thread, message.thread_id) {
            return;
        }
        let title = app
            .db(|s| s.display_name(&message.address))
            .ok()
            .flatten()
            .unwrap_or_else(|| message.address.clone());
        let body = message
            .body
            .clone()
            .unwrap_or_else(|| "Sent an attachment".into());
        let _ = app
            .handle
            .notification()
            .builder()
            .title(title)
            .body(body)
            .show();
    }

    fn status(&self, status: &DeviceStatus) {
        if let Some(app) = self.state.upgrade() {
            let _ = app.handle.emit("device-status", status.clone());
        }
    }
}
