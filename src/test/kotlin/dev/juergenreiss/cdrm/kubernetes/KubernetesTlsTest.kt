package dev.juergenreiss.cdrm.kubernetes

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

// Exercises the hand-rolled PEM/PKCS8 parsing against real openssl-generated material,
// since a typo there would otherwise only surface against a live cluster.
class KubernetesTlsTest {

    companion object {
        private lateinit var caCertPem: String
        private lateinit var clientCertPem: String
        private lateinit var clientKeyPem: String
        private lateinit var clientKeyPkcs1Pem: String

        @JvmStatic
        @BeforeAll
        fun generateCertificates(@TempDir dir: Path) {
            fun run(vararg command: String) {
                val process = ProcessBuilder(*command).directory(dir.toFile()).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                check(process.waitFor() == 0) { "Command failed: ${command.joinToString(" ")}\n$output" }
            }

            run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-keyout", "ca-key.pem", "-out", "ca-cert.pem", "-days", "1", "-nodes", "-subj", "/CN=test-ca")
            run("openssl", "req", "-newkey", "rsa:2048", "-keyout", "client-key-pkcs1.pem", "-out", "client.csr", "-nodes", "-subj", "/CN=test-client")
            run("openssl", "x509", "-req", "-in", "client.csr", "-CA", "ca-cert.pem", "-CAkey", "ca-key.pem", "-CAcreateserial", "-out", "client-cert.pem", "-days", "1")
            run("openssl", "pkcs8", "-topk8", "-nocrypt", "-in", "client-key-pkcs1.pem", "-out", "client-key-pkcs8.pem")

            caCertPem = Files.readString(dir.resolve("ca-cert.pem"))
            clientCertPem = Files.readString(dir.resolve("client-cert.pem"))
            clientKeyPem = Files.readString(dir.resolve("client-key-pkcs8.pem"))
            clientKeyPkcs1Pem = Files.readString(dir.resolve("client-key-pkcs1.pem"))
        }
    }

    @Test
    fun `builds an SSL context from a CA certificate only`() {
        val context = KubernetesTls.buildSslContext(
            ClusterConnection(
                serverUrl = "https://example.com",
                caCertificatePem = caCertPem,
                insecureSkipTlsVerify = false,
                bearerToken = "token",
                clientCertificatePem = null,
                clientKeyPem = null,
            )
        )
        assertNotNull(context)
    }

    @Test
    fun `builds an SSL context with a PKCS8 client certificate and key`() {
        val context = KubernetesTls.buildSslContext(
            ClusterConnection(
                serverUrl = "https://example.com",
                caCertificatePem = caCertPem,
                insecureSkipTlsVerify = false,
                bearerToken = null,
                clientCertificatePem = clientCertPem,
                clientKeyPem = clientKeyPem,
            )
        )
        assertNotNull(context)
    }

    @Test
    fun `builds an SSL context with a PKCS1 RSA client key`() {
        // Matches minikube's own generated client.key format ("BEGIN RSA PRIVATE KEY").
        val context = KubernetesTls.buildSslContext(
            ClusterConnection(
                serverUrl = "https://example.com",
                caCertificatePem = caCertPem,
                insecureSkipTlsVerify = false,
                bearerToken = null,
                clientCertificatePem = clientCertPem,
                clientKeyPem = clientKeyPkcs1Pem,
            )
        )
        assertNotNull(context)
    }

    @Test
    fun `builds an SSL context with insecure skip verify and no CA data`() {
        val context = KubernetesTls.buildSslContext(
            ClusterConnection(
                serverUrl = "https://example.com",
                caCertificatePem = null,
                insecureSkipTlsVerify = true,
                bearerToken = "token",
                clientCertificatePem = null,
                clientKeyPem = null,
            )
        )
        assertNotNull(context)
    }

    @Test
    fun `rejects a malformed private key`() {
        assertThrows(KubernetesConfigException::class.java) {
            KubernetesTls.buildSslContext(
                ClusterConnection(
                    serverUrl = "https://example.com",
                    caCertificatePem = caCertPem,
                    insecureSkipTlsVerify = false,
                    bearerToken = null,
                    clientCertificatePem = clientCertPem,
                    clientKeyPem = "-----BEGIN PRIVATE KEY-----\nbm90LWEta2V5\n-----END PRIVATE KEY-----\n",
                )
            )
        }
    }
}
