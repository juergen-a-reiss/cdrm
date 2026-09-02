// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.workload

import dev.juergenreiss.cdrm.common.SortSpec
import dev.juergenreiss.cdrm.common.sortedBySpec
import dev.juergenreiss.cdrm.product.ProductRepository
import dev.juergenreiss.cdrm.security.RebacContext
import dev.juergenreiss.cdrm.stage.StageRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
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
    private val productRepository: ProductRepository,
    private val currentUser: AuditorAware<UUID>,
    private val rebac: RebacContext,
) {

    private val log = LoggerFactory.getLogger(WorkloadService::class.java)

    private val defaultSort = SortSpec("name", descending = false)
    private val sortComparators: Map<String, Comparator<WorkloadResponse>> = mapOf(
        "name" to compareBy { it.name },
        "pipeline" to compareBy { it.pipeline },
    )

    // ReBAC (see README): cdrm-products restricts by the workload's product, and
    // cdrm-workloads further restricts by the workload's own name — both, when set on
    // the caller, exact-match. Batches the product lookup instead of one per workload.
    fun findAll(sort: String? = null): List<WorkloadResponse> {
        val workloads = repository.findAll()
        val productsById = productRepository.findAllById(workloads.map { it.productId }.toSet()).associateBy { it.id }
        val spec = SortSpec.parse(sort, defaultSort)
        val comparators = sortComparators + mapOf(
            "productName" to compareBy<WorkloadResponse> { productsById[it.productId]?.name ?: "" },
        )
        return workloads
            .filter { rebac.canSeeWorkload(productsById[it.productId]?.name ?: "", it.name) }
            .map { it.toResponse() }
            .sortedBySpec(spec, comparators)
    }

    fun findById(id: UUID): WorkloadResponse {
        val workload = repository.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        val productName = productRepository.findById(workload.productId).orElseThrow().name
        if (!rebac.canSeeWorkload(productName, workload.name)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return workload.toResponse()
    }

    @Transactional
    fun create(request: WorkloadRequest): WorkloadResponse {
        validateTarget(request.kubernetes, request.kubernetesKind, request.kubernetesNameSpace)
        // A new workload always links to every stage of its own pipeline (see update()'s
        // stageIds for changing that set later) — a pipeline with no stages at all can't
        // host a workload, so reject before writing anything.
        val stageIds = stageRepository.findAll().filter { it.pipeline == request.pipeline }.map { it.id!! }
        if (stageIds.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No stages found for pipeline '${request.pipeline}'")
        }
        val userId = currentUserId()
        val saved = repository.save(
            Workload(
                name = request.name,
                productId = request.productId,
                description = request.description,
                kubernetes = request.kubernetes,
                kubernetesKind = request.kubernetesKind,
                kubernetesNameSpace = request.kubernetesNameSpace,
                pipeline = request.pipeline,
                createdBy = userId,
                modifiedBy = userId,
            )
        )
        workloadStageRepository.saveAll(stageIds.map { WorkloadStage(workloadId = saved.id!!, stageId = it) })
        log.info("Created workload {} ('{}') by user {}, linked to {} stage(s)", saved.id, saved.name, userId, stageIds.size)
        return saved.toResponse()
    }

    @Transactional
    fun update(id: UUID, request: WorkloadRequest): WorkloadResponse {
        validateTarget(request.kubernetes, request.kubernetesKind, request.kubernetesNameSpace)
        val stagesById = stageRepository.findAll().associateBy { it.id!! }
        if (stagesById.values.none { it.pipeline == request.pipeline }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No stages found for pipeline '${request.pipeline}'")
        }
        val workload = repository.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        // The stages this workload will be linked to after this update — the requested
        // set if it's changing, otherwise whatever it's already linked to — must all
        // belong to the workload's (possibly just-changed) pipeline. Unknown ids are left
        // for updateStageLinks()'s own check below rather than reported here.
        val connectedStageIds = request.stageIds ?: workloadStageRepository.findByWorkloadId(id).map { it.stageId }
        val mismatchedStageIds = connectedStageIds.filter { stagesById[it]?.let { stage -> stage.pipeline != request.pipeline } ?: false }
        if (mismatchedStageIds.isNotEmpty()) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Stage(s) $mismatchedStageIds do not belong to pipeline '${request.pipeline}'",
            )
        }
        workload.name = request.name
        workload.productId = request.productId
        workload.description = request.description
        workload.kubernetes = request.kubernetes
        workload.kubernetesKind = request.kubernetesKind
        workload.kubernetesNameSpace = request.kubernetesNameSpace
        workload.pipeline = request.pipeline
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
        try {
            repository.deleteById(id)
            repository.flush()
        } catch (e: DataIntegrityViolationException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Workload is still referenced by one or more releases")
        }
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
            pipeline = pipeline,
            stages = stages,
            createdAt = createdAt!!,
            modifiedAt = modifiedAt!!,
            createdBy = createdBy,
            modifiedBy = modifiedBy,
        )
    }
}