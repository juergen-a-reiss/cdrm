// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.gitops

import dev.juergenreiss.cdrm.cluster.Cluster
import dev.juergenreiss.cdrm.cluster.ClusterRepository
import dev.juergenreiss.cdrm.cluster.GitOpsNamespaceMode
import dev.juergenreiss.cdrm.cluster.K8sNamespaceGitopsConfig
import dev.juergenreiss.cdrm.product.Product
import dev.juergenreiss.cdrm.stage.Stage
import dev.juergenreiss.cdrm.workload.Workload
import org.springframework.stereotype.Component

// One file edit a GitOps push should make: set yamlKeyPath to value inside filePath, on
// branch — a mode=SIMPLE namespace always produces exactly one of these (the release's
// new image, at the configured file/YAML key); mode=TEMPLATE can return any number,
// spanning multiple files and/or branches, computed by GitOpsTemplateEngine. value is
// always written into the YAML as a string (see YamlPathEditor.setAt) — yamlKeyPath must
// target a string-typed field (an image tag, a label, ...), never one Kubernetes expects
// to be numeric or boolean (e.g. spec.replicas), or the resulting manifest fails to apply.
data class GitOpsEdit(
    val branch: String,
    val filePath: String,
    val yamlKeyPath: String,
    val value: String,
)

// Everything DeploymentExecutor/GitCommitClient need to actually perform a GitOps push:
// which repo, and the edit(s) to make there. All of edits' branches live in this one
// repositoryUrl — a template can spread edits across branches, never across repos (see
// K8sNamespaceGitopsConfig.gitRepo's own doc comment for why).
data class GitOpsTarget(
    val repositoryUrl: String,
    val edits: List<GitOpsEdit>,
)

// The three ways GitOpsResolver.resolve() can conclude: NotManaged, and both DeploymentExecutor
// and the caller of resolve() must treat these very differently — a template failure is
// a deploy *failure* to retry, never silently mistaken for "this namespace isn't
// GitOps-managed at all" (which would fall through to a direct Kubernetes patch, wrong
// for a namespace that's very much supposed to be GitOps-managed).
sealed interface GitOpsResolution {
    data class Resolved(val target: GitOpsTarget) : GitOpsResolution
    data class TemplateFailed(val reason: String) : GitOpsResolution
    data object NotManaged : GitOpsResolution
}

// Where (and how) a workload's deploy to a stage should go through GitOps instead of a
// direct Kubernetes patch — see Cluster.k8sGitOpsConfig (Cluster.kt) for the config this
// reads.
@Component
class GitOpsResolver(
    private val clusterRepository: ClusterRepository,
    private val templateEngine: GitOpsTemplateEngine,
) {

    private data class NamespaceMatch(val cluster: Cluster, val namespaceConfig: K8sNamespaceGitopsConfig, val effectiveNamespace: String)

    // Namespace names are looked up across every cluster rather than restricted to the
    // stage's own linked clusters (stage_cluster is informational/UI-only today — not
    // consulted at deploy time by KubernetesDeploymentClient either) — a namespace name
    // naturally belongs to exactly one cluster in practice.
    private fun findNamespace(workload: Workload, stage: Stage): NamespaceMatch? {
        val namespace = workload.kubernetesNameSpace ?: return null
        val effectiveNamespace = (stage.namespacePrefix ?: "") + namespace
        for (cluster in clusterRepository.findAll()) {
            val config = cluster.k8sGitOpsConfig ?: continue
            if (!config.useGitOps) continue
            val namespaceConfig = config.namespaces[effectiveNamespace] ?: continue
            if (!namespaceConfig.useGitOps) continue
            return NamespaceMatch(cluster, namespaceConfig, effectiveNamespace)
        }
        return null
    }

    // Cheap existence check — never runs a namespace's template script, so it's safe to
    // call just to decide a boolean (e.g. ReleaseHistory.gitOpsManaged) without the cost
    // or risk of the actual resolution below.
    fun isManaged(workload: Workload, stage: Stage): Boolean = findNamespace(workload, stage) != null

    fun resolve(workload: Workload, stage: Stage, product: Product, image: String): GitOpsResolution {
        val (cluster, namespaceConfig, effectiveNamespace) = findNamespace(workload, stage) ?: return GitOpsResolution.NotManaged
        val gitOpsConfig = cluster.k8sGitOpsConfig!!
        val repositoryUrl = namespaceConfig.gitRepo ?: gitOpsConfig.gitRepo

        return when (namespaceConfig.mode) {
            GitOpsNamespaceMode.SIMPLE -> {
                val fileExpression = namespaceConfig.fileExpression
                val yamlExpression = namespaceConfig.yamlExpression
                if (fileExpression.isNullOrBlank() || yamlExpression.isNullOrBlank()) return GitOpsResolution.NotManaged
                val filePath = fileExpression
                    .replace("{namespace}", effectiveNamespace)
                    .replace("{workload}", workload.name)
                val branch = namespaceConfig.gitBranch ?: gitOpsConfig.gitBranch
                GitOpsResolution.Resolved(GitOpsTarget(repositoryUrl, listOf(GitOpsEdit(branch, filePath, yamlExpression, image))))
            }
            GitOpsNamespaceMode.TEMPLATE -> {
                val script = namespaceConfig.templateScript
                if (script.isNullOrBlank()) return GitOpsResolution.NotManaged
                val context = GitOpsTemplateContext(
                    gitRepoName = repositoryUrl,
                    clusterName = cluster.name,
                    namespace = effectiveNamespace,
                    productName = product.name,
                    stageName = stage.name,
                    workloadName = workload.name,
                    releaseBinary = image,
                    targetStage = stage.name,
                )
                when (val result = templateEngine.evaluate(script, context)) {
                    is GitOpsTemplateResult.Success ->
                        if (result.edits.isEmpty()) {
                            GitOpsResolution.TemplateFailed("template script returned no edits")
                        } else {
                            GitOpsResolution.Resolved(GitOpsTarget(repositoryUrl, result.edits))
                        }
                    is GitOpsTemplateResult.Failed -> GitOpsResolution.TemplateFailed(result.reason)
                }
            }
        }
    }
}
