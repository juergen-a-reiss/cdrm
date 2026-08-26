// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.release

import dev.juergenreiss.cdrm.product.ProductStageRepository
import dev.juergenreiss.cdrm.stage.DeploymentPolicy
import dev.juergenreiss.cdrm.stage.StageRepository
import dev.juergenreiss.cdrm.workload.WorkloadRepository
import org.slf4j.LoggerFactory
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
) {

    private val log = LoggerFactory.getLogger(DeploymentSchedulerJob::class.java)

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

            if (deploymentExecutor.attemptDeploy(workload, stage, release.binaryUrl)) {
                entry.deployedAt = now
                releaseHistoryRepository.save(entry)
                log.info("Deployed release {} at stage {}", entry.releaseId, entry.stageId)
            } else {
                log.warn("Deployment attempt failed for release {} at stage {} — will retry next tick", entry.releaseId, entry.stageId)
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
