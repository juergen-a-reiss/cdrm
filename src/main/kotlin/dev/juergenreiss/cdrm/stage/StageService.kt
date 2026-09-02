// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.stage

import dev.juergenreiss.cdrm.cluster.ClusterRepository
import dev.juergenreiss.cdrm.common.SortSpec
import dev.juergenreiss.cdrm.common.sortedBySpec
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
class StageService(
    private val repository: StageRepository,
    private val clusterRepository: ClusterRepository,
    private val stageClusterRepository: StageClusterRepository,
    private val currentUser: AuditorAware<UUID>,
) {

    private val log = LoggerFactory.getLogger(StageService::class.java)

    private val defaultSort = SortSpec("pipeline", descending = false)
    private val sortComparators: Map<String, Comparator<StageResponse>> = mapOf(
        "order" to compareBy { it.order },
        "pipeline" to compareBy { it.pipeline },
        "name" to compareBy { it.name },
        "deploymentPolicy" to compareBy { it.deploymentPolicy },
        "kubernetesContext" to compareBy(nullsFirst()) { it.kubernetesContext },
        "namespacePrefix" to compareBy(nullsFirst()) { it.namespacePrefix },
        "description" to compareBy(nullsFirst()) { it.description },
    )

    fun findAll(sort: String? = null): List<StageResponse> =
        repository.findAll().map { it.toResponse() }.sortedBySpec(SortSpec.parse(sort, defaultSort), sortComparators)

    fun findById(id: UUID): StageResponse =
        repository.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }.toResponse()

    @Transactional
    fun create(request: StageRequest): StageResponse {
        val userId = currentUserId()
        val saved = repository.save(
            Stage(
                pipeline = request.pipeline,
                name = request.name,
                description = request.description,
                order = request.order,
                deploymentPolicy = request.deploymentPolicy,
                kubernetesContext = request.kubernetesContext,
                namespacePrefix = request.namespacePrefix,
                createdBy = userId,
                modifiedBy = userId,
            )
        )
        if (request.clusterIds != null) {
            updateClusterLinks(saved.id!!, request.clusterIds)
        }
        log.info("Created stage {} ('{}') by user {}", saved.id, saved.name, userId)
        return saved.toResponse()
    }

    @Transactional
    fun update(id: UUID, request: StageRequest): StageResponse {
        val stage = repository.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        stage.pipeline = request.pipeline
        stage.name = request.name
        stage.description = request.description
        stage.order = request.order
        stage.deploymentPolicy = request.deploymentPolicy
        stage.kubernetesContext = request.kubernetesContext
        stage.namespacePrefix = request.namespacePrefix
        stage.modifiedBy = currentUserId()
        val saved = repository.save(stage)
        if (request.clusterIds != null) {
            updateClusterLinks(saved.id!!, request.clusterIds)
        }
        log.info("Updated stage {} ('{}') by user {}", saved.id, saved.name, saved.modifiedBy)
        return saved.toResponse()
    }

    private fun updateClusterLinks(stageId: UUID, clusterIds: List<UUID>) {
        val requestedIds = clusterIds.toSet()
        val validClusterIds = clusterRepository.findAllById(requestedIds).map { it.id }.toSet()
        val unknownIds = requestedIds - validClusterIds
        if (unknownIds.isNotEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown cluster id(s): $unknownIds")
        }

        val existingLinks = stageClusterRepository.findByStageId(stageId)
        val existingClusterIds = existingLinks.map { it.clusterId }.toSet()

        val toRemove = existingLinks.filter { it.clusterId !in requestedIds }
        if (toRemove.isNotEmpty()) {
            stageClusterRepository.deleteAll(toRemove)
        }

        val toAdd = requestedIds - existingClusterIds
        if (toAdd.isNotEmpty()) {
            stageClusterRepository.saveAll(toAdd.map { StageCluster(stageId = stageId, clusterId = it) })
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
            throw ResponseStatusException(HttpStatus.CONFLICT, "Stage is still referenced by one or more releases")
        }
        log.info("Deleted stage {} by user {}", id, userId)
    }

    private fun currentUserId(): UUID =
        currentUser.currentAuditor.orElseThrow { IllegalStateException("Current user could not be determined") }

    private fun Stage.toResponse(): StageResponse {
        val linkedClusterIds = stageClusterRepository.findByStageId(id!!).map { it.clusterId }.toSet()
        val clusters = clusterRepository.findAll(Sort.by("name"))
            .filter { it.id in linkedClusterIds }
            .map { StageClusterInfo(id = it.id!!, name = it.name, clusterType = it.clusterType) }
        return StageResponse(
            id = id!!,
            pipeline = pipeline,
            name = name,
            description = description,
            order = order,
            deploymentPolicy = deploymentPolicy,
            kubernetesContext = kubernetesContext,
            namespacePrefix = namespacePrefix,
            clusters = clusters,
            createdAt = createdAt!!,
            modifiedAt = modifiedAt!!,
            createdBy = createdBy,
            modifiedBy = modifiedBy,
        )
    }
}
