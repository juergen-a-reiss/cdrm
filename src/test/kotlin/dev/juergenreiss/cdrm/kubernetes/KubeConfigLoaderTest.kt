package dev.juergenreiss.cdrm.kubernetes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

class KubeConfigLoaderTest {

    private fun kubeconfig(dir: Path, contents: String): String {
        val file = dir.resolve("kubeconfig.yaml")
        Files.writeString(file, contents)
        return file.toString()
    }

    @Test
    fun `resolves a token-authenticated context`(@TempDir dir: Path) {
        val path = kubeconfig(
            dir,
            """
            clusters:
              - name: cluster-a
                cluster:
                  server: https://cluster-a.example.com:6443
                  certificate-authority-data: ${Base64.getEncoder().encodeToString("ca-pem-bytes".toByteArray())}
            contexts:
              - name: my-context
                context:
                  cluster: cluster-a
                  user: user-a
            users:
              - name: user-a
                user:
                  token: secret-token
            """.trimIndent(),
        )

        val connection = KubeConfigLoader(path).resolve("my-context")

        assertEquals("https://cluster-a.example.com:6443", connection.serverUrl)
        assertEquals("ca-pem-bytes", connection.caCertificatePem)
        assertEquals("secret-token", connection.bearerToken)
        assertFalse(connection.insecureSkipTlsVerify)
        assertNull(connection.clientCertificatePem)
    }

    @Test
    fun `resolves a client-certificate-authenticated context with insecure skip verify`(@TempDir dir: Path) {
        val path = kubeconfig(
            dir,
            """
            clusters:
              - name: cluster-a
                cluster:
                  server: https://cluster-a.example.com:6443
                  insecure-skip-tls-verify: true
            contexts:
              - name: my-context
                context:
                  cluster: cluster-a
                  user: user-a
            users:
              - name: user-a
                user:
                  client-certificate-data: ${Base64.getEncoder().encodeToString("cert-pem".toByteArray())}
                  client-key-data: ${Base64.getEncoder().encodeToString("key-pem".toByteArray())}
            """.trimIndent(),
        )

        val connection = KubeConfigLoader(path).resolve("my-context")

        assertTrue(connection.insecureSkipTlsVerify)
        assertNull(connection.caCertificatePem)
        assertNull(connection.bearerToken)
        assertEquals("cert-pem", connection.clientCertificatePem)
        assertEquals("key-pem", connection.clientKeyPem)
    }

    @Test
    fun `throws when the context is not found`(@TempDir dir: Path) {
        val path = kubeconfig(dir, "clusters: []\ncontexts: []\nusers: []")

        val exception = assertThrows(KubernetesConfigException::class.java) {
            KubeConfigLoader(path).resolve("missing-context")
        }
        assertTrue(exception.message!!.contains("missing-context"))
    }

    @Test
    fun `throws when the user has no supported credentials`(@TempDir dir: Path) {
        val path = kubeconfig(
            dir,
            """
            clusters:
              - name: cluster-a
                cluster:
                  server: https://cluster-a.example.com:6443
            contexts:
              - name: my-context
                context:
                  cluster: cluster-a
                  user: user-a
            users:
              - name: user-a
                user: {}
            """.trimIndent(),
        )

        val exception = assertThrows(KubernetesConfigException::class.java) {
            KubeConfigLoader(path).resolve("my-context")
        }
        assertTrue(exception.message!!.contains("no supported credentials"))
    }

    @Test
    fun `throws when the kubeconfig file does not exist`(@TempDir dir: Path) {
        val missingPath = dir.resolve("does-not-exist.yaml").toString()

        assertThrows(KubernetesConfigException::class.java) {
            KubeConfigLoader(missingPath).resolve("any-context")
        }
    }
}
