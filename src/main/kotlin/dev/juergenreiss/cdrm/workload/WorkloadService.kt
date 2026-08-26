// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.workload

import dev.juergenreiss.cdrm.stage.StageRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.AuditorAware
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.*

@Service
@Transactional(readOnly = true)
class WorkloadService(
    private val repository: WorkloadRepository,
    private val stageRepository: StageRepository,
    private val workloadStageRepository: WorkloadStageRepository,
    private val currentUser: AuditorAware<UUID>,
) {

    private val log = LoggerFactory.getLogger(WorkloadService::class.java)

    fun findAll(): List<WorkloadResponse> =
        repository.findAll(Sort.by("name")).map { it.toResponse() }

    fun findById(id: UUID): WorkloadResponse =
        repository.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }.toResponse()

    @Transactional
    fun create(request: WorkloadRequest): WorkloadResponse {
        validateTarget(request.kubernetes, request.kubernetesKind, request.kubernetesNameSpace)
        val userId = currentUserId()
        val saved = repository.save(
            Workload(
                name = request.name,
                productId = request.productId,
                description = request.description,
                kubernetes = request.kubernetes,
                kubernetesKind = request.kubernetesKind,
                kubernetesNameSpace = request.kubernetesNameSpace,
                createdBy = userId,
                modifiedBy = userId,
            )
        )
        val stageIds = stageRepository.findAll().map { it.id!! }
        workloadStageRepository.saveAll(stageIds.map { WorkloadStage(workloadId = saved.id!!, stageId = it) })
        log.info("Created workload {} ('{}') by user {}, linked to {} stage(s)", saved.id, saved.name, userId, stageIds.size)
        return saved.toResponse()
    }

    @Transactional
    fun update(id: UUID, request: WorkloadRequest): WorkloadResponse {
        validateTarget(request.kubernetes, request.kubernetesKind, request.kubernetesNameSpace)
        val workload = repository.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        workload.name = request.name
        workload.productId = request.productId
        workload.description = request.description
        workload.kubernetes = request.kubernetes
        workload.kubernetesKind = request.kubernetesKind
        workload.kubernetesNameSpace = request.kubernetesNameSpace
        workload.modifiedBy = currentUserId()
        val saved = repository.save(workload)
        if (request.stageIds != null) {
            updateStageLinks(saved.id!!, request.stageIds)
        }
        log.info("Updated workload {} ('{}') by user {}", saved.id, saved.name, saved.modifiedBy)
        return saved.toResponse()
    }

    private fun validateTarget(kubernetes: Boolean, kubernetesKind: KubernetesKind?, kubernetesNameSpace: String?) {
        if (kubernetes && kubernetesKind == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "kubernetesKind is required when kubernetes is true")
        }
        if (!kubernetes && kubernetesKind != null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "kubernetesKind must not be set when kubernetes is false")
        }
        if (kubernetes && kubernetesNameSpace.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "kubernetesNameSpace is required when kubernetes is true")
        }
        if (!kubernetes && kubernetesNameSpace != null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "kubernetesNameSpace must not be set when kubernetes is false")
        }
    }

    private fun updateStageLinks(workloadId: UUID, stageIds: List<UUID>) {
        val requestedIds = stageIds.toSet()
        val validStageIds = stageRepository.findAllById(requestedIds).map { it.id }.toSet()
        val unknownIds = requestedIds - validStageIds
        if (unknownIds.isNotEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown stage id(s): $unknownIds")
        }

        val existingLinks = workloadStageRepository.findByWorkloadId(workloadId)
        val existingStageIds = existingLinks.map { it.stageId }.toSet()

        val toRemove = existingLinks.filter { it.stageId !in requestedIds }
        if (toRemove.isNotEmpty()) {
            workloadStageRepository.deleteAll(toRemove)
        }

        val toAdd = requestedIds - existingStageIds
        if (toAdd.isNotEmpty()) {
            workloadStageRepository.saveAll(toAdd.map { WorkloadStage(workloadId = workloadId, stageId = it) })
        }
    }

    @Transactional
    fun delete(id: UUID) {
        if (!repository.existsById(id)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val userId = currentUserId()
        repository.deleteById(id)
        log.info("Deleted workload {} by user {}", id, userId)
    }

    private fun currentUserId(): UUID =
        currentUser.currentAuditor.orElseThrow { IllegalStateException("Current user could not be determined") }

    private fun Workload.toResponse(): WorkloadResponse {
        val linkedStageIds = workloadStageRepository.findByWorkloadId(id!!).map { it.stageId }.toSet()
        val stages = stageRepository.findAll(Sort.by("order"))
            .filter { it.id in linkedStageIds }
            .map { WorkloadStageInfo(id = it.id!!, name = it.name, description = it.description) }
        return WorkloadResponse(
            id = id!!,
            name = name,
            productId = productId,
            description = description,
            kubernetes = kubernetes,
            kubernetesKind = kubernetesKind,
            kubernetesNameSpace = kubernetesNameSpace,
            stages = stages,
            createdAt = createdAt!!,
            modifiedAt = modifiedAt!!,
            createdBy = createdBy,
            modifiedBy = modifiedBy,
        )
    }
}