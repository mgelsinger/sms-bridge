// Hide the console window on Windows release builds.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod app;

use app::{AppState, Paired};
use serde::Serialize;
use sms_bridge_core::discovery::{self, DiscoveredPhone};
use sms_bridge_core::net::{probe_fingerprint, NetError, PhoneAddress, PhoneClient};
use sms_bridge_core::protocol::PROTOCOL_VERSION;
use sms_bridge_core::store::{MessageView, ThreadView};
use sms_bridge_core::sync::{self, Command, ConnState};
use std::sync::Arc;
use std::time::Duration;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{Manager, State, WindowEvent};

type Shared<'a> = State<'a, Arc<AppState>>;

fn err(e: impl std::fmt::Display) -> String {
    e.to_string()
}

#[derive(Serialize)]
struct Snapshot {
    paired: Option<Paired>,
    conn: Option<ConnState>,
    protocol_version: u32,
}

#[tauri::command]
fn app_state(state: Shared<'_>) -> Snapshot {
    let (paired, conn) = state.snapshot();
    Snapshot {
        paired,
        conn,
        protocol_version: PROTOCOL_VERSION,
    }
}

#[tauri::command]
async fn discover() -> Result<Vec<DiscoveredPhone>, String> {
    tauri::async_runtime::spawn_blocking(|| discovery::browse(Duration::from_secs(3)))
        .await
        .map_err(err)?
}

/// Reads the phone's certificate fingerprint without sending anything, so the
/// user can compare it with the phone's screen before pairing.
#[tauri::command]
async fn probe(host: String, port: u16) -> Result<String, String> {
    probe_fingerprint(host.trim(), port).await.map_err(err)
}

#[tauri::command]
async fn pair(
    state: Shared<'_>,
    host: String,
    port: u16,
    fingerprint: String,
    code: String,
    client_name: String,
) -> Result<Paired, String> {
    let address = PhoneAddress {
        host: host.trim().to_owned(),
        port,
        fingerprint: fingerprint.trim().to_ascii_lowercase(),
    };
    let client = PhoneClient::new(address.clone(), None).map_err(err)?;
    let response = client
        .pair(code.trim(), client_name.trim())
        .await
        .map_err(|e| match e {
            NetError::Refused {
                code: sms_bridge_core::protocol::ErrorCode::PairingInvalid,
                ..
            } => "That code is wrong or expired. Tap Pair on the phone for a new one.".to_owned(),
            NetError::Refused {
                code: sms_bridge_core::protocol::ErrorCode::PairingRejected,
                ..
            } => "Pairing was declined on the phone.".to_owned(),
            NetError::Refused {
                code: sms_bridge_core::protocol::ErrorCode::RateLimited,
                ..
            } => "Too many wrong codes. Pairing is locked on the phone for 10 minutes.".to_owned(),
            other => other.to_string(),
        })?;
    let paired = Paired {
        device_name: response.device_name,
        host: address.host,
        port: address.port,
        fingerprint: address.fingerprint,
    };
    state.adopt(paired.clone(), &response.token).map_err(err)?;
    Ok(paired)
}

#[tauri::command]
fn unpair(state: Shared<'_>) -> Result<(), String> {
    state.forget().map_err(err)
}

#[tauri::command]
fn threads(state: Shared<'_>) -> Result<Vec<ThreadView>, String> {
    state.db(|s| s.threads()).map_err(err)
}

#[tauri::command]
fn messages(
    state: Shared<'_>,
    thread_id: u64,
    before: Option<u64>,
) -> Result<Vec<MessageView>, String> {
    state
        .db(|s| s.messages(thread_id, before, 100))
        .map_err(err)
}

/// Fetches older history for a thread from the phone. False when there is no more.
#[tauri::command]
async fn load_older(state: Shared<'_>, thread_id: u64) -> Result<bool, String> {
    let client = state.client().ok_or("not paired")?;
    sync::fetch_older(&client, &state.store, thread_id)
        .await
        .map_err(err)
}

#[tauri::command]
fn search(state: Shared<'_>, query: String) -> Result<Vec<MessageView>, String> {
    if query.trim().is_empty() {
        return Ok(Vec::new());
    }
    state.db(|s| s.search(query.trim(), 100)).map_err(err)
}

#[tauri::command]
fn contacts(state: Shared<'_>) -> Result<Vec<(String, String)>, String> {
    state.db(|s| s.contacts()).map_err(err)
}

#[tauri::command]
fn send(
    state: Shared<'_>,
    thread_id: Option<u64>,
    to: Option<String>,
    body: String,
) -> Result<(), String> {
    if body.is_empty() || body.chars().count() > 5000 {
        return Err("Messages must be 1 to 5000 characters.".into());
    }
    if thread_id.is_none() && to.as_deref().is_none_or(|t| t.trim().is_empty()) {
        return Err("Choose a conversation or enter a number.".into());
    }
    state.command(Command::Send {
        thread_id,
        to: to.map(|t| t.trim().to_owned()),
        body,
    })
}

#[tauri::command]
fn retry(state: Shared<'_>, client_id: String) -> Result<(), String> {
    state.command(Command::Retry { client_id })
}

#[tauri::command]
fn discard(state: Shared<'_>, client_id: String) -> Result<(), String> {
    state.db(|s| s.outbox_remove(&client_id)).map_err(err)
}

#[tauri::command]
fn mark_read(state: Shared<'_>, thread_id: u64) -> Result<(), String> {
    state
        .db(|s| s.mark_read_locally(thread_id, sync::now_ms()))
        .map_err(err)?;
    state.emit_threads_changed();
    Ok(())
}

/// The thread on screen, so its messages do not raise notifications while the window is focused.
#[tauri::command]
fn set_open_thread(state: Shared<'_>, thread_id: Option<u64>) {
    state.set_open_thread(thread_id);
}

#[tauri::command]
fn reconnect(state: Shared<'_>) -> Result<(), String> {
    state.command(Command::Reconnect)
}

/// Raw bytes of an MMS attachment, returned without JSON encoding.
#[tauri::command]
async fn mms_part(state: Shared<'_>, part_id: u64) -> Result<tauri::ipc::Response, String> {
    let client = state.client().ok_or("not paired")?;
    let (_, bytes) = client.mms_part(part_id).await.map_err(err)?;
    Ok(tauri::ipc::Response::new(bytes.to_vec()))
}

#[tauri::command]
fn computer_name() -> String {
    std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .unwrap_or_else(|_| "Windows PC".into())
}

fn show_main(app: &tauri::AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
    }
}

fn main() {
    let result = tauri::Builder::default()
        .plugin(tauri_plugin_notification::init())
        .setup(|app| {
            let handle = app.handle().clone();
            let dir = app.path().app_local_data_dir()?;
            std::fs::create_dir_all(&dir)?;
            let state = AppState::open(handle.clone(), &dir.join("sms-bridge.db"))?;
            app.manage(state.clone());
            state.start_if_paired();

            let show = MenuItem::with_id(app, "show", "Show SMS Bridge", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&show, &quit])?;
            let mut tray = TrayIconBuilder::with_id("main")
                .tooltip("SMS Bridge")
                .menu(&menu)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "show" => show_main(app),
                    "quit" => app.exit(0),
                    _ => {}
                });
            if let Some(icon) = app.default_window_icon() {
                tray = tray.icon(icon.clone());
            }
            tray.build(app)?;

            // Closing the window keeps the app running in the tray, so texts still arrive.
            if let Some(window) = app.get_webview_window("main") {
                let w = window.clone();
                window.on_window_event(move |event| {
                    if let WindowEvent::CloseRequested { api, .. } = event {
                        api.prevent_close();
                        let _ = w.hide();
                    }
                });
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            app_state,
            discover,
            probe,
            pair,
            unpair,
            threads,
            messages,
            load_older,
            search,
            contacts,
            send,
            retry,
            discard,
            mark_read,
            set_open_thread,
            reconnect,
            mms_part,
            computer_name,
        ])
        .run(tauri::generate_context!());
    if let Err(err) = result {
        eprintln!("sms-bridge failed to start: {err}");
        std::process::exit(1);
    }
}
