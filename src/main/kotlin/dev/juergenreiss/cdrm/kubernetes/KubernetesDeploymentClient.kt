// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.kubernetes

import dev.juergenreiss.cdrm.workload.KubernetesKind
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.net.http.HttpClient
import java.time.Duration

// Whether a Deployment/StatefulSet's rollout to a given image has actually completed —
// not just accepted (see patchImage). detail explains why not when ready is false, or
// "rollout complete" when true; DeploymentVerificationJob logs/stores it either way.
data class RolloutStatus(val ready: Boolean, val detail: String)

// Patches only the container image of a Deployment/StatefulSet via a JSON Patch PATCH
// request — no full manifest management. Requires the workload's pod spec to have
// exactly one container, since a strategic-merge/JSON patch by index can't otherwise
// tell which container to update.
@Component
class KubernetesDeploymentClient(
    private val kubeConfigLoader: KubeConfigLoader,
) {

    private val log = LoggerFactory.getLogger(KubernetesDeploymentClient::class.java)
    private val objectMapper = ObjectMapper()

    fun patchImage(contextName: String, namespace: String, kind: KubernetesKind, name: String, image: String) {
        val connection = kubeConfigLoader.resolve(contextName)
        val restClient = buildRestClient(connection)
        val resourcePath = resourcePath(kind, namespace, name)

        val current = restClient.get()
            .uri(resourcePath)
            .retrieve()
            .body(String::class.java)
            ?: throw KubernetesDeploymentException("Empty response fetching $resourcePath")

        val containers = objectMapper.readTree(current).path("spec").path("template").path("spec").path("containers")
        val containerCount = if (containers.isArray) containers.size() else 0
        if (containerCount != 1) {
            throw KubernetesDeploymentException(
                "Expected exactly 1 container in $resourcePath, found $containerCount — cannot determine which to patch"
            )
        }

        val patch = objectMapper.createArrayNode().add(
            objectMapper.createObjectNode()
                .put("op", "replace")
                .put("path", "/spec/template/spec/containers/0/image")
                .put("value", image)
        )

        restClient.patch()
            .uri(resourcePath)
            .contentType(MediaType.valueOf("application/json-patch+json"))
            .body(objectMapper.writeValueAsString(patch))
            .retrieve()
            .toBodilessEntity()

        log.info("Patched {} '{}' in namespace '{}' (context '{}') to image '{}'", kind, name, namespace, contextName, image)
    }

    // Whether the rollout to `image` has actually completed: every pod matching the
    // resource's selector exists (replica count reached), runs the target image, and its
    // matching container is ready with no restarts. Checked by container name (not
    // index) since a pod can end up with more containers than the manifest declares (a
    // mutating admission webhook / sidecar injector), even though patchImage's
    // single-container requirement still holds for the manifest itself.
    fun checkRollout(contextName: String, namespace: String, kind: KubernetesKind, name: String, image: String): RolloutStatus {
        val connection = kubeConfigLoader.resolve(contextName)
        val restClient = buildRestClient(connection)
        val resourcePath = resourcePath(kind, namespace, name)

        val current = restClient.get()
            .uri(resourcePath)
            .retrieve()
            .body(String::class.java)
            ?: throw KubernetesDeploymentException("Empty response fetching $resourcePath")

        val spec = objectMapper.readTree(current).path("spec")
        val expectedReplicas = if (spec.path("replicas").isMissingNode) 1 else spec.path("replicas").asInt(1)
        val containerName = spec.path("template").path("spec").path("containers").path(0).path("name").asString(null)
            ?: return RolloutStatus(false, "manifest has no container to check")
        val matchLabels = spec.path("selector").path("matchLabels")
        val selector = matchLabels.properties().joinToString(",") { (key, value) -> "$key=${value.asString()}" }
        if (selector.isBlank()) return RolloutStatus(false, "no label selector found")

        val podsJson = restClient.get()
            .uri { it.path("/api/v1/namespaces/$namespace/pods").queryParam("labelSelector", selector).build() }
            .retrieve()
            .body(String::class.java)
            ?: throw KubernetesDeploymentException("Empty response listing pods for $resourcePath")

        val pods = objectMapper.readTree(podsJson).path("items")
        val reasons = mutableListOf<String>()

        val podCount = if (pods.isArray) pods.size() else 0
        if (podCount != expectedReplicas) reasons += "$podCount/$expectedReplicas pods present"

        var staleImage = 0
        var notReady = 0
        var restarting = 0
        pods.forEach { pod ->
            val podImage = pod.path("spec").path("containers")
                .firstOrNull { it.path("name").asString() == containerName }
                ?.path("image")?.asString()
            if (podImage != image) staleImage++

            val status = pod.path("status").path("containerStatuses")
                .firstOrNull { it.path("name").asString() == containerName }
            if (status == null || !status.path("ready").asBoolean(false)) notReady++
            if ((status?.path("restartCount")?.asInt(0) ?: 0) > 0) restarting++
        }
        if (staleImage > 0) reasons += "$staleImage pod(s) still running the previous image"
        if (notReady > 0) reasons += "$notReady pod(s) not ready"
        if (restarting > 0) reasons += "$restarting pod(s) restarting (restart count > 0)"

        return if (reasons.isEmpty()) RolloutStatus(true, "rollout complete") else RolloutStatus(false, reasons.joinToString("; "))
    }

    private fun resourcePath(kind: KubernetesKind, namespace: String, name: String): String {
        val resource = when (kind) {
            KubernetesKind.DEPLOYMENT -> "deployments"
            KubernetesKind.STATEFUL_SET -> "statefulsets"
        }
        return "/apis/apps/v1/namespaces/$namespace/$resource/$name"
    }

    private fun buildRestClient(connection: ClusterConnection): RestClient {
        val httpClient = HttpClient.newBuilder()
            .sslContext(KubernetesTls.buildSslContext(connection))
            .connectTimeout(Duration.ofSeconds(10))
            .build()

        val builder = RestClient.builder()
            .baseUrl(connection.serverUrl)
            .requestFactory(JdkClientHttpRequestFactory(httpClient))
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
        connection.bearerToken?.let { builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $it") }
        return builder.build()
    }
}
