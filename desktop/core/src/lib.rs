//! Platform-independent core of the sms-bridge desktop client.
//!
//! Everything here builds and tests on any OS. The Tauri shell in
//! `desktop/src-tauri` wires it to the window, tray, and notifications.

pub mod discovery;
pub mod net;
pub mod protocol;
pub mod store;
pub mod sync;
