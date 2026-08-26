// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.kubernetes

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.Base64

// Resolves a named context from the server's kubeconfig file into connection details.
// No secrets live in Postgres — devops manages the kubeconfig file (path via KUBECONFIG,
// default ~/.kube/config) and its credential rotation entirely out of band. Only static
// bearer-token and client-certificate users are supported; exec-credential plugins
// (aws-iam-authenticator, gke-gcloud-auth-plugin, ...) are not.
@Component
class KubeConfigLoader(
    @Value("\${KUBECONFIG:\${user.home}/.kube/config}") private val kubeconfigPath: String,
) {

    fun resolve(contextName: String): ClusterConnection {
        val file = kubeconfigFile()
        val root = file.inputStream().use { Yaml().load<Map<*, *>>(it) }
            ?: throw KubernetesConfigException("Kubeconfig file '$file' is empty or invalid")

        val contextEntry = namedList(root, "contexts").firstOrNull { name(it) == contextName }
            ?: throw KubernetesConfigException("Context '$contextName' not found in kubeconfig '$file'")
        val context = subMap(contextEntry, "context")
        val clusterName = context["cluster"] as? String
            ?: throw KubernetesConfigException("Context '$contextName' has no cluster")
        val userName = context["user"] as? String
            ?: throw KubernetesConfigException("Context '$contextName' has no user")

        val clusterEntry = namedList(root, "clusters").firstOrNull { name(it) == clusterName }
            ?: throw KubernetesConfigException("Cluster '$clusterName' not found in kubeconfig '$file'")
        val cluster = subMap(clusterEntry, "cluster")
        val server = cluster["server"] as? String
            ?: throw KubernetesConfigException("Cluster '$clusterName' has no server URL")
        val caCertificatePem = readCertMaterial(cluster, "certificate-authority-data", "certificate-authority", file.parentFile)
        val insecure = cluster["insecure-skip-tls-verify"] as? Boolean ?: false

        val userEntry = namedList(root, "users").firstOrNull { name(it) == userName }
            ?: throw KubernetesConfigException("User '$userName' not found in kubeconfig '$file'")
        val user = subMap(userEntry, "user")
        val token = (user["token"] as? String)
            ?: (user["tokenFile"] as? String ?: user["token-file"] as? String)?.let { File(it).readText().trim() }
        val clientCertPem = readCertMaterial(user, "client-certificate-data", "client-certificate", file.parentFile)
        val clientKeyPem = readCertMaterial(user, "client-key-data", "client-key", file.parentFile)

        if (token.isNullOrBlank() && (clientCertPem == null || clientKeyPem == null)) {
            throw KubernetesConfigException(
                "User '$userName' has no supported credentials — only static tokens and client-certificate/key pairs are supported"
            )
        }

        return ClusterConnection(
            serverUrl = server,
            caCertificatePem = caCertificatePem,
            insecureSkipTlsVerify = insecure,
            bearerToken = token,
            clientCertificatePem = clientCertPem,
            clientKeyPem = clientKeyPem,
        )
    }

    private fun kubeconfigFile(): File {
        val file = File(kubeconfigPath)
        if (!file.isFile) {
            throw KubernetesConfigException(
                "Kubeconfig file not found at '$kubeconfigPath' (set KUBECONFIG or place a file at ~/.kube/config)"
            )
        }
        return file
    }

    private fun readCertMaterial(map: Map<*, *>, dataKey: String, fileKey: String, baseDir: File?): String? {
        (map[dataKey] as? String)?.let { return String(Base64.getDecoder().decode(it)) }
        (map[fileKey] as? String)?.let { path ->
            val file = File(path).let { if (it.isAbsolute) it else File(baseDir, path) }
            return file.readText()
        }
        return null
    }

    private fun namedList(root: Map<*, *>, key: String): List<Map<*, *>> =
        (root[key] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()

    private fun name(entry: Map<*, *>): String? = entry["name"] as? String

    @Suppress("UNCHECKED_CAST")
    private fun subMap(entry: Map<*, *>, key: String): Map<String, Any?> =
        (entry[key] as? Map<String, Any?>) ?: emptyMap()
}
