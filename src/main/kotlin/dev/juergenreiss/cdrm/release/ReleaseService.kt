// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.release

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
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.net.URISyntaxException
import java.time.Instant
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
        val stagesById = stageRepository.findAllById(entries.map { it.stageId }.toSet()).associateBy { it.id }
        return entries.map { entry ->
            val stage = stagesById[entry.stageId] ?: throw IllegalStateException("Stage ${entry.stageId} not found")
            ReleaseHistoryEntry(
                id = entry.id!!,
                binaryUrl = entry.binaryUrl,
                stage = ReleaseStageInfo(id = stage.id!!, name = stage.name, order = stage.order),
                timestamp = entry.createdAt!!,
                deployedAt = entry.deployedAt,
                createdBy = entry.createdBy,
            )
        }
    }

    @Transactional
    fun create(request: ReleaseRequest): ReleaseResponse {
        validateBinaryUrl(request.binaryUrl)
        val userId = currentUserId()
        val workload = workloadRepository.findById(request.workloadId)
            .orElseThrow { ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown workload") }
        val initialStage = firstStageFor(request.workloadId)
        requireDeployable(workload, initialStage)
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
        recordHistory(saved, initialStage, workload, userId)
        recordPromotionMetric(workload, initialStage)
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
            recordHistory(saved, newStage, newWorkload!!, userId)
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
        val userId = currentUserId()
        release.currentStageId = nextStage.id!!
        release.modifiedBy = userId
        val saved = repository.save(release)
        recordHistory(saved, nextStage, workload, userId)
        recordPromotionMetric(workload, nextStage)
        log.info("Promoted release {} ('{}') to stage {} by user {}", saved.id, saved.binaryUrl, nextStage.name, saved.modifiedBy)
        return saved.toResponse()
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
    private fun recordHistory(release: Release, stage: Stage, workload: Workload, userId: UUID) {
        val entry = releaseHistoryRepository.save(
            ReleaseHistory(
                releaseId = release.id!!,
                binaryUrl = release.binaryUrl,
                stageId = stage.id!!,
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

    private fun recordPromotionMetric(workload: Workload, stage: Stage) {
        val product = productRepository.findById(workload.productId)
            .orElseThrow { IllegalStateException("Product ${workload.productId} not found") }
        meterRegistry.counter(
            "cdrm.releases.promoted",
            "product", product.name,
            "workload", workload.name,
            "stage", stage.name,
        ).increment()
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
        val lastDeployedAt = releaseHistoryRepository.findTopByReleaseIdAndDeployedAtIsNotNullOrderByDeployedAtDesc(id!!)?.deployedAt
        return ReleaseResponse(
            id = id!!,
            binaryUrl = binaryUrl,
            description = description,
            workloadId = workloadId,
            currentStage = ReleaseStageInfo(id = currentStage.id!!, name = currentStage.name, order = currentStage.order),
            canPromote = canPromote,
            lastDeployedAt = lastDeployedAt,
            createdAt = createdAt!!,
            modifiedAt = modifiedAt!!,
            createdBy = createdBy,
            modifiedBy = modifiedBy,
        )
    }
}
