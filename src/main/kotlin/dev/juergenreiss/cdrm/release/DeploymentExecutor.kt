// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.release

import dev.juergenreiss.cdrm.gitops.GitCommitClient
import dev.juergenreiss.cdrm.gitops.GitCommitResult
import dev.juergenreiss.cdrm.gitops.GitOpsResolution
import dev.juergenreiss.cdrm.gitops.GitOpsResolver
import dev.juergenreiss.cdrm.kubernetes.KubernetesDeploymentClient
import dev.juergenreiss.cdrm.product.Product
import dev.juergenreiss.cdrm.stage.Stage
import dev.juergenreiss.cdrm.workload.Workload
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

// GitLockBusy only ever comes from the GitOps path (GitCommitClient.commit) —
// another git operation (anywhere in the app, any instance) currently holds the single
// git_lock row. Not a failure of this push: ReleaseService maps it to HTTP 429 for a
// synchronous (IMMEDIATE-policy) attempt, and DeploymentSchedulerJob just retries next
// tick without spending any of the row's 5-attempt GitOps retry budget on it.
sealed interface DeployAttemptResult {
    data object Success : DeployAttemptResult
    data class Failed(val reason: String) : DeployAttemptResult
    data object GitLockBusy : DeployAttemptResult
}

// Shared by ReleaseService (best-effort attempt at promotion time for IMMEDIATE stages)
// and DeploymentSchedulerJob (the sole retry path, on every tick, for anything still
// pending). Never throws — failures are logged, counted, and reported as a short
// human-readable reason so the caller can leave the release-history row's deployedAt
// null and surface why to the user while the job retries later.
@Component
class DeploymentExecutor(
    private val kubernetesDeploymentClient: KubernetesDeploymentClient,
    private val gitOpsResolver: GitOpsResolver,
    private val gitCommitClient: GitCommitClient,
    private val meterRegistry: MeterRegistry,
) {

    private val log = LoggerFactory.getLogger(DeploymentExecutor::class.java)

    fun attemptDeploy(workload: Workload, stage: Stage, product: Product, image: String): DeployAttemptResult {
        if (!workload.kubernetes) return DeployAttemptResult.Success

        when (val resolution = gitOpsResolver.resolve(workload, stage, product, image)) {
            is GitOpsResolution.TemplateFailed -> {
                log.error("GitOps template failed for workload {} at stage {}: {}", workload.id, stage.id, resolution.reason)
                incrementDeployFailedMetric(workload, stage)
                return DeployAttemptResult.Failed(resolution.reason)
            }
            is GitOpsResolution.Resolved -> {
                val commitMessage = "cdrm: deploy $image for workload '${workload.name}' at stage '${stage.name}'"
                return when (val result = gitCommitClient.commit(resolution.target, commitMessage)) {
                    is GitCommitResult.Success -> DeployAttemptResult.Success
                    is GitCommitResult.Failed -> {
                        log.error("Failed to commit GitOps deploy for workload {} at stage {}: {}", workload.id, stage.id, result.reason)
                        incrementDeployFailedMetric(workload, stage)
                        DeployAttemptResult.Failed(result.reason)
                    }
                    GitCommitResult.LockBusy -> DeployAttemptResult.GitLockBusy
                }
            }
            GitOpsResolution.NotManaged -> Unit
        }

        val context = stage.kubernetesContext
        val namespace = workload.kubernetesNameSpace
        val kind = workload.kubernetesKind
        if (context.isNullOrBlank() || namespace.isNullOrBlank() || kind == null) {
            log.error(
                "Cannot deploy workload {} at stage {}: missing Kubernetes configuration (context={}, namespace={}, kind={})",
                workload.id, stage.id, context, namespace, kind,
            )
            incrementDeployFailedMetric(workload, stage)
            return DeployAttemptResult.Failed("Kubernetes configuration missing for this stage")
        }
        val effectiveNamespace = (stage.namespacePrefix ?: "") + namespace

        return try {
            kubernetesDeploymentClient.patchImage(context, effectiveNamespace, kind, workload.name, image)
            DeployAttemptResult.Success
        } catch (e: Exception) {
            log.error("Failed to deploy workload {} at stage {}: {}", workload.id, stage.id, e.message, e)
            incrementDeployFailedMetric(workload, stage)
            DeployAttemptResult.Failed("cluster not reachable")
        }
    }

    private fun incrementDeployFailedMetric(workload: Workload, stage: Stage) {
        meterRegistry.counter("cdrm.deploy.failed", "workload", workload.name, "stage", stage.name).increment()
    }
}
