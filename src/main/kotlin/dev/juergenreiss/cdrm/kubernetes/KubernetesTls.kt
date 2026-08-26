// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.kubernetes

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPrivateCrtKeySpec
import java.util.Base64
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

// PKCS8-encoded ("BEGIN PRIVATE KEY") RSA/EC and PKCS1-encoded ("BEGIN RSA PRIVATE KEY")
// client keys are supported — minikube's own generated client key is PKCS1 RSA. Legacy
// SEC1 ("BEGIN EC PRIVATE KEY") keys are not supported.
object KubernetesTls {

    private val CLIENT_KEY_PASSWORD = CharArray(0)

    private val TRUST_ALL_MANAGERS: Array<TrustManager> = arrayOf(
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    )

    fun buildSslContext(connection: ClusterConnection): SSLContext {
        val trustManagers = if (connection.insecureSkipTlsVerify) {
            TRUST_ALL_MANAGERS
        } else {
            val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                connection.caCertificatePem?.let { pem ->
                    parseCertificates(pem).forEachIndexed { index, cert -> setCertificateEntry("ca-$index", cert) }
                }
            }
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(trustStore)
            }.trustManagers
        }

        val keyManagers = if (connection.clientCertificatePem != null && connection.clientKeyPem != null) {
            buildKeyManagers(connection.clientCertificatePem, connection.clientKeyPem)
        } else {
            null
        }

        return SSLContext.getInstance("TLS").apply { init(keyManagers, trustManagers, SecureRandom()) }
    }

    private fun buildKeyManagers(certPem: String, keyPem: String): Array<KeyManager> {
        val certs = parseCertificates(certPem)
        val privateKey = parsePrivateKey(keyPem)
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("client", privateKey, CLIENT_KEY_PASSWORD, certs.toTypedArray())
        }
        return KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, CLIENT_KEY_PASSWORD)
        }.keyManagers
    }

    private fun parseCertificates(pem: String): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificates(ByteArrayInputStream(pem.toByteArray())).map { it as X509Certificate }
    }

    private fun parsePrivateKey(pem: String): PrivateKey {
        val base64 = pem.lineSequence().filterNot { it.startsWith("-----") }.joinToString("")
        val keyBytes = Base64.getDecoder().decode(base64)

        if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            return parsePkcs1RsaPrivateKey(keyBytes)
        }

        val spec = PKCS8EncodedKeySpec(keyBytes)
        for (algorithm in listOf("RSA", "EC")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec)
            } catch (e: InvalidKeySpecException) {
                // try the next algorithm
            }
        }
        throw KubernetesConfigException(
            "Unsupported private key format — only PKCS8-encoded RSA/EC keys and PKCS1-encoded RSA keys are supported"
        )
    }

    // No PKCS1 support in the JDK's KeyFactory — hand-parses the RSAPrivateKey DER
    // SEQUENCE (RFC 8017 A.1.2: version, n, e, d, p, q, d mod (p-1), d mod (q-1), qInv)
    // into an RSAPrivateCrtKeySpec instead of pulling in a library just for this.
    private fun parsePkcs1RsaPrivateKey(der: ByteArray): PrivateKey {
        val reader = DerReader(der)
        reader.readSequenceHeader()
        reader.readInteger() // version
        val modulus = reader.readInteger()
        val publicExponent = reader.readInteger()
        val privateExponent = reader.readInteger()
        val prime1 = reader.readInteger()
        val prime2 = reader.readInteger()
        val exponent1 = reader.readInteger()
        val exponent2 = reader.readInteger()
        val coefficient = reader.readInteger()
        val spec = RSAPrivateCrtKeySpec(modulus, publicExponent, privateExponent, prime1, prime2, exponent1, exponent2, coefficient)
        return KeyFactory.getInstance("RSA").generatePrivate(spec)
    }

    private class DerReader(private val data: ByteArray) {
        private var pos = 0

        fun readSequenceHeader() {
            require(data[pos].toInt() and 0xFF == 0x30) { "Expected DER SEQUENCE" }
            pos++
            readLength()
        }

        fun readInteger(): BigInteger {
            require(data[pos].toInt() and 0xFF == 0x02) { "Expected DER INTEGER" }
            pos++
            val length = readLength()
            val bytes = data.copyOfRange(pos, pos + length)
            pos += length
            return BigInteger(bytes)
        }

        private fun readLength(): Int {
            val first = data[pos].toInt() and 0xFF
            pos++
            if (first < 0x80) return first
            val numBytes = first and 0x7F
            var length = 0
            repeat(numBytes) {
                length = (length shl 8) or (data[pos].toInt() and 0xFF)
                pos++
            }
            return length
        }
    }
}
