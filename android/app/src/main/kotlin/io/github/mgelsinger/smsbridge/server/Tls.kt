package io.github.mgelsinger.smsbridge.server

import io.github.mgelsinger.smsbridge.core.sha256Hex
import java.io.InputStream
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/** The phone's TLS identity: one EC key and its self-signed certificate. */
class TlsIdentity(private val key: PrivateKey, val certificate: X509Certificate) {
    /** Lowercase hex SHA-256 of the DER certificate, as clients pin it. */
    val fingerprint: String = sha256Hex(certificate.encoded)

    val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(arrayOf(SingleKeyManager(ALIAS, key, arrayOf(certificate))), null, null)
        }
    }

    companion object {
        private const val ALIAS = "sms-bridge"

        /** Loads an identity from a PKCS#12 file. Used by tests; the phone uses Android Keystore. */
        fun fromPkcs12(input: InputStream, password: CharArray, alias: String): TlsIdentity {
            val ks = KeyStore.getInstance("PKCS12").apply { load(input, password) }
            val key = ks.getKey(alias, password) as PrivateKey
            return TlsIdentity(key, ks.getCertificate(alias) as X509Certificate)
        }
    }
}

/**
 * Always presents the one key. Implemented directly so it works the same with a
 * PKCS#12 key in tests and a non-exportable Android Keystore key on the phone.
 */
private class SingleKeyManager(
    private val alias: String,
    private val key: PrivateKey,
    private val chain: Array<X509Certificate>,
) : X509ExtendedKeyManager() {
    private fun pick(keyType: String?) = alias.takeIf { keyType == null || keyType.startsWith("EC") }
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = pick(keyType)
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = pick(keyType)
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = pick(keyType)?.let { arrayOf(it) }
    override fun getPrivateKey(alias: String?) = key
    override fun getCertificateChain(alias: String?) = chain
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
}

/**
 * Trusts exactly one certificate, by SHA-256 fingerprint. This is what a client
 * does after pairing; it is here for tests and documentation. Hostname checks
 * add nothing once the exact certificate is pinned.
 */
class PinnedTrustManager(private val fingerprint: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
        val actual = sha256Hex(leaf.encoded)
        if (actual != fingerprint) throw CertificateException("certificate fingerprint $actual does not match the pinned one")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("client certificates are not used")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
