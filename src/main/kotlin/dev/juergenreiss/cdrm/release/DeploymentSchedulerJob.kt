// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.release

import dev.juergenreiss.cdrm.notification.ReleaseHistoryNotificationKind
import dev.juergenreiss.cdrm.notification.ReleaseHistoryRecordedEvent
import dev.juergenreiss.cdrm.product.ProductStageRepository
import dev.juergenreiss.cdrm.stage.DeploymentPolicy
import dev.juergenreiss.cdrm.stage.StageRepository
import dev.juergenreiss.cdrm.workload.WorkloadRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneId

// Polls for release-history rows that haven't been deployed yet and, once due, actually
// performs the deployment (patches the workload's Kubernetes image, or no-ops for
// non-Kubernetes workloads). IMMEDIATE-policy rows are due immediately — this is also
// the retry path for an IMMEDIATE deploy attempted (and failed) synchronously at
// promotion time. SCHEDULED-policy rows become due once their product's configured cron
// next-trigger time (computed from the row's creation time) has passed. Runs every
// minute; pending rows are selected with a pessimistic write lock so a second app
// instance (or a slow-running overlapping tick) can't process the same row twice.
@Component
class DeploymentSchedulerJob(
    private val releaseHistoryRepository: ReleaseHistoryRepository,
    private val releaseRepository: ReleaseRepository,
    private val workloadRepository: WorkloadRepository,
    private val stageRepository: StageRepository,
    private val productStageRepository: ProductStageRepository,
    private val deploymentExecutor: DeploymentExecutor,
    private val eventPublisher: ApplicationEventPublisher,
) {

    private val log = LoggerFactory.getLogger(DeploymentSchedulerJob::class.java)

    companion object {
        // Consecutive failed GitOps push attempts before giving up on the row (see
        // ReleaseHistory.gitopsRetryCount) — repeated git failures need a human to look
        // at the repo/credentials, not an indefinite retry loop. Only ever applies to
        // GitOps-managed rows; a direct Kubernetes patch failure keeps retrying forever,
        // same as before this existed.
        private const val MAX_GITOPS_RETRIES = 5
    }

    @Scheduled(fixedRate = 60_000)
    @Transactional
    fun processPendingDeployments() {
        val pending = releaseHistoryRepository.findPendingForUpdate()
        if (pending.isEmpty()) return

        val now = Instant.now()
        for (entry in pending) {
            val release = releaseRepository.findById(entry.releaseId).orElse(null) ?: continue
            val workload = workloadRepository.findById(release.workloadId).orElse(null) ?: continue
            val stage = stageRepository.findById(entry.stageId).orElse(null) ?: continue

            if (!isDue(entry, workload.productId, stage.deploymentPolicy, now)) continue

            when (val result = deploymentExecutor.attemptDeploy(workload, stage, release.image)) {
                is DeployAttemptResult.Success -> {
                    entry.deployedAt = now
                    entry.deployError = null
                    entry.gitopsError = null
                    // Nothing to verify for a non-Kubernetes workload — DeploymentVerificationJob
                    // only ever looks at Kubernetes-backed deploys.
                    if (!workload.kubernetes) entry.deploymentFinished = now
                    releaseHistoryRepository.save(entry)
                    log.info("Deployed release {} at stage {}", entry.releaseId, entry.stageId)
                    // Only non-Kubernetes workloads are actually terminal here — a
                    // Kubernetes deploy still needs DeploymentVerificationJob to confirm
                    // the rollout before this is the "final go".
                    if (entry.deploymentFinished != null) {
                        eventPublisher.publishEvent(ReleaseHistoryRecordedEvent(entry, ReleaseHistoryNotificationKind.DEPLOYED))
                    } else if (entry.gitOpsManaged) {
                        // Not terminal yet, but gitOpsStatus() just became PUSH_SUCCEEDED —
                        // its own stable, user-visible milestone. Without this, the UI
                        // wouldn't learn about a successful GitOps push until the rollout
                        // is later verified, which can lag well behind the commit itself.
                        eventPublisher.publishEvent(ReleaseHistoryRecordedEvent(entry, ReleaseHistoryNotificationKind.GITOPS_PUSHED))
                    }
                }
                is DeployAttemptResult.Failed -> {
                    if (entry.gitOpsManaged) {
                        entry.gitopsRetryCount += 1
                        entry.gitopsError = result.reason
                        if (entry.gitopsRetryCount >= MAX_GITOPS_RETRIES) {
                            entry.deploymentFailed = true
                            entry.deploymentFinished = now
                        }
                    } else {
                        entry.deployError = result.reason
                    }
                    // save() must not be the last statement of this branch — Kotlin
                    // inserts an implicit non-null assertion on a when-branch's tail
                    // expression, and JpaRepository.save's Java-generic return type
                    // trips it (harmless against a real save, but NPEs against
                    // Mockito's default null answer in tests) — keep a log call last.
                    releaseHistoryRepository.save(entry)
                    when {
                        !entry.gitOpsManaged -> log.warn(
                            "Deployment attempt failed for release {} at stage {} — will retry next tick", entry.releaseId, entry.stageId,
                        )
                        entry.gitopsRetryCount >= MAX_GITOPS_RETRIES -> log.error(
                            "GitOps push permanently failed for release {} at stage {} after {} attempts: {}",
                            entry.releaseId, entry.stageId, entry.gitopsRetryCount, result.reason,
                        )
                        else -> log.warn(
                            "GitOps push attempt {}/{} failed for release {} at stage {} — will retry next tick",
                            entry.gitopsRetryCount, MAX_GITOPS_RETRIES, entry.releaseId, entry.stageId,
                        )
                    }
                    // Only the permanent give-up is terminal — a plain "will retry next
                    // tick" failure isn't the final go/no-go yet, and notifying on every
                    // retry would fire once per minute for as long as it keeps failing.
                    if (entry.deploymentFailed) {
                        eventPublisher.publishEvent(ReleaseHistoryRecordedEvent(entry, ReleaseHistoryNotificationKind.DEPLOY_FAILED))
                    }
                }
                DeployAttemptResult.GitLockBusy -> {
                    // Contention with another git operation, not a failure of this push —
                    // skip silently, retry next tick, don't spend any retry budget on it.
                    log.debug("GitOps lock busy for release {} at stage {}, will retry next tick", entry.releaseId, entry.stageId)
                }
            }
        }
    }

    private fun isDue(entry: ReleaseHistory, productId: java.util.UUID, policy: DeploymentPolicy, now: Instant): Boolean {
        if (policy == DeploymentPolicy.IMMEDIATE) return true

        val productStage = productStageRepository.findByProductIdAndStageId(productId, entry.stageId) ?: return false
        val cron = try {
            CronExpression.parse(productStage.deploymentCron)
        } catch (e: IllegalArgumentException) {
            log.warn("Invalid cron '{}' for product {} stage {} — skipping", productStage.deploymentCron, productId, entry.stageId)
            return false
        }
        val nextTrigger = cron.next(entry.createdAt!!.atZone(ZoneId.systemDefault()))?.toInstant() ?: return false
        return !now.isBefore(nextTrigger)
    }
}
