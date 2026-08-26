// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.product

import dev.juergenreiss.cdrm.stage.DeploymentPolicy
import dev.juergenreiss.cdrm.stage.StageRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.AuditorAware
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.ZoneId
import java.util.*

@Service
@Transactional(readOnly = true)
class ProductService(
    private val repository: ProductRepository,
    private val productStageRepository: ProductStageRepository,
    private val stageRepository: StageRepository,
    private val currentUser: AuditorAware<UUID>,
) {

    private val log = LoggerFactory.getLogger(ProductService::class.java)

    fun findAll(): List<ProductResponse> =
        repository.findAll(Sort.by("name")).map { it.toResponse() }

    fun findById(id: UUID): ProductResponse =
        repository.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }.toResponse()

    @Transactional
    fun create(request: ProductRequest): ProductResponse {
        val userId = currentUserId()
        val saved = repository.save(
            Product(name = request.name, description = request.description, createdBy = userId, modifiedBy = userId)
        )
        if (request.stageDeploymentCrons != null) {
            updateStageCrons(saved.id!!, request.stageDeploymentCrons)
        }
        log.info("Created product {} ('{}') by user {}", saved.id, saved.name, userId)
        return saved.toResponse()
    }

    @Transactional
    fun update(id: UUID, request: ProductRequest): ProductResponse {
        val product = repository.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        product.name = request.name
        product.description = request.description
        product.modifiedBy = currentUserId()
        val saved = repository.save(product)
        if (request.stageDeploymentCrons != null) {
            updateStageCrons(saved.id!!, request.stageDeploymentCrons)
        }
        log.info("Updated product {} ('{}') by user {}", saved.id, saved.name, saved.modifiedBy)
        return saved.toResponse()
    }

    private fun updateStageCrons(productId: UUID, entries: List<ProductStageCronRequest>) {
        val stagesById = stageRepository.findAllById(entries.map { it.stageId }).associateBy { it.id!! }
        val unknownIds = entries.map { it.stageId }.toSet() - stagesById.keys
        if (unknownIds.isNotEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown stage id(s): $unknownIds")
        }

        for (entry in entries) {
            val stage = stagesById.getValue(entry.stageId)
            if (stage.deploymentPolicy != DeploymentPolicy.SCHEDULED) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Stage '${stage.name}' is not SCHEDULED — a deployment time cannot be set",
                )
            }
            if (!CronExpression.isValidExpression(entry.deploymentCron)) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid cron expression for stage '${stage.name}': ${entry.deploymentCron}",
                )
            }
        }

        val existingByStageId = productStageRepository.findByProductId(productId).associateBy { it.stageId }
        val requestedStageIds = entries.map { it.stageId }.toSet()

        val toRemove = existingByStageId.values.filter { it.stageId !in requestedStageIds }
        if (toRemove.isNotEmpty()) {
            productStageRepository.deleteAll(toRemove)
        }

        for (entry in entries) {
            val existing = existingByStageId[entry.stageId]
            if (existing != null) {
                existing.deploymentCron = entry.deploymentCron
                productStageRepository.save(existing)
            } else {
                productStageRepository.save(
                    ProductStage(productId = productId, stageId = entry.stageId, deploymentCron = entry.deploymentCron)
                )
            }
        }
    }

    @Transactional
    fun delete(id: UUID) {
        if (!repository.existsById(id)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val userId = currentUserId()
        repository.deleteById(id)
        log.info("Deleted product {} by user {}", id, userId)
    }

    private fun currentUserId(): UUID =
        currentUser.currentAuditor.orElseThrow { IllegalStateException("Current user could not be determined") }

    private fun nextDeploymentAt(cron: String): Instant? =
        try {
            CronExpression.parse(cron).next(Instant.now().atZone(ZoneId.systemDefault()))?.toInstant()
        } catch (e: IllegalArgumentException) {
            null
        }

    private fun Product.toResponse(): ProductResponse {
        val crons = productStageRepository.findByProductId(id!!)
        val stagesById = stageRepository.findAllById(crons.map { it.stageId }).associateBy { it.id!! }
        return ProductResponse(
            id = id!!,
            name = name,
            description = description,
            stageDeploymentCrons = crons.mapNotNull { c ->
                stagesById[c.stageId]?.let { stage ->
                    ProductStageCronInfo(
                        stageId = stage.id!!,
                        stageName = stage.name,
                        deploymentCron = c.deploymentCron,
                        nextDeploymentAt = nextDeploymentAt(c.deploymentCron),
                    )
                }
            },
            createdAt = createdAt!!,
            modifiedAt = modifiedAt!!,
            createdBy = createdBy,
            modifiedBy = modifiedBy,
        )
    }
}
