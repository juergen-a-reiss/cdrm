// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.release

import dev.juergenreiss.cdrm.kubernetes.KubernetesDeploymentClient
import dev.juergenreiss.cdrm.stage.Stage
import dev.juergenreiss.cdrm.workload.Workload
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

// Shared by ReleaseService (best-effort attempt at promotion time for IMMEDIATE stages)
// and DeploymentSchedulerJob (the sole retry path, on every tick, for anything still
// pending). Never throws — failures are logged, counted, and reported as a short
// human-readable reason (null on success) so the caller can leave the release-history
// row's deployedAt null and surface why to the user while the job retries later.
@Component
class DeploymentExecutor(
    private val kubernetesDeploymentClient: KubernetesDeploymentClient,
    private val meterRegistry: MeterRegistry,
) {

    private val log = LoggerFactory.getLogger(DeploymentExecutor::class.java)

    fun attemptDeploy(workload: Workload, stage: Stage, image: String): String? {
        if (!workload.kubernetes) return null

        val context = stage.kubernetesContext
        val namespace = workload.kubernetesNameSpace
        val kind = workload.kubernetesKind
        if (context.isNullOrBlank() || namespace.isNullOrBlank() || kind == null) {
            log.error(
                "Cannot deploy workload {} at stage {}: missing Kubernetes configuration (context={}, namespace={}, kind={})",
                workload.id, stage.id, context, namespace, kind,
            )
            incrementDeployFailedMetric(workload, stage)
            return "Kubernetes configuration missing for this stage"
        }
        val effectiveNamespace = (stage.namespacePrefix ?: "") + namespace

        return try {
            kubernetesDeploymentClient.patchImage(context, effectiveNamespace, kind, workload.name, image)
            null
        } catch (e: Exception) {
            log.error("Failed to deploy workload {} at stage {}: {}", workload.id, stage.id, e.message, e)
            incrementDeployFailedMetric(workload, stage)
            "cluster not reachable"
        }
    }

    private fun incrementDeployFailedMetric(workload: Workload, stage: Stage) {
        meterRegistry.counter("cdrm.deploy.failed", "workload", workload.name, "stage", stage.name).increment()
    }
}
