//! Starting with Windows: off unless the user turned it on, and quiet when it
//! happens. The entry is one value under the current user's Run key. What it
//! should hold is decided by plain functions; [`registry`] reads and writes it
//! and does nothing on other systems.

/// The name of the app's value under the Run key.
pub const ENTRY_NAME: &str = "SMS Bridge";

/// The argument the login entry passes, so the app can tell a start at login
/// from one the user made.
pub const LOGIN_ARG: &str = "--autostart";

/// The command line Windows runs at login. The path is quoted: the app
/// installs under a folder with a space in its name.
pub fn login_command(exe: &str) -> String {
    format!("\"{exe}\" {LOGIN_ARG}")
}

/// Whether this process was started by the login entry. Such a start stays in
/// the tray, because nobody asked for a window.
pub fn started_at_login<I, S>(args: I) -> bool
where
    I: IntoIterator<Item = S>,
    S: AsRef<str>,
{
    args.into_iter().any(|a| a.as_ref() == LOGIN_ARG)
}

/// What Windows currently holds for this app.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct LoginEntry {
    /// The command under the Run key, if there is one.
    pub command: Option<String>,
    /// The entry's state under "Startup apps" in Settings and Task Manager, if
    /// the user ever switched it there.
    pub approval: Option<Vec<u8>>,
}

impl LoginEntry {
    /// Windows keeps the "Startup apps" switch apart from the Run key, as a few
    /// bytes whose first is odd while the entry is switched off.
    fn switched_off_in_windows(&self) -> bool {
        self.approval
            .as_deref()
            .and_then(<[u8]>::first)
            .is_some_and(|b| b & 1 == 1)
    }

    /// Whether Windows will start this copy of the app at login.
    pub fn is_on(&self, exe: &str) -> bool {
        self.command.as_deref() == Some(login_command(exe).as_str())
            && !self.switched_off_in_windows()
    }

    /// What to do at startup so the entry matches the user's choice:
    /// `Some(true)` to write it, `Some(false)` to remove it, `None` to leave it.
    /// An entry the user switched off in Windows itself is left alone; turning
    /// the switch on in the app is what brings it back.
    pub fn repair(&self, wanted: bool, exe: &str) -> Option<bool> {
        match (&self.command, wanted) {
            (Some(_), false) => Some(false),
            (None, false) => None,
            (_, true) if self.switched_off_in_windows() => None,
            (Some(current), true) if *current == login_command(exe) => None,
            (_, true) => Some(true),
        }
    }
}

/// The current user's Run key, and the key where Windows keeps the "Startup
/// apps" switch. Nothing outside the current user's own settings is touched.
#[cfg(windows)]
pub mod registry {
    use super::LoginEntry;
    use windows_registry::CURRENT_USER;

    const RUN: &str = r"Software\Microsoft\Windows\CurrentVersion\Run";
    const APPROVED: &str =
        r"Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run";

    pub fn read(name: &str) -> LoginEntry {
        LoginEntry {
            command: CURRENT_USER
                .open(RUN)
                .and_then(|key| key.get_string(name))
                .ok(),
            approval: CURRENT_USER
                .open(APPROVED)
                .and_then(|key| key.get_value(name))
                .ok()
                .map(|value| value.to_vec()),
        }
    }

    pub fn write(name: &str, command: &str) -> Result<(), String> {
        CURRENT_USER
            .create(RUN)
            .and_then(|key| key.set_string(name, command))
            .map_err(|e| e.to_string())?;
        // The user asked for it in the app, so a "disabled" left under Startup apps must not win.
        if let Ok(key) = CURRENT_USER.options().write().open(APPROVED) {
            let _ = key.remove_value(name);
        }
        Ok(())
    }

    /// Removing an entry that is not there is not an error.
    pub fn remove(name: &str) -> Result<(), String> {
        let Ok(key) = CURRENT_USER.options().read().write().open(RUN) else {
            return Ok(());
        };
        if key.get_string(name).is_err() {
            return Ok(());
        }
        key.remove_value(name).map_err(|e| e.to_string())
    }
}

#[cfg(not(windows))]
pub mod registry {
    use super::LoginEntry;

    pub fn read(_name: &str) -> LoginEntry {
        LoginEntry::default()
    }

    pub fn write(_name: &str, _command: &str) -> Result<(), String> {
        Err("Starting at login is only available on Windows.".into())
    }

    pub fn remove(_name: &str) -> Result<(), String> {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const EXE: &str = r"C:\Users\me\AppData\Local\SMS Bridge\sms-bridge-desktop.exe";

    fn entry(command: Option<&str>, approval: Option<&[u8]>) -> LoginEntry {
        LoginEntry {
            command: command.map(str::to_owned),
            approval: approval.map(<[u8]>::to_vec),
        }
    }

    #[test]
    fn the_login_command_quotes_the_path_and_marks_the_start() {
        let command = login_command(EXE);
        assert_eq!(
            command,
            r#""C:\Users\me\AppData\Local\SMS Bridge\sms-bridge-desktop.exe" --autostart"#
        );
        assert!(started_at_login([EXE, LOGIN_ARG]));
        assert!(!started_at_login([EXE]));
        assert!(!started_at_login([EXE, "--autostart=no"]));
    }

    #[test]
    fn the_entry_is_on_only_for_this_copy_and_when_windows_allows_it() {
        let ours = login_command(EXE);
        assert!(entry(Some(&ours), None).is_on(EXE));
        assert!(entry(Some(&ours), Some(&[2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0])).is_on(EXE));
        assert!(
            !entry(Some(&ours), Some(&[3, 0, 0, 0, 9, 9, 9, 9, 9, 9, 9, 1])).is_on(EXE),
            "switched off under Startup apps"
        );
        assert!(!entry(None, None).is_on(EXE));
        assert!(
            !entry(Some(r#""D:\old\sms-bridge-desktop.exe" --autostart"#), None).is_on(EXE),
            "an entry for another copy"
        );
    }

    #[test]
    fn startup_repairs_the_entry_towards_the_users_choice() {
        let ours = login_command(EXE);
        assert_eq!(entry(None, None).repair(false, EXE), None);
        assert_eq!(
            entry(Some(&ours), None).repair(false, EXE),
            Some(false),
            "off means no entry"
        );
        assert_eq!(entry(Some(&ours), None).repair(true, EXE), None);
        assert_eq!(
            entry(None, None).repair(true, EXE),
            Some(true),
            "something removed it"
        );
        assert_eq!(
            entry(Some("\"D:\\old\\app.exe\" --autostart"), None).repair(true, EXE),
            Some(true),
            "the app moved"
        );
        assert_eq!(
            entry(Some(&ours), Some(&[3, 0, 0, 0])).repair(true, EXE),
            None,
            "the user switched it off in Windows; do not fight that"
        );
    }

    /// The real Run key, under a throwaway name. Only in CI: a developer's own
    /// list of startup programs is not a place for tests to write.
    #[cfg(windows)]
    #[test]
    fn the_entry_round_trips_through_the_registry() {
        if std::env::var_os("CI").is_none() {
            eprintln!("skipped outside CI: this test writes to the current user's Run key");
            return;
        }
        struct Cleanup(String);
        impl Drop for Cleanup {
            fn drop(&mut self) {
                let _ = registry::remove(&self.0);
            }
        }
        let name = Cleanup(format!("SMS Bridge test {}", std::process::id()));
        assert_eq!(registry::read(&name.0), LoginEntry::default());
        registry::remove(&name.0).expect("removing a missing entry is fine");

        registry::write(&name.0, &login_command(EXE)).expect("write");
        let entry = registry::read(&name.0);
        assert_eq!(entry.command.as_deref(), Some(login_command(EXE).as_str()));
        assert!(entry.is_on(EXE));
        assert_eq!(entry.repair(true, EXE), None);

        registry::remove(&name.0).expect("remove");
        assert_eq!(registry::read(&name.0), LoginEntry::default());
    }
}
