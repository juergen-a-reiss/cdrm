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
