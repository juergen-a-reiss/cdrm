// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

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
