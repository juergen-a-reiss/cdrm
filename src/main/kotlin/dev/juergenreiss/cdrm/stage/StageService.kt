// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.stage

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
    private val currentUser: AuditorAware<UUID>,
) {

    private val log = LoggerFactory.getLogger(StageService::class.java)

    fun findAll(): List<StageResponse> =
        repository.findAll(Sort.by("order")).map { it.toResponse() }

    fun findById(id: UUID): StageResponse =
        repository.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }.toResponse()

    @Transactional
    fun create(request: StageRequest): StageResponse {
        val userId = currentUserId()
        val saved = repository.save(
            Stage(
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
        log.info("Created stage {} ('{}') by user {}", saved.id, saved.name, userId)
        return saved.toResponse()
    }

    @Transactional
    fun update(id: UUID, request: StageRequest): StageResponse {
        val stage = repository.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        stage.name = request.name
        stage.description = request.description
        stage.order = request.order
        stage.deploymentPolicy = request.deploymentPolicy
        stage.kubernetesContext = request.kubernetesContext
        stage.namespacePrefix = request.namespacePrefix
        stage.modifiedBy = currentUserId()
        val saved = repository.save(stage)
        log.info("Updated stage {} ('{}') by user {}", saved.id, saved.name, saved.modifiedBy)
        return saved.toResponse()
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

    private fun Stage.toResponse() = StageResponse(
        id = id!!,
        name = name,
        description = description,
        order = order,
        deploymentPolicy = deploymentPolicy,
        kubernetesContext = kubernetesContext,
        namespacePrefix = namespacePrefix,
        createdAt = createdAt!!,
        modifiedAt = modifiedAt!!,
        createdBy = createdBy,
        modifiedBy = modifiedBy,
    )
}
