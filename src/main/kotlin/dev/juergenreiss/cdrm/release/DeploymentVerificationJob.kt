// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.release

import dev.juergenreiss.cdrm.kubernetes.KubernetesDeploymentClient
import dev.juergenreiss.cdrm.notification.ReleaseHistoryNotificationKind
import dev.juergenreiss.cdrm.notification.ReleaseHistoryRecordedEvent
import dev.juergenreiss.cdrm.stage.StageRepository
import dev.juergenreiss.cdrm.workload.Workload
import dev.juergenreiss.cdrm.workload.WorkloadRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

// Confirms a Kubernetes deploy that DeploymentSchedulerJob already got accepted by the API
// server (deployedAt set) actually rolled out: only pods on the new image exist, all ready,
// none restarting. Runs independently of deployment policy — both IMMEDIATE and SCHEDULED
// rows funnel through the same deployedAt/deploymentFinished fields. Never throws — a bad
// row or an unreachable cluster is logged and simply retried like any other not-ready
// result.
//
// GRACE_PERIOD only ever bounds the time between the new image first being observed
// running (rolloutStartedAt) and the rollout actually finishing — not the time since
// deployedAt. Until the new image is observed on at least one pod, this just keeps
// retrying forever: that covers a cluster/API-server that's temporarily unreachable and a
// GitOps deploy that hasn't been synced by ArgoCD (or a human) yet, neither of which cdrm
// itself can do anything about, so neither is ever reported as failed on its own. Only a
// rollout that has visibly started and then stalls or starts restarting is timed out.
@Component
class DeploymentVerificationJob(
    private val releaseHistoryRepository: ReleaseHistoryRepository,
    private val releaseRepository: ReleaseRepository,
    private val workloadRepository: WorkloadRepository,
    private val stageRepository: StageRepository,
    private val kubernetesDeploymentClient: KubernetesDeploymentClient,
    private val meterRegistry: MeterRegistry,
    private val eventPublisher: ApplicationEventPublisher,
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
                eventPublisher.publishEvent(ReleaseHistoryRecordedEvent(entry, ReleaseHistoryNotificationKind.DEPLOYED))
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
            // Missing stage/workload Kubernetes config, not a cluster/sync-timing issue —
            // nothing external is ever going to fix this on its own, so (unlike the cases
            // below) this still times out on the original deployedAt-anchored clock.
            if (Duration.between(entry.deployedAt!!, now) < GRACE_PERIOD) return
            failWith(entry, now, "Kubernetes configuration missing for this stage")
            return
        }
        val effectiveNamespace = (namespacePrefix ?: "") + namespace

        val status = try {
            kubernetesDeploymentClient.checkRollout(context, effectiveNamespace, kind, workload.name, entry.image)
        } catch (e: Exception) {
            log.info("Cluster/API unreachable checking rollout for workload {} at stage {}, will keep waiting: {}", workload.id, entry.stageId, e.message)
            return
        }

        when {
            status.ready -> {
                entry.deploymentFinished = now
                entry.deploymentFailed = false
                entry.deployError = null
                releaseHistoryRepository.save(entry)
                meterRegistry.counter("cdrm.deploy.verification_succeeded", "workload", workload.name, "stage", entry.stageName).increment()
                log.info("Verified rollout for release {} at stage {}", entry.releaseId, entry.stageId)
                eventPublisher.publishEvent(ReleaseHistoryRecordedEvent(entry, ReleaseHistoryNotificationKind.DEPLOYED))
            }
            !status.imageObserved -> {
                // Still on the previous image (or the resource/pods don't exist yet) —
                // indistinguishable from "ArgoCD/a human hasn't synced this yet", so just
                // keep waiting; never times out on its own.
                log.debug("Workload {} at stage {} not yet observed on the new image, will keep waiting: {}", workload.id, entry.stageId, status.detail)
            }
            else -> {
                // The rollout has visibly started — this is the one case the grace period
                // actually applies to.
                if (entry.rolloutStartedAt == null) {
                    entry.rolloutStartedAt = now
                    releaseHistoryRepository.save(entry)
                    log.debug("Rollout started for release {} at stage {}, grace period clock started", entry.releaseId, entry.stageId)
                    return
                }
                if (Duration.between(entry.rolloutStartedAt!!, now) < GRACE_PERIOD) return
                failWith(entry, now, status.detail)
            }
        }
    }

    private fun failWith(entry: ReleaseHistory, now: Instant, reason: String) {
        entry.deploymentFinished = now
        entry.deploymentFailed = true
        entry.deployError = reason
        releaseHistoryRepository.save(entry)
        meterRegistry.counter("cdrm.deploy.verification_failed", "workload", entry.workloadName, "stage", entry.stageName).increment()
        log.warn("Rollout verification failed for release {} at stage {}: {}", entry.releaseId, entry.stageId, reason)
        eventPublisher.publishEvent(ReleaseHistoryRecordedEvent(entry, ReleaseHistoryNotificationKind.DEPLOY_FAILED))
    }
}
