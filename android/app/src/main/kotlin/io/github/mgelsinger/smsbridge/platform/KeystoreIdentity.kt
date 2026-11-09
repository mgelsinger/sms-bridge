package io.github.mgelsinger.smsbridge.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.mgelsinger.smsbridge.server.TlsIdentity
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * The phone's TLS key lives in Android Keystore and never leaves it. Keystore
 * issues the self-signed certificate for the key; clients pin its fingerprint.
 */
object KeystoreIdentity {
    const val DEFAULT_ALIAS = "sms-bridge-tls"
    private const val TEN_YEARS_MS = 10L * 365 * 24 * 60 * 60 * 1000

    fun loadOrCreate(alias: String = DEFAULT_ALIAS): TlsIdentity {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(alias)) generate(alias)
        val key = ks.getKey(alias, null) as PrivateKey
        val cert = ks.getCertificate(alias) as X509Certificate
        return TlsIdentity(key, cert)
    }

    /** Deletes the key. Every paired PC must pair again afterwards. */
    fun reset(alias: String = DEFAULT_ALIAS) {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    private fun generate(alias: String) {
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            // TLS stacks sign either a raw digest (NONE) or the full transcript hash.
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            .setCertificateSubject(X500Principal("CN=SMS Bridge"))
            .setCertificateSerialNumber(BigInteger(64, SecureRandom()).add(BigInteger.ONE))
            .setCertificateNotBefore(Date(now - 24 * 60 * 60 * 1000))
            .setCertificateNotAfter(Date(now + TEN_YEARS_MS))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(spec)
            generateKeyPair()
        }
    }
}
