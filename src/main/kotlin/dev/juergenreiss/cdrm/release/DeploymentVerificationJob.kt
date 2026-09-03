// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.release

import dev.juergenreiss.cdrm.kubernetes.KubernetesDeploymentClient
import dev.juergenreiss.cdrm.stage.StageRepository
import dev.juergenreiss.cdrm.workload.Workload
import dev.juergenreiss.cdrm.workload.WorkloadRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

// Confirms a Kubernetes deploy that DeploymentSchedulerJob already got accepted by the API
// server (deployedAt set) actually rolled out: only pods on the new image exist, all ready,
// none restarting. Runs independently of deployment policy — both IMMEDIATE and SCHEDULED
// rows funnel through the same deployedAt/deploymentFinished fields. A row is left pending
// (retried next tick) until either the rollout is confirmed or GRACE_PERIOD elapses, at
// which point it's marked failed with a reason. Never throws — a bad row or an unreachable
// cluster is logged and simply retried (or timed out) like any other not-ready result.
@Component
class DeploymentVerificationJob(
    private val releaseHistoryRepository: ReleaseHistoryRepository,
    private val releaseRepository: ReleaseRepository,
    private val workloadRepository: WorkloadRepository,
    private val stageRepository: StageRepository,
    private val kubernetesDeploymentClient: KubernetesDeploymentClient,
    private val meterRegistry: MeterRegistry,
) {

    private val log = LoggerFactory.getLogger(DeploymentVerificationJob::class.java)

    companion object {
        private val GRACE_PERIOD: Duration = Duration.ofMinutes(5)
    }

    @Scheduled(fixedRate = 30_000)
    @Transactional
    fun verifyPendingDeployments() {
        val pending = releaseHistoryRepository.findAwaitingVerification()
        if (pending.isEmpty()) return

        val now = Instant.now()
        for (entry in pending) {
            val release = releaseRepository.findById(entry.releaseId).orElse(null) ?: continue
            val workload = workloadRepository.findById(release.workloadId).orElse(null) ?: continue

            if (!workload.kubernetes) {
                entry.deploymentFinished = entry.deployedAt
                releaseHistoryRepository.save(entry)
                continue
            }

            val stage = stageRepository.findById(entry.stageId).orElse(null) ?: continue
            verifyEntry(entry, workload, stage.kubernetesContext, stage.namespacePrefix, now)
        }
    }

    private fun verifyEntry(entry: ReleaseHistory, workload: Workload, context: String?, namespacePrefix: String?, now: Instant) {
        val namespace = workload.kubernetesNameSpace
        val kind = workload.kubernetesKind
        if (context.isNullOrBlank() || namespace.isNullOrBlank() || kind == null) {
            failIfOverdue(entry, now, "Kubernetes configuration missing for this stage")
            return
        }
        val effectiveNamespace = (namespacePrefix ?: "") + namespace

        val status = try {
            kubernetesDeploymentClient.checkRollout(context, effectiveNamespace, kind, workload.name, entry.image)
        } catch (e: Exception) {
            log.error("Failed to check rollout for workload {} at stage {}: {}", workload.id, entry.stageId, e.message, e)
            failIfOverdue(entry, now, "cluster not reachable")
            return
        }

        if (status.ready) {
            entry.deploymentFinished = now
            entry.deploymentFailed = false
            entry.deployError = null
            releaseHistoryRepository.save(entry)
            meterRegistry.counter("cdrm.deploy.verification_succeeded", "workload", workload.name, "stage", entry.stageName).increment()
            log.info("Verified rollout for release {} at stage {}", entry.releaseId, entry.stageId)
        } else {
            failIfOverdue(entry, now, status.detail)
        }
    }

    private fun failIfOverdue(entry: ReleaseHistory, now: Instant, reason: String) {
        // findAwaitingVerification() only ever returns rows with deployedAt already set.
        if (Duration.between(entry.deployedAt!!, now) < GRACE_PERIOD) return
        entry.deploymentFinished = now
        entry.deploymentFailed = true
        entry.deployError = reason
        releaseHistoryRepository.save(entry)
        meterRegistry.counter("cdrm.deploy.verification_failed", "workload", entry.workloadName, "stage", entry.stageName).increment()
        log.warn("Rollout verification failed for release {} at stage {}: {}", entry.releaseId, entry.stageId, reason)
    }
}
