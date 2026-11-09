// Hide the console window on Windows release builds.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

#[tauri::command]
fn protocol_version() -> u32 {
    sms_bridge_core::protocol::PROTOCOL_VERSION
}

fn main() {
    let result = tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![protocol_version])
        .run(tauri::generate_context!());
    if let Err(err) = result {
        eprintln!("sms-bridge failed to start: {err}");
        std::process::exit(1);
    }
}
