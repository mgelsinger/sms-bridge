//! Talking to the phone: TLS with a pinned certificate, REST over HTTP/1.1,
//! and the WebSocket. One TLS configuration serves both, so there is exactly one
//! place where the server's identity is checked.

use crate::protocol::*;
use http_body_util::{BodyExt, Full};
use hyper::body::Bytes;
use hyper::header::{AUTHORIZATION, CONTENT_TYPE, HOST};
use hyper::Request;
use hyper_util::rt::TokioIo;
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::CryptoProvider;
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{CertificateError, ClientConfig, DigitallySignedStruct, SignatureScheme};
use serde::de::DeserializeOwned;
use std::sync::Arc;
use std::time::Duration;
use tokio::net::TcpStream;
use tokio_rustls::client::TlsStream;
use tokio_rustls::TlsConnector;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::WebSocketStream;

pub type PhoneSocket = WebSocketStream<TlsStream<TcpStream>>;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Debug, thiserror::Error)]
pub enum NetError {
    #[error("the phone's certificate does not match the paired fingerprint")]
    FingerprintMismatch,
    #[error("could not reach the phone: {0}")]
    Unreachable(String),
    #[error("timed out talking to the phone")]
    Timeout,
    #[error("the phone refused the request: {code:?} ({message})")]
    Refused {
        status: u16,
        code: ErrorCode,
        message: String,
    },
    #[error("unexpected HTTP status {0}")]
    Status(u16),
    #[error("the phone sent something this app cannot read: {0}")]
    Decode(String),
    #[error("invalid fingerprint; expected 64 lowercase hex characters")]
    BadFingerprint,
}

impl NetError {
    /// The token is no longer valid; the user has to pair again.
    pub fn is_unauthorized(&self) -> bool {
        matches!(
            self,
            NetError::Refused {
                code: ErrorCode::Unauthorized,
                ..
            }
        ) || matches!(self, NetError::Status(401))
    }
}

/// Where the phone is and who it must be.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PhoneAddress {
    pub host: String,
    pub port: u16,
    /// Lowercase hex SHA-256 of the phone's DER certificate.
    pub fingerprint: String,
}

pub fn sha256_hex(bytes: &[u8]) -> String {
    let digest = ring::digest::digest(&ring::digest::SHA256, bytes);
    digest.as_ref().iter().map(|b| format!("{b:02x}")).collect()
}

/// Accepts exactly one certificate, by SHA-256 fingerprint, and still checks
/// the handshake signatures against that certificate's key. Hostnames and CA
/// chains are irrelevant once the exact certificate is pinned.
#[derive(Debug)]
struct PinnedVerifier {
    fingerprint: String,
    provider: Arc<CryptoProvider>,
}

impl ServerCertVerifier for PinnedVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        let actual = sha256_hex(end_entity.as_ref());
        // The fingerprint is public, so a plain comparison is fine here.
        if actual == self.fingerprint {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::InvalidCertificate(
                CertificateError::ApplicationVerificationFailure,
            ))
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

fn valid_fingerprint(fp: &str) -> bool {
    fp.len() == 64
        && fp
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

fn tls_connector(fingerprint: &str) -> Result<TlsConnector, NetError> {
    if !valid_fingerprint(fingerprint) {
        return Err(NetError::BadFingerprint);
    }
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let config = ClientConfig::builder_with_provider(provider.clone())
        .with_protocol_versions(&[&rustls::version::TLS13, &rustls::version::TLS12])
        .map_err(|e| NetError::Unreachable(e.to_string()))?
        .dangerous() // Custom verification: pinning, which is stricter than CA validation.
        .with_custom_certificate_verifier(Arc::new(PinnedVerifier {
            fingerprint: fingerprint.to_owned(),
            provider,
        }))
        .with_no_client_auth();
    Ok(TlsConnector::from(Arc::new(config)))
}

fn map_io(e: std::io::Error) -> NetError {
    let pinned = e
        .get_ref()
        .and_then(|inner| inner.downcast_ref::<rustls::Error>())
        .is_some_and(|re| matches!(re, rustls::Error::InvalidCertificate(_)));
    if pinned {
        NetError::FingerprintMismatch
    } else {
        NetError::Unreachable(e.to_string())
    }
}

/// Records the certificate a server presents, and still checks that the server
/// holds the matching key. Used only by [`probe_fingerprint`], which sends no
/// data: the user compares the result with the phone's screen before pairing.
#[derive(Debug)]
struct RecordingVerifier {
    seen: std::sync::Mutex<Option<String>>,
    provider: Arc<CryptoProvider>,
}

impl ServerCertVerifier for RecordingVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        *self.seen.lock().unwrap_or_else(|p| p.into_inner()) =
            Some(sha256_hex(end_entity.as_ref()));
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

/// Completes a TLS handshake, sends nothing, and returns the fingerprint of the
/// certificate the server proved it holds the key for. The caller must have a
/// person confirm it against the phone's screen (or the QR code) before pinning.
pub async fn probe_fingerprint(host: &str, port: u16) -> Result<String, NetError> {
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let verifier = Arc::new(RecordingVerifier {
        seen: std::sync::Mutex::new(None),
        provider: provider.clone(),
    });
    let config = ClientConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&rustls::version::TLS13, &rustls::version::TLS12])
        .map_err(|e| NetError::Unreachable(e.to_string()))?
        .dangerous()
        .with_custom_certificate_verifier(verifier.clone())
        .with_no_client_auth();
    let name = ServerName::try_from(host.to_owned())
        .map_err(|_| NetError::Unreachable("bad host".into()))?;
    let fut = async {
        let tcp = TcpStream::connect((host, port)).await.map_err(map_io)?;
        let mut tls = TlsConnector::from(Arc::new(config))
            .connect(name, tcp)
            .await
            .map_err(map_io)?;
        let _ = tokio::io::AsyncWriteExt::shutdown(&mut tls).await;
        Ok::<_, NetError>(())
    };
    tokio::time::timeout(CONNECT_TIMEOUT, fut)
        .await
        .map_err(|_| NetError::Timeout)??;
    let seen = verifier
        .seen
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .clone();
    seen.ok_or_else(|| NetError::Unreachable("no certificate presented".into()))
}

/// A client for one paired (or about to pair) phone.
#[derive(Clone)]
pub struct PhoneClient {
    address: PhoneAddress,
    token: Option<String>,
    tls: TlsConnector,
}

impl PhoneClient {
    pub fn new(address: PhoneAddress, token: Option<String>) -> Result<Self, NetError> {
        let tls = tls_connector(&address.fingerprint)?;
        Ok(Self {
            address,
            token,
            tls,
        })
    }

    pub fn address(&self) -> &PhoneAddress {
        &self.address
    }

    fn host_header(&self) -> String {
        if self.address.host.contains(':') {
            format!("[{}]:{}", self.address.host, self.address.port)
        } else {
            format!("{}:{}", self.address.host, self.address.port)
        }
    }

    async fn connect(&self) -> Result<TlsStream<TcpStream>, NetError> {
        let name = ServerName::try_from(self.address.host.clone())
            .map_err(|_| NetError::Unreachable("bad host".into()))?;
        let fut = async {
            let tcp = TcpStream::connect((self.address.host.as_str(), self.address.port))
                .await
                .map_err(map_io)?;
            tcp.set_nodelay(true).map_err(map_io)?;
            self.tls.connect(name, tcp).await.map_err(map_io)
        };
        tokio::time::timeout(CONNECT_TIMEOUT, fut)
            .await
            .map_err(|_| NetError::Timeout)?
    }

    /// One request on a fresh connection. LAN TLS handshakes are cheap and this
    /// keeps failure handling simple.
    async fn request(
        &self,
        method: &str,
        path: &str,
        body: Option<Vec<u8>>,
    ) -> Result<(u16, String, Bytes), NetError> {
        let fut = async {
            let tls = self.connect().await?;
            let (mut sender, conn) = hyper::client::conn::http1::handshake(TokioIo::new(tls))
                .await
                .map_err(|e| NetError::Unreachable(e.to_string()))?;
            let driver = tokio::spawn(async move {
                let _ = conn.await;
            });
            let mut req = Request::builder()
                .method(method)
                .uri(path)
                .header(HOST, self.host_header());
            if let Some(token) = &self.token {
                req = req.header(AUTHORIZATION, format!("Bearer {token}"));
            }
            if body.is_some() {
                req = req.header(CONTENT_TYPE, "application/json");
            }
            let req = req
                .body(Full::new(Bytes::from(body.unwrap_or_default())))
                .map_err(|e| NetError::Decode(e.to_string()))?;
            let resp = sender
                .send_request(req)
                .await
                .map_err(|e| NetError::Unreachable(e.to_string()))?;
            let status = resp.status().as_u16();
            let content_type = resp
                .headers()
                .get(CONTENT_TYPE)
                .and_then(|v| v.to_str().ok())
                .unwrap_or_default()
                .to_owned();
            let bytes = resp
                .into_body()
                .collect()
                .await
                .map_err(|e| NetError::Unreachable(e.to_string()))?
                .to_bytes();
            driver.abort();
            Ok((status, content_type, bytes))
        };
        tokio::time::timeout(REQUEST_TIMEOUT, fut)
            .await
            .map_err(|_| NetError::Timeout)?
    }

    async fn json<T: DeserializeOwned>(
        &self,
        method: &str,
        path: &str,
        body: Option<Vec<u8>>,
    ) -> Result<T, NetError> {
        let (status, _, bytes) = self.request(method, path, body).await?;
        if status == 200 {
            return serde_json::from_slice(&bytes).map_err(|e| NetError::Decode(e.to_string()));
        }
        match serde_json::from_slice::<ErrorBody>(&bytes) {
            Ok(err) => Err(NetError::Refused {
                status,
                code: err.error.code,
                message: err.error.message,
            }),
            Err(_) => Err(NetError::Status(status)),
        }
    }

    pub async fn pair(
        &self,
        pairing_code: &str,
        client_name: &str,
    ) -> Result<PairResponse, NetError> {
        let body = PairRequest {
            protocol_version: PROTOCOL_VERSION,
            pairing_code: pairing_code.to_owned(),
            client_name: client_name.to_owned(),
        };
        let bytes = serde_json::to_vec(&body).map_err(|e| NetError::Decode(e.to_string()))?;
        self.json("POST", "/pair", Some(bytes)).await
    }

    pub async fn status(&self) -> Result<StatusResponse, NetError> {
        self.json("GET", "/status", None).await
    }

    pub async fn threads(&self, limit: u32, cursor: Option<&str>) -> Result<ThreadsPage, NetError> {
        self.json("GET", &with_query("/threads", limit, cursor), None)
            .await
    }

    pub async fn thread_messages(
        &self,
        thread_id: u64,
        limit: u32,
        cursor: Option<&str>,
    ) -> Result<MessagesPage, NetError> {
        self.json(
            "GET",
            &with_query(&format!("/threads/{thread_id}/messages"), limit, cursor),
            None,
        )
        .await
    }

    /// Incremental sync. `None` returns a cursor for "now" and no messages.
    pub async fn sync(&self, cursor: Option<&str>, limit: u32) -> Result<SyncPage, NetError> {
        self.json("GET", &with_query("/messages", limit, cursor), None)
            .await
    }

    pub async fn contacts(&self) -> Result<ContactsResponse, NetError> {
        self.json("GET", "/contacts", None).await
    }

    /// Raw bytes and content type of an MMS attachment.
    pub async fn mms_part(&self, part_id: u64) -> Result<(String, Bytes), NetError> {
        let (status, content_type, bytes) = self
            .request("GET", &format!("/mms/{part_id}"), None)
            .await?;
        if status == 200 {
            Ok((content_type, bytes))
        } else {
            Err(NetError::Status(status))
        }
    }

    pub async fn websocket(&self) -> Result<PhoneSocket, NetError> {
        let tls = self.connect().await?;
        let mut req = format!("wss://{}/ws", self.host_header())
            .into_client_request()
            .map_err(|e| NetError::Decode(e.to_string()))?;
        if let Some(token) = &self.token {
            let value = format!("Bearer {token}")
                .parse()
                .map_err(|_| NetError::Decode("token".into()))?;
            req.headers_mut().insert(AUTHORIZATION, value);
        }
        let fut = tokio_tungstenite::client_async(req, tls);
        match tokio::time::timeout(CONNECT_TIMEOUT, fut).await {
            Err(_) => Err(NetError::Timeout),
            Ok(Ok((socket, _))) => Ok(socket),
            Ok(Err(tokio_tungstenite::tungstenite::Error::Http(resp))) => {
                Err(NetError::Status(resp.status().as_u16()))
            }
            Ok(Err(e)) => Err(NetError::Unreachable(e.to_string())),
        }
    }
}

fn with_query(path: &str, limit: u32, cursor: Option<&str>) -> String {
    let mut s = format!("{path}?limit={limit}");
    if let Some(c) = cursor {
        s.push_str("&cursor=");
        s.push_str(&percent_encode(c));
    }
    s
}

/// Cursors are opaque, so encode everything outside the unreserved set.
fn percent_encode(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                (b as char).to_string()
            }
            _ => format!("%{b:02X}"),
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fingerprints_must_be_64_lowercase_hex() {
        assert!(valid_fingerprint(&"a".repeat(64)));
        assert!(!valid_fingerprint(&"A".repeat(64)));
        assert!(!valid_fingerprint(&"a".repeat(63)));
        assert!(!valid_fingerprint(&"g".repeat(64)));
        let addr = PhoneAddress {
            host: "127.0.0.1".into(),
            port: 1,
            fingerprint: "nope".into(),
        };
        assert!(matches!(
            PhoneClient::new(addr, None),
            Err(NetError::BadFingerprint)
        ));
    }

    #[test]
    fn cursors_are_percent_encoded() {
        assert_eq!(
            with_query("/messages", 5, Some("s1.m2")),
            "/messages?limit=5&cursor=s1.m2"
        );
        assert_eq!(
            with_query("/t", 1, Some("a b&c")),
            "/t?limit=1&cursor=a%20b%26c"
        );
        assert_eq!(with_query("/t", 1, None), "/t?limit=1");
    }
}
