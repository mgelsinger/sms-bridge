//! The "Start with Windows" switch, for this running copy of the app. The rules
//! and the registry access are in `sms_bridge_core::startup`.

use sms_bridge_core::startup::{self, registry, ENTRY_NAME};

fn exe() -> Result<String, String> {
    std::env::current_exe()
        .map(|p| p.display().to_string())
        .map_err(|e| e.to_string())
}

/// Whether Windows will start this copy of the app at login.
pub fn is_on() -> bool {
    exe().is_ok_and(|exe| registry::read(ENTRY_NAME).is_on(&exe))
}

/// Writes or removes the entry. A development build never registers itself.
pub fn set(enabled: bool) -> Result<(), String> {
    if !enabled {
        return registry::remove(ENTRY_NAME);
    }
    if cfg!(debug_assertions) {
        return Err("A development build does not register itself to start with Windows.".into());
    }
    registry::write(ENTRY_NAME, &startup::login_command(&exe()?))
}

/// Run once at startup: brings the entry in line with the user's choice, for
/// example after the app moved or its entry was removed.
pub fn repair(wanted: bool) {
    let Ok(exe) = exe() else {
        return;
    };
    if let Some(enabled) = registry::read(ENTRY_NAME).repair(wanted, &exe) {
        if let Err(e) = set(enabled) {
            log::warn!("could not update the start-with-Windows entry: {e}");
        }
    }
}
