// Hide the console window on Windows release builds.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod app;
mod autostart;

use app::{AppState, Paired};
use serde::Serialize;
use sms_bridge_core::discovery::{self, DiscoveredPhone};
use sms_bridge_core::net::{probe_fingerprint, NetError, PhoneAddress, PhoneClient};
use sms_bridge_core::protocol::{CallAbilities, CallAction, PROTOCOL_VERSION};
use sms_bridge_core::startup;
use sms_bridge_core::store::{
    CallView, MessageView, ThreadView, PREF_AUTOSTART, PREF_CALL_POPUPS_OFF, PREF_POPUPS,
};
use sms_bridge_core::sync::{self, Command, ConnState, Line};
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
    // The window asks for this whenever it (re)loads, which is when the taskbar dot may be stale.
    state.refresh_attention();
    state.db(|s| s.threads()).map_err(err)
}

/// Whether inbound texts raise a pop-up notification. Off unless the user turned it on.
#[tauri::command]
fn popups_enabled(state: Shared<'_>) -> Result<bool, String> {
    state.db(|s| s.pref(PREF_POPUPS)).map_err(err)
}

#[tauri::command]
fn set_popups(state: Shared<'_>, enabled: bool) -> Result<(), String> {
    state.db(|s| s.set_pref(PREF_POPUPS, enabled)).map_err(err)
}

/// Whether the app starts with Windows, in the tray. Off unless the user turned
/// it on here, and off again if they switched it off under Startup apps.
#[tauri::command]
fn autostart_enabled(state: Shared<'_>) -> Result<bool, String> {
    Ok(state.db(|s| s.pref(PREF_AUTOSTART)).map_err(err)? && autostart::is_on())
}

#[tauri::command]
fn set_autostart(state: Shared<'_>, enabled: bool) -> Result<(), String> {
    autostart::set(enabled)?;
    state
        .db(|s| s.set_pref(PREF_AUTOSTART, enabled))
        .map_err(err)
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
    attachments: Option<Vec<sms_bridge_core::protocol::OutgoingAttachment>>,
) -> Result<(), String> {
    let attachments = attachments.unwrap_or_default();
    if body.is_empty() && attachments.is_empty() {
        return Err("Type a message or attach a picture.".into());
    }
    if body.chars().count() > 5000 {
        return Err("Messages must be at most 5000 characters.".into());
    }
    if attachments.len() > 10 {
        return Err("Attach at most 10 files to one message.".into());
    }
    if thread_id.is_none() && to.as_deref().is_none_or(|t| t.trim().is_empty()) {
        return Err("Choose a conversation or enter a number.".into());
    }
    state.command(Command::Send {
        thread_id,
        to: to.map(|t| t.trim().to_owned()),
        body,
        attachments,
    })
}

/// Call history, newest first.
#[tauri::command]
fn calls(state: Shared<'_>, before: Option<u64>) -> Result<Vec<CallView>, String> {
    state.db(|s| s.calls(before, 200)).map_err(err)
}

#[tauri::command]
fn missed_calls(state: Shared<'_>) -> Result<u32, String> {
    state.db(|s| s.missed_calls_unseen()).map_err(err)
}

/// The user is looking at the call history: missed calls stop counting.
#[tauri::command]
fn mark_calls_seen(state: Shared<'_>) -> Result<(), String> {
    state.db(|s| s.mark_calls_seen()).map_err(err)?;
    state.emit_calls_changed();
    Ok(())
}

/// What the phone's line is doing right now.
#[tauri::command]
fn line(state: Shared<'_>) -> Line {
    state.line()
}

/// What the phone can do with calls. `None` when its app does not know about
/// calls, or cannot be asked right now.
#[tauri::command]
async fn call_abilities(state: Shared<'_>) -> Result<Option<CallAbilities>, String> {
    let Some(client) = state.client() else {
        return Ok(None);
    };
    Ok(client.status().await.ok().and_then(|s| s.status.calls))
}

/// Whether the phone can send MMS, and its size limit. `None` when its app
/// cannot, or cannot be asked right now: no attachments, no group replies.
#[tauri::command]
async fn mms_abilities(
    state: Shared<'_>,
) -> Result<Option<sms_bridge_core::protocol::MmsAbilities>, String> {
    let Some(client) = state.client() else {
        return Ok(None);
    };
    Ok(client.status().await.ok().and_then(|s| s.status.mms))
}

/// Asks the phone to place a call. Only ever called because the user clicked
/// Call or pressed Enter in the dial pad. Returns the request's id; what became
/// of it arrives as a `call-result` event, and it is never sent a second time.
#[tauri::command]
fn dial(state: Shared<'_>, to: String) -> Result<String, String> {
    let to = to.trim().to_owned();
    if to.is_empty() || to.chars().count() > 64 {
        return Err("Enter a number to call.".into());
    }
    let client_id = sync::new_client_id();
    state.command(Command::Dial {
        client_id: client_id.clone(),
        to,
    })?;
    Ok(client_id)
}

/// Answers the ringing call, or ends (declines or hangs up) the current one.
#[tauri::command]
fn call_action(state: Shared<'_>, action: String) -> Result<String, String> {
    let action = match action.as_str() {
        "answer" => CallAction::Answer,
        "end" => CallAction::End,
        _ => return Err("unknown call action".into()),
    };
    let client_id = sync::new_client_id();
    state.command(Command::CallAction {
        client_id: client_id.clone(),
        action,
    })?;
    Ok(client_id)
}

/// Whether a ringing or missed call raises a pop-up. On unless switched off.
#[tauri::command]
fn call_popups_enabled(state: Shared<'_>) -> Result<bool, String> {
    Ok(!state.db(|s| s.pref(PREF_CALL_POPUPS_OFF)).map_err(err)?)
}

#[tauri::command]
fn set_call_popups(state: Shared<'_>, enabled: bool) -> Result<(), String> {
    state
        .db(|s| s.set_pref(PREF_CALL_POPUPS_OFF, !enabled))
        .map_err(err)
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

/// Saves an MMS attachment into the user's Downloads folder, never over an existing
/// file, and returns the path it was saved to.
#[tauri::command]
async fn save_mms_part(
    app: tauri::AppHandle,
    state: Shared<'_>,
    part_id: u64,
    name: Option<String>,
) -> Result<String, String> {
    let client = state.client().ok_or("not paired")?;
    let (content_type, bytes) = client.mms_part(part_id).await.map_err(err)?;
    let dir = app.path().download_dir().map_err(err)?;
    let file = sms_bridge_core::attachments::file_name(name.as_deref(), &content_type, part_id);
    let path = sms_bridge_core::attachments::unique_path(&dir, &file, |p| p.exists());
    // create_new: if another save took the name in the meantime, fail instead of overwriting.
    use std::io::Write as _;
    let mut f = std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&path)
        .map_err(err)?;
    f.write_all(&bytes).map_err(err)?;
    Ok(path.display().to_string())
}

/// Opens Explorer with a saved file selected.
#[tauri::command]
fn show_in_folder(path: String) -> Result<(), String> {
    let p = std::path::Path::new(&path);
    if !p.is_file() {
        return Err("That file is no longer there.".into());
    }
    #[cfg(windows)]
    {
        std::process::Command::new("explorer.exe")
            .arg(format!("/select,{}", p.display()))
            .spawn()
            .map_err(err)?;
    }
    Ok(())
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
    if let Some(state) = app.try_state::<Arc<AppState>>() {
        state.reapply_attention();
    }
}

fn main() {
    let result = tauri::Builder::default()
        // One copy at a time: the app keeps running in the tray, so opening it
        // again must show that copy, not start a second one syncing beside it.
        .plugin(tauri_plugin_single_instance::init(|app, args, _cwd| {
            if !startup::started_at_login(&args) {
                show_main(app);
            }
        }))
        .plugin(tauri_plugin_notification::init())
        .setup(|app| {
            let handle = app.handle().clone();
            let dir = app.path().app_local_data_dir()?;
            std::fs::create_dir_all(&dir)?;
            let state = AppState::open(handle.clone(), &dir.join("sms-bridge.db"))?;
            app.manage(state.clone());
            state.start_if_paired();
            autostart::repair(state.db(|s| s.pref(PREF_AUTOSTART)).unwrap_or(false));

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
            // The window is created hidden. A start at login stays in the tray;
            // any other start shows it.
            if !startup::started_at_login(std::env::args()) {
                show_main(&handle);
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
            popups_enabled,
            set_popups,
            autostart_enabled,
            set_autostart,
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
            save_mms_part,
            show_in_folder,
            computer_name,
            calls,
            missed_calls,
            mark_calls_seen,
            line,
            call_abilities,
            mms_abilities,
            dial,
            call_action,
            call_popups_enabled,
            set_call_popups,
        ])
        .run(tauri::generate_context!());
    if let Err(err) = result {
        eprintln!("sms-bridge failed to start: {err}");
        std::process::exit(1);
    }
}
