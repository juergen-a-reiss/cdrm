// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.gitops

import dev.juergenreiss.cdrm.cluster.ClusterRepository
import dev.juergenreiss.cdrm.stage.Stage
import dev.juergenreiss.cdrm.workload.Workload
import org.springframework.stereotype.Component

// Where a GitOps-managed deploy should land: which file in which repo/branch, and the
// YAML key path within it to set to the new image. Returned by GitOpsResolver.resolve()
// when the effective namespace is GitOps-managed; DeploymentExecutor commits there via
// GitCommitClient instead of patching Kubernetes directly.
data class GitOpsTarget(
    val repositoryUrl: String,
    val branch: String,
    val filePath: String,
    val yamlKeyPath: String,
)

// Whether (and where) a workload's deploy to a stage should go through GitOps instead of
// a direct Kubernetes patch — see Cluster.k8sGitOpsConfig (Cluster.kt) for the config
// this reads.
@Component
class GitOpsResolver(private val clusterRepository: ClusterRepository) {

    // Namespace names are looked up across every cluster rather than restricted to the
    // stage's own linked clusters (stage_cluster is informational/UI-only today — not
    // consulted at deploy time by KubernetesDeploymentClient either) — a namespace name
    // naturally belongs to exactly one cluster in practice.
    fun resolve(workload: Workload, stage: Stage): GitOpsTarget? {
        val namespace = workload.kubernetesNameSpace ?: return null
        val effectiveNamespace = (stage.namespacePrefix ?: "") + namespace
        for (cluster in clusterRepository.findAll()) {
            val config = cluster.k8sGitOpsConfig ?: continue
            if (!config.useGitOps) continue
            val namespaceConfig = config.namespaces[effectiveNamespace] ?: continue
            if (!namespaceConfig.useGitOps) continue
            val fileExpression = namespaceConfig.fileExpression
            val yamlExpression = namespaceConfig.yamlExpression
            if (fileExpression.isNullOrBlank() || yamlExpression.isNullOrBlank()) continue
            val filePath = fileExpression
                .replace("{namespace}", effectiveNamespace)
                .replace("{workload}", workload.name)
            return GitOpsTarget(
                repositoryUrl = config.gitRepo,
                branch = namespaceConfig.gitBranch ?: config.gitBranch,
                filePath = filePath,
                yamlKeyPath = yamlExpression,
            )
        }
        return null
    }
}
