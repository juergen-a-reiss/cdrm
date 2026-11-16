// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

package dev.juergenreiss.cdrm.cluster

import java.net.URL
import java.time.Instant
import java.util.*

data class ClusterRequests(
    val name: String,
    val description: String?,
    val clusterType: ClusterType,
    val url: URL,
    val k8sNamespaces: String? = null,
    val k8sGitOpsConfig: K8sGitopsConfig? = null,
)

data class ClusterResponse (
    val id: UUID,
    val name: String,
    val description: String?,
    val clusterType: ClusterType,
    val url: URL,
    val k8sNamespaces: String? = null,
    val k8sGitOpsConfig: K8sGitopsConfig? = null,
    val createdAt: Instant,
    val modifiedAt: Instant,
    val createdBy: UUID,
    val modifiedBy: UUID,
)
