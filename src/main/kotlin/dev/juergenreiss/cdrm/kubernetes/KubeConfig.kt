// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.kubernetes

class KubernetesConfigException(message: String) : RuntimeException(message)

class KubernetesDeploymentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class ClusterConnection(
    val serverUrl: String,
    val caCertificatePem: String?,
    val insecureSkipTlsVerify: Boolean,
    val bearerToken: String?,
    val clientCertificatePem: String?,
    val clientKeyPem: String?,
)
