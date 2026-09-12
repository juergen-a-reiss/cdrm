package dev.juergenreiss.cdrm.kubernetes

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.patch
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import dev.juergenreiss.cdrm.workload.KubernetesKind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import java.time.Instant

class KubernetesDeploymentClientTest {

    private lateinit var server: WireMockServer
    private lateinit var kubeConfigLoader: KubeConfigLoader
    private lateinit var client: KubernetesDeploymentClient

    @BeforeEach
    fun setUp() {
        server = WireMockServer(0)
        server.start()
        kubeConfigLoader = mock(KubeConfigLoader::class.java)
        given(kubeConfigLoader.resolve("my-context")).willReturn(
            ClusterConnection(
                serverUrl = "http://localhost:${server.port()}",
                caCertificatePem = null,
                insecureSkipTlsVerify = true,
                bearerToken = "test-token",
                clientCertificatePem = null,
                clientKeyPem = null,
            )
        )
        client = KubernetesDeploymentClient(kubeConfigLoader)
    }

    @AfterEach
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `patches the image of a single-container deployment`() {
        server.stubFor(
            get(urlPathEqualTo("/apis/apps/v1/namespaces/platform/deployments/platform-api"))
                .willReturn(okJson("""{"spec":{"template":{"spec":{"containers":[{"name":"app","image":"old:1.0"}]}}}}"""))
        )
        server.stubFor(
            patch(urlPathEqualTo("/apis/apps/v1/namespaces/platform/deployments/platform-api"))
                .willReturn(aResponse().withStatus(200))
        )

        client.patchImage("my-context", "platform", KubernetesKind.DEPLOYMENT, "platform-api", "new:2.0")

        server.verify(
            getRequestedFor(urlEqualTo("/apis/apps/v1/namespaces/platform/deployments/platform-api"))
                .withHeader("Authorization", equalTo("Bearer test-token"))
        )
        server.verify(
            patchRequestedFor(urlEqualTo("/apis/apps/v1/namespaces/platform/deployments/platform-api"))
                .withHeader("Content-Type", equalTo("application/json-patch+json"))
                .withRequestBody(equalToJson("""[{"op":"replace","path":"/spec/template/spec/containers/0/image","value":"new:2.0"}]"""))
        )
    }

    @Test
    fun `patches a stateful set using the statefulsets resource path`() {
        server.stubFor(
            get(urlPathEqualTo("/apis/apps/v1/namespaces/platform/statefulsets/platform-db"))
                .willReturn(okJson("""{"spec":{"template":{"spec":{"containers":[{"name":"app","image":"old:1.0"}]}}}}"""))
        )
        server.stubFor(
            patch(urlPathEqualTo("/apis/apps/v1/namespaces/platform/statefulsets/platform-db"))
                .willReturn(aResponse().withStatus(200))
        )

        client.patchImage("my-context", "platform", KubernetesKind.STATEFUL_SET, "platform-db", "new:2.0")

        server.verify(patchRequestedFor(urlEqualTo("/apis/apps/v1/namespaces/platform/statefulsets/platform-db")))
    }

    @Test
    fun `rejects a manifest with more than one container`() {
        server.stubFor(
            get(urlPathEqualTo("/apis/apps/v1/namespaces/platform/deployments/platform-api"))
                .willReturn(
                    okJson(
                        """{"spec":{"template":{"spec":{"containers":[{"name":"app","image":"old:1.0"},{"name":"sidecar","image":"proxy:1.0"}]}}}}"""
                    )
                )
        )

        assertThrows(KubernetesDeploymentException::class.java) {
            client.patchImage("my-context", "platform", KubernetesKind.DEPLOYMENT, "platform-api", "new:2.0")
        }
    }

    @Test
    fun `rejects a manifest with no containers`() {
        server.stubFor(
            get(urlPathEqualTo("/apis/apps/v1/namespaces/platform/deployments/platform-api"))
                .willReturn(okJson("""{"spec":{"template":{"spec":{"containers":[]}}}}"""))
        )

        assertThrows(KubernetesDeploymentException::class.java) {
            client.patchImage("my-context", "platform", KubernetesKind.DEPLOYMENT, "platform-api", "new:2.0")
        }
    }

    private fun stubDeployment(replicas: Int = 2) {
        server.stubFor(
            get(urlPathEqualTo("/apis/apps/v1/namespaces/platform/deployments/platform-api"))
                .willReturn(
                    okJson(
                        """{"spec":{"replicas":$replicas,"selector":{"matchLabels":{"app":"platform-api"}},
                            |"template":{"spec":{"containers":[{"name":"app","image":"old:1.0"}]}}}}""".trimMargin()
                    )
                )
        )
    }

    private fun stubPods(vararg pods: String) {
        server.stubFor(
            get(urlPathEqualTo("/api/v1/namespaces/platform/pods"))
                .withQueryParam("labelSelector", equalTo("app=platform-api"))
                .willReturn(okJson("""{"items":[${pods.joinToString(",")}]}"""))
        )
    }

    private fun pod(image: String, ready: Boolean = true, restartCount: Int = 0) =
        """{"spec":{"containers":[{"name":"app","image":"$image"}]},
            |"status":{"containerStatuses":[{"name":"app","ready":$ready,"restartCount":$restartCount}]}}""".trimMargin()

    @Test
    fun `checkRollout reports ready once every pod matches replicas, image, readiness and no restarts`() {
        stubDeployment(replicas = 2)
        stubPods(pod("new:2.0"), pod("new:2.0"))

        val result = client.checkRollout("my-context", "platform", KubernetesKind.DEPLOYMENT, "platform-api", "new:2.0")

        assertTrue(result.ready)
    }

    @Test
    fun `checkRollout reports not ready when fewer pods than replicas exist`() {
        stubDeployment(replicas = 2)
        stubPods(pod("new:2.0"))

        val result = client.checkRollout("my-context", "platform", KubernetesKind.DEPLOYMENT, "platform-api", "new:2.0")

        assertFalse(result.ready)
        assertTrue(result.detail.contains("1/2 pods present"))
    }

    @Test
    fun `checkRollout reports not ready when a pod still runs the previous image`() {
        stubDeployment(replicas = 2)
        stubPods(pod("new:2.0"), pod("old:1.0"))

        val result = client.checkRollout("my-context", "platform", KubernetesKind.DEPLOYMENT, "platform-api", "new:2.0")

        assertFalse(result.ready)
        assertTrue(result.detail.contains("1 pod(s) still running the previous image"))
    }

    @Test
    fun `checkRollout reports not ready when a pod is not ready`() {
        stubDeployment(replicas = 1)
        stubPods(pod("new:2.0", ready = false))

        val result = client.checkRollout("my-context", "platform", KubernetesKind.DEPLOYMENT, "platform-api", "new:2.0")

        assertFalse(result.ready)
        assertTrue(result.detail.contains("1 pod(s) not ready"))
    }

    @Test
    fun `checkRollout reports not ready when a pod has restarted`() {
        stubDeployment(replicas = 1)
        stubPods(pod("new:2.0", restartCount = 3))

        val result = client.checkRollout("my-context", "platform", KubernetesKind.DEPLOYMENT, "platform-api", "new:2.0")

        assertFalse(result.ready)
        assertTrue(result.detail.contains("1 pod(s) restarting (restart count > 0)"))
    }

    private fun stubDeploymentWithMeta(replicas: Int = 1, image: String = "old:1.0", createdAt: String? = "2024-01-01T00:00:00Z") {
        val metaJson = if (createdAt != null) """"metadata":{"creationTimestamp":"$createdAt"},""" else ""
        server.stubFor(
            get(urlPathEqualTo("/apis/apps/v1/namespaces/platform/deployments/platform-api"))
                .willReturn(
                    okJson(
                        """{$metaJson"spec":{"replicas":$replicas,"selector":{"matchLabels":{"app":"platform-api"}},
                            |"template":{"spec":{"containers":[{"name":"app","image":"$image"}]}}}}""".trimMargin()
                    )
                )
        )
    }

    private fun podWithState(
        name: String = "platform-api-abc",
        image: String = "old:1.0",
        ready: Boolean = true,
        restartCount: Int = 0,
        startedAt: String? = "2024-01-02T00:00:00Z",
    ): String {
        val stateJson = if (startedAt != null) ""","state":{"running":{"startedAt":"$startedAt"}}""" else ""
        return """{"metadata":{"name":"$name"},"spec":{"containers":[{"name":"app","image":"$image"}]},
            |"status":{"containerStatuses":[{"name":"app","ready":$ready,"restartCount":$restartCount$stateJson}]}}""".trimMargin()
    }

    @Test
    fun `getLiveStatus returns null when the resource doesn't exist`() {
        server.stubFor(
            get(urlPathEqualTo("/apis/apps/v1/namespaces/platform/deployments/platform-api"))
                .willReturn(aResponse().withStatus(404))
        )

        val result = client.getLiveStatus("my-context", "platform", KubernetesKind.DEPLOYMENT, "platform-api")

        assertEquals(null, result)
    }

    @Test
    fun `getLiveStatus parses desiredReplicas, declaredImage, resourceCreatedAt and per-pod details`() {
        stubDeploymentWithMeta(replicas = 2, image = "new:2.0", createdAt = "2024-01-01T00:00:00Z")
        stubPods(podWithState(name = "pod-1", image = "new:2.0", ready = true, restartCount = 1, startedAt = "2024-01-02T00:00:00Z"))

        val result = client.getLiveStatus("my-context", "platform", KubernetesKind.DEPLOYMENT, "platform-api")!!

        assertEquals(2, result.desiredReplicas)
        assertEquals("new:2.0", result.declaredImage)
        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), result.resourceCreatedAt)
        val pod = result.pods.single()
        assertEquals("pod-1", pod.name)
        assertEquals("new:2.0", pod.image)
        assertTrue(pod.ready)
        assertEquals(1, pod.restartCount)
        assertEquals(Instant.parse("2024-01-02T00:00:00Z"), pod.runningSince)
    }

    @Test
    fun `getLiveStatus reports a null runningSince for a pod never observed running`() {
        stubDeploymentWithMeta(replicas = 1)
        stubPods(podWithState(ready = false, startedAt = null))

        val result = client.getLiveStatus("my-context", "platform", KubernetesKind.DEPLOYMENT, "platform-api")!!

        assertNull(result.pods.single().runningSince)
    }

    @Test
    fun `getLiveStatus propagates a non-404 error instead of swallowing it`() {
        server.stubFor(
            get(urlPathEqualTo("/apis/apps/v1/namespaces/platform/deployments/platform-api"))
                .willReturn(aResponse().withStatus(500))
        )

        assertThrows(Exception::class.java) {
            client.getLiveStatus("my-context", "platform", KubernetesKind.DEPLOYMENT, "platform-api")
        }
    }
}
