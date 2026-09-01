// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.release

import dev.juergenreiss.cdrm.product.Product
import dev.juergenreiss.cdrm.product.ProductRepository
import dev.juergenreiss.cdrm.product.ProductStageRepository
import dev.juergenreiss.cdrm.stage.DeploymentPolicy
import dev.juergenreiss.cdrm.stage.Stage
import dev.juergenreiss.cdrm.stage.StageRepository
import dev.juergenreiss.cdrm.workload.Workload
import dev.juergenreiss.cdrm.workload.WorkloadRepository
import dev.juergenreiss.cdrm.workload.WorkloadStageRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.domain.AuditorAware
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.net.URISyntaxException
import java.time.Instant
import java.time.ZoneId
import java.util.*

@Service
@Transactional(readOnly = true)
class ReleaseService(
    private val repository: ReleaseRepository,
    private val releaseHistoryRepository: ReleaseHistoryRepository,
    private val stageRepository: StageRepository,
    private val workloadRepository: WorkloadRepository,
    private val workloadStageRepository: WorkloadStageRepository,
    private val productRepository: ProductRepository,
    private val productStageRepository: ProductStageRepository,
    private val deploymentExecutor: DeploymentExecutor,
    private val currentUser: AuditorAware<UUID>,
    private val meterRegistry: MeterRegistry,
) {

    private val log = LoggerFactory.getLogger(ReleaseService::class.java)

    fun findAll(): List<ReleaseResponse> =
        repository.findAll(Sort.by("binaryUrl")).map { it.toResponse() }

    fun findById(id: UUID): ReleaseResponse =
        repository.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }.toResponse()

    fun history(id: UUID): List<ReleaseHistoryEntry> {
        if (!repository.existsById(id)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val entries = releaseHistoryRepository.findByReleaseIdOrderByCreatedAtDesc(id)
        // Only needed for the still-live stage's deploymentPolicy/order — name/id on the
        // entry itself are read straight off the row, not joined.
        val stagesById = stageRepository.findAllById(entries.map { it.stageId }.toSet()).associateBy { it.id }
        val cronByProductAndStage = productStagesFor(entries)
        return entries.map { entry ->
            val stage = stagesById[entry.stageId]
            ReleaseHistoryEntry(
                id = entry.id!!,
                binaryUrl = entry.binaryUrl,
                action = entry.action,
                stage = ReleaseStageInfo(id = entry.stageId, name = entry.stageName, order = stage?.order ?: 0),
                timestamp = entry.createdAt!!,
                deployedAt = entry.deployedAt,
                scheduledAt = scheduledDeploymentFor(entry, stage, cronByProductAndStage[entry.productId to entry.stageId]),
                createdBy = entry.createdBy,
            )
        }
    }

    // Every release-history entry across every release, for the history dashboard's chart
    // and table. Product/workload/stage name and id are read straight off each row (a
    // snapshot taken when it was recorded) rather than joined live, so this stays correct
    // even for entries whose product/workload/stage has since been deleted. The stage and
    // product-stage-cron lookups below are only for the still-live stage's
    // deploymentPolicy/order and scheduledAt, batched rather than done per entry.
    fun historyOverview(): List<ReleaseHistoryOverviewEntry> {
        val entries = releaseHistoryRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
        val stagesById = stageRepository.findAllById(entries.map { it.stageId }.toSet()).associateBy { it.id }
        val cronByProductAndStage = productStagesFor(entries)

        return entries.map { entry ->
            val stage = stagesById[entry.stageId]
            ReleaseHistoryOverviewEntry(
                id = entry.id!!,
                releaseId = entry.releaseId,
                binaryUrl = entry.binaryUrl,
                action = entry.action,
                productId = entry.productId,
                productName = entry.productName,
                workloadId = entry.workloadId,
                workloadName = entry.workloadName,
                stage = ReleaseStageInfo(id = entry.stageId, name = entry.stageName, order = stage?.order ?: 0),
                timestamp = entry.createdAt!!,
                deployedAt = entry.deployedAt,
                scheduledAt = scheduledDeploymentFor(entry, stage, cronByProductAndStage[entry.productId to entry.stageId]),
                createdBy = entry.createdBy,
            )
        }
    }

    // Batched by stageId (product_stage rows are few per stage) rather than one
    // findByProductIdAndStageId() call per entry.
    private fun productStagesFor(entries: List<ReleaseHistory>): Map<Pair<UUID, UUID>, String> =
        productStageRepository.findByStageIdIn(entries.map { it.stageId }.toSet())
            .associate { (it.productId to it.stageId) to it.deploymentCron }

    @Transactional
    fun create(request: ReleaseRequest): ReleaseResponse {
        validateBinaryUrl(request.binaryUrl)
        val userId = currentUserId()
        val workload = workloadRepository.findById(request.workloadId)
            .orElseThrow { ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown workload") }
        val initialStage = firstStageFor(request.workloadId)
        requireDeployable(workload, initialStage)
        val product = productFor(workload)
        val saved = repository.save(
            Release(
                binaryUrl = request.binaryUrl,
                description = request.description,
                workloadId = request.workloadId,
                currentStageId = initialStage.id!!,
                createdBy = userId,
                modifiedBy = userId,
            )
        )
        recordHistory(saved, initialStage, workload, product, userId, ReleaseHistoryAction.CREATED)
        incrementReleaseMetric("cdrm.releases.promoted", workload, product, initialStage)
        log.info("Created release {} ('{}') by user {}, starting at stage {}", saved.id, saved.binaryUrl, userId, initialStage.name)
        return saved.toResponse()
    }

    @Transactional
    fun update(id: UUID, request: ReleaseRequest): ReleaseResponse {
        val release = repository.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        if (request.binaryUrl != release.binaryUrl) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "binaryUrl cannot be changed after creation")
        }
        val userId = currentUserId()
        release.description = request.description

        var newStage: Stage? = null
        var newWorkload: Workload? = null
        if (request.workloadId != release.workloadId) {
            val workload = workloadRepository.findById(request.workloadId)
                .orElseThrow { ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown workload") }
            newStage = firstStageFor(request.workloadId)
            requireDeployable(workload, newStage)
            release.currentStageId = newStage.id!!
            release.workloadId = request.workloadId
            newWorkload = workload
        }
        release.modifiedBy = userId
        val saved = repository.save(release)
        if (newStage != null) {
            recordHistory(saved, newStage, newWorkload!!, productFor(newWorkload), userId, ReleaseHistoryAction.CREATED)
        }
        log.info("Updated release {} ('{}') by user {}", saved.id, saved.binaryUrl, saved.modifiedBy)
        return saved.toResponse()
    }

    @Transactional
    fun promote(id: UUID): ReleaseResponse {

        val release = repository.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        val workload = workloadRepository.findById(release.workloadId)
            .orElseThrow { IllegalStateException("Workload ${release.workloadId} not found") }
        val orderedStages = orderedStagesFor(release.workloadId)
        val currentIndex = orderedStages.indexOfFirst { it.id == release.currentStageId }
        if (currentIndex == -1) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Current stage is no longer part of the workload's stages")
        }
        if (currentIndex == orderedStages.lastIndex) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Release is already at the final stage")
        }
        val nextStage = orderedStages[currentIndex + 1]
        requireDeployable(workload, nextStage)
        val product = productFor(workload)
        val userId = currentUserId()
        release.currentStageId = nextStage.id!!
        release.modifiedBy = userId
        val saved = repository.save(release)
        recordHistory(saved, nextStage, workload, product, userId, ReleaseHistoryAction.PROMOTED)
        incrementReleaseMetric("cdrm.releases.promoted", workload, product, nextStage)
        log.info("Promoted release {} ('{}') to stage {} by user {}", saved.id, saved.binaryUrl, nextStage.name, saved.modifiedBy)
        return saved.toResponse()
    }

    // Restores a release that was superseded as head (by a newer release deployed to the
    // same stage) back to head, without moving it — its currentStageId is already the
    // right stage since nothing about its own position ever changed. Only meaningful for
    // a release that currently isn't head: promoting the actual head further along the
    // pipeline is what regular promote() is for.
    @Transactional
    fun rollback(id: UUID): ReleaseResponse {
        val release = repository.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        val workload = workloadRepository.findById(release.workloadId)
            .orElseThrow { IllegalStateException("Workload ${release.workloadId} not found") }
        val stage = stageRepository.findById(release.currentStageId)
            .orElseThrow { IllegalStateException("Stage ${release.currentStageId} not found") }
        if (isHead(release)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Release is already the head at stage '${stage.name}'")
        }
        requireDeployable(workload, stage)
        val product = productFor(workload)
        val userId = currentUserId()
        release.modifiedBy = userId
        val saved = repository.save(release)
        recordHistory(saved, stage, workload, product, userId, ReleaseHistoryAction.ROLLED_BACK)
        incrementReleaseMetric("cdrm.releases.rollback", workload, product, stage)
        log.info("Rolled back release {} ('{}') to head at stage {} by user {}", saved.id, saved.binaryUrl, stage.name, saved.modifiedBy)
        return saved.toResponse()
    }

    // Re-deploys the release's already-recorded binary to its own current stage (only if
    // it's head there — otherwise regular promote()/rollback() are the way to change what's
    // deployed) or to any earlier stage, without moving the release itself. Existing at all
    // stages, the release's currentStageId and head status are unaffected by construction:
    // a lower-stage target was never a headship candidate for this release to begin with,
    // and a same-stage target requires already being head, so the new history entry there
    // just reaffirms the existing head rather than changing it.
    @Transactional
    fun redeploy(id: UUID, request: RedeployRequest): ReleaseResponse {
        val release = repository.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        val workload = workloadRepository.findById(release.workloadId)
            .orElseThrow { IllegalStateException("Workload ${release.workloadId} not found") }
        val orderedStages = orderedStagesFor(release.workloadId)
        val currentIndex = orderedStages.indexOfFirst { it.id == release.currentStageId }
        if (currentIndex == -1) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Current stage is no longer part of the workload's stages")
        }
        val targetIndex = orderedStages.indexOfFirst { it.id == request.stageId }
        if (targetIndex == -1) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown stage id, or stage is not linked to this workload")
        }
        if (targetIndex > currentIndex) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot redeploy to a stage later than the release's current stage")
        }
        val targetStage = orderedStages[targetIndex]
        if (targetIndex == currentIndex && !isHead(release)) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Can only redeploy to the current stage if this release is the head release there",
            )
        }
        requireDeployable(workload, targetStage)
        val product = productFor(workload)
        val userId = currentUserId()
        recordHistory(release, targetStage, workload, product, userId, ReleaseHistoryAction.REDEPLOYED)
        incrementReleaseMetric("cdrm.releases.redeploy", workload, product, targetStage)
        log.info("Redeployed release {} ('{}') to stage {} by user {}", release.id, release.binaryUrl, targetStage.name, userId)
        return release.toResponse()
    }

    @Transactional
    fun delete(id: UUID) {
        if (!repository.existsById(id)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val userId = currentUserId()
        repository.deleteById(id)
        log.info("Deleted release {} by user {}", id, userId)
    }

    private fun requireDeployable(workload: Workload, stage: Stage) {
        if (stage.deploymentPolicy == DeploymentPolicy.SCHEDULED) {
            val cron = productStageRepository.findByProductIdAndStageId(workload.productId, stage.id!!)?.deploymentCron
            if (cron.isNullOrBlank()) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "No deployment time configured for stage '${stage.name}' on this product",
                )
            }
        }
        if (workload.kubernetes && stage.kubernetesContext.isNullOrBlank()) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "No Kubernetes cluster configured for stage '${stage.name}'",
            )
        }
    }

    // For IMMEDIATE stages, attempts the actual deployment right away as a best-effort
    // optimization so the common case (cluster reachable) resolves within the request.
    // The row is always created with deployedAt null first — on failure it's simply left
    // that way, and DeploymentSchedulerJob retries it (and SCHEDULED-stage rows) every tick.
    private fun recordHistory(release: Release, stage: Stage, workload: Workload, product: Product, userId: UUID, action: ReleaseHistoryAction) {
        val entry = releaseHistoryRepository.save(
            ReleaseHistory(
                releaseId = release.id!!,
                workloadId = workload.id!!,
                productId = product.id!!,
                productName = product.name,
                workloadName = workload.name,
                binaryUrl = release.binaryUrl,
                stageId = stage.id!!,
                stageName = stage.name,
                action = action,
                deployedAt = null,
                createdBy = userId,
            )
        )
        if (stage.deploymentPolicy == DeploymentPolicy.IMMEDIATE) {
            if (deploymentExecutor.attemptDeploy(workload, stage, release.binaryUrl)) {
                entry.deployedAt = Instant.now()
                releaseHistoryRepository.save(entry)
            }
        }
        log.info(
            "Recorded history for release {} at stage {} for workload {}: {}",
            release.id, stage.id, workload.id, if (entry.deployedAt != null) "deployed" else "pending",
        )
    }

    // Next SCHEDULED-policy trigger time for a still-pending history entry — same
    // computation DeploymentSchedulerJob uses to decide when it's due — so the UI can show
    // "Pending (<time>)" instead of a bare "Pending". Null once deployed, for
    // IMMEDIATE-policy stages (retried every tick, no fixed time), or if no cron is
    // configured for this (product, stage).
    private fun scheduledDeploymentFor(entry: ReleaseHistory, stage: Stage?, cron: String?): Instant? {
        if (entry.deployedAt != null || stage?.deploymentPolicy != DeploymentPolicy.SCHEDULED || cron.isNullOrBlank()) return null
        return try {
            CronExpression.parse(cron).next(entry.createdAt!!.atZone(ZoneId.systemDefault()))?.toInstant()
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun incrementReleaseMetric(counterName: String, workload: Workload, product: Product, stage: Stage) {
        meterRegistry.counter(
            counterName,
            "product", product.name,
            "workload", workload.name,
            "stage", stage.name,
        ).increment()
    }

    private fun productFor(workload: Workload): Product =
        productRepository.findById(workload.productId)
            .orElseThrow { IllegalStateException("Product ${workload.productId} not found") }

    // The head of a (workload, stage) pair is whichever release currently sitting at that
    // stage has the most recent history entry there — i.e. the last one actually deployed,
    // regardless of whether it got there via create(), promote(), or rollback().
    private fun headReleaseId(workloadId: UUID, stageId: UUID): UUID? {
        val candidateIds = repository.findByWorkloadIdAndCurrentStageId(workloadId, stageId).mapNotNull { it.id }
        if (candidateIds.isEmpty()) return null
        return releaseHistoryRepository.findFirstByStageIdAndReleaseIdInOrderByCreatedAtDesc(stageId, candidateIds)?.releaseId
    }

    private fun isHead(release: Release): Boolean =
        headReleaseId(release.workloadId, release.currentStageId) == release.id

    private fun redeployableStagesFor(release: Release): List<Stage> {
        val orderedStages = orderedStagesFor(release.workloadId)
        val currentIndex = orderedStages.indexOfFirst { it.id == release.currentStageId }
        if (currentIndex == -1) return emptyList()
        val upToAndIncludingCurrent = orderedStages.subList(0, currentIndex + 1)
        return if (isHead(release)) upToAndIncludingCurrent else upToAndIncludingCurrent.dropLast(1)
    }

    private fun validateBinaryUrl(url: String) {
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "binaryUrl must be a valid URL")
        }
        if (uri.scheme?.lowercase() !in setOf("http", "https")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "binaryUrl must be an http(s) URL")
        }
    }

    private fun currentUserId(): UUID =
        currentUser.currentAuditor.orElseThrow { IllegalStateException("Current user could not be determined") }

    private fun orderedStagesFor(workloadId: UUID): List<Stage> {
        val linkedStageIds = workloadStageRepository.findByWorkloadId(workloadId).map { it.stageId }.toSet()
        return stageRepository.findAll(Sort.by("order")).filter { it.id in linkedStageIds }
    }

    private fun firstStageFor(workloadId: UUID): Stage =
        orderedStagesFor(workloadId).firstOrNull()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Workload has no linked stages")

    private fun Release.toResponse(): ReleaseResponse {
        val currentStage = stageRepository.findById(currentStageId).orElseThrow()
        val orderedStages = orderedStagesFor(workloadId)
        val currentIndex = orderedStages.indexOfFirst { it.id == currentStageId }
        val canPromote = currentIndex != -1 && currentIndex != orderedStages.lastIndex
        val canRollback = !isHead(this)
        val redeployableStages = redeployableStagesFor(this).map { ReleaseStageInfo(id = it.id!!, name = it.name, order = it.order) }
        val lastDeployedAt = releaseHistoryRepository.findTopByReleaseIdAndDeployedAtIsNotNullOrderByDeployedAtDesc(id!!)?.deployedAt
        return ReleaseResponse(
            id = id!!,
            binaryUrl = binaryUrl,
            description = description,
            workloadId = workloadId,
            currentStage = ReleaseStageInfo(id = currentStage.id!!, name = currentStage.name, order = currentStage.order),
            canPromote = canPromote,
            canRollback = canRollback,
            redeployableStages = redeployableStages,
            lastDeployedAt = lastDeployedAt,
            createdAt = createdAt!!,
            modifiedAt = modifiedAt!!,
            createdBy = createdBy,
            modifiedBy = modifiedBy,
        )
    }
}
