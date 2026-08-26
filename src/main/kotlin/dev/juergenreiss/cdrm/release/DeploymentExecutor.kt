// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.release

import dev.juergenreiss.cdrm.kubernetes.KubernetesDeploymentClient
import dev.juergenreiss.cdrm.stage.Stage
import dev.juergenreiss.cdrm.workload.Workload
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

// Shared by ReleaseService (best-effort attempt at promotion time for IMMEDIATE stages)
// and DeploymentSchedulerJob (the sole retry path, on every tick, for anything still
// pending). Never throws — failures are logged and reported as `false` so the caller can
// leave the release-history row's deployedAt null for the job to retry later.
@Component
class DeploymentExecutor(
    private val kubernetesDeploymentClient: KubernetesDeploymentClient,
) {

    private val log = LoggerFactory.getLogger(DeploymentExecutor::class.java)

    fun attemptDeploy(workload: Workload, stage: Stage, image: String): Boolean {
        if (!workload.kubernetes) return true

        val context = stage.kubernetesContext
        val namespace = workload.kubernetesNameSpace
        val kind = workload.kubernetesKind
        if (context.isNullOrBlank() || namespace.isNullOrBlank() || kind == null) {
            log.error(
                "Cannot deploy workload {} at stage {}: missing Kubernetes configuration (context={}, namespace={}, kind={})",
                workload.id, stage.id, context, namespace, kind,
            )
            return false
        }
        val effectiveNamespace = (stage.namespacePrefix ?: "") + namespace

        return try {
            kubernetesDeploymentClient.patchImage(context, effectiveNamespace, kind, workload.name, image)
            true
        } catch (e: Exception) {
            log.error("Failed to deploy workload {} at stage {}: {}", workload.id, stage.id, e.message, e)
            false
        }
    }
}
