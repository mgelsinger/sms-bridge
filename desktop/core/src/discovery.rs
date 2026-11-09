//! Finds phones advertising `_smsbridge._tcp` on the local network.

use mdns_sd::{ServiceDaemon, ServiceEvent};
use serde::Serialize;
use std::collections::BTreeMap;
use std::net::IpAddr;
use std::time::{Duration, Instant};

pub const SERVICE_TYPE: &str = "_smsbridge._tcp.local.";

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct DiscoveredPhone {
    pub name: String,
    pub host: String,
    pub port: u16,
    /// First 16 hex characters of the certificate fingerprint, from the TXT record.
    pub fingerprint_prefix: Option<String>,
    pub protocol_version: Option<String>,
}

/// Browses for `wait` and returns what answered. IPv4 addresses are preferred.
pub fn browse(wait: Duration) -> Result<Vec<DiscoveredPhone>, String> {
    let daemon = ServiceDaemon::new().map_err(|e| e.to_string())?;
    let rx = daemon.browse(SERVICE_TYPE).map_err(|e| e.to_string())?;
    let deadline = Instant::now() + wait;
    let mut found = BTreeMap::new();
    while let Some(left) = deadline.checked_duration_since(Instant::now()) {
        match rx.recv_timeout(left) {
            Ok(ServiceEvent::ServiceResolved(info)) => {
                let mut addrs: Vec<IpAddr> = info.get_addresses().iter().copied().collect();
                addrs.sort_by_key(|a| !a.is_ipv4());
                let Some(addr) = addrs.first() else { continue };
                let name = info
                    .get_fullname()
                    .trim_end_matches(SERVICE_TYPE)
                    .trim_end_matches('.')
                    .to_owned();
                found.insert(
                    info.get_fullname().to_owned(),
                    DiscoveredPhone {
                        name,
                        host: addr.to_string(),
                        port: info.get_port(),
                        fingerprint_prefix: info.get_property_val_str("fp").map(str::to_owned),
                        protocol_version: info.get_property_val_str("v").map(str::to_owned),
                    },
                );
            }
            Ok(_) => {}
            Err(_) => break,
        }
    }
    let _ = daemon.shutdown();
    Ok(found.into_values().collect())
}

/// True when the probed fingerprint agrees with the prefix the phone advertised.
pub fn prefix_matches(phone: &DiscoveredPhone, fingerprint: &str) -> bool {
    phone
        .fingerprint_prefix
        .as_deref()
        .is_none_or(|p| fingerprint.starts_with(&p.to_ascii_lowercase()))
}
