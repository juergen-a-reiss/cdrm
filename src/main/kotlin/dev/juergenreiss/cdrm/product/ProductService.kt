// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.product

import dev.juergenreiss.cdrm.common.SortSpec
import dev.juergenreiss.cdrm.common.sortedBySpec
import dev.juergenreiss.cdrm.security.RebacContext
import dev.juergenreiss.cdrm.stage.DeploymentPolicy
import dev.juergenreiss.cdrm.stage.StageRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.AuditorAware
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
    private val rebac: RebacContext,
) {

    private val log = LoggerFactory.getLogger(ProductService::class.java)

    private val defaultSort = SortSpec("name", descending = false)
    private val sortComparators: Map<String, Comparator<ProductResponse>> = mapOf(
        "name" to compareBy { it.name },
        "description" to compareBy(nullsFirst()) { it.description },
    )

    // ReBAC (see README): cdrm-products, when set on the caller, restricts visibility
    // to an exact-match subset — silently for the list, as a 404 for a specific id (so
    // a hidden product doesn't leak its existence via a 403 vs. 404 distinction).
    fun findAll(sort: String? = null): List<ProductResponse> =
        repository.findAll()
            .filter { rebac.canSeeProduct(it.name) }
            .map { it.toResponse() }
            .sortedBySpec(SortSpec.parse(sort, defaultSort), sortComparators)

    fun findById(id: UUID): ProductResponse {
        val product = repository.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        if (!rebac.canSeeProduct(product.name)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return product.toResponse()
    }

    @Transactional
    fun create(request: ProductRequest): ProductResponse {
        validateGroupInvariants(id = null, request = request)
        val userId = currentUserId()
        val saved = repository.save(
            Product(
                name = request.name,
                description = request.description,
                isGroup = request.isGroup,
                productGroupId = request.productGroupId,
                createdBy = userId,
                modifiedBy = userId,
            )
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
        validateGroupInvariants(id = id, request = request)
        if (product.isGroup && !request.isGroup && repository.existsByProductGroupId(id)) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Product group '${product.name}' still has member products — move or delete them first",
            )
        }
        product.name = request.name
        product.description = request.description
        product.isGroup = request.isGroup
        product.productGroupId = request.productGroupId
        product.modifiedBy = currentUserId()
        val saved = repository.save(product)
        if (request.stageDeploymentCrons != null) {
            updateStageCrons(saved.id!!, request.stageDeploymentCrons)
        }
        log.info("Updated product {} ('{}') by user {}", saved.id, saved.name, saved.modifiedBy)
        return saved.toResponse()
    }

    // A product group only organizes the product catalog for humans — it can never be a
    // deployment target, so it can't carry deployment-time cron config (that's what would
    // let it acquire ProductStage rows), and its parent link (if any) must point at another
    // actual group, never at itself and never through a cycle back to itself.
    private fun validateGroupInvariants(id: UUID?, request: ProductRequest) {
        if (request.isGroup && !request.stageDeploymentCrons.isNullOrEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A product group cannot have deployment times configured")
        }
        val parentId = request.productGroupId ?: return
        if (parentId == id) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A product cannot be its own group")
        }
        val parent = repository.findById(parentId)
            .orElseThrow { ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown product group id: $parentId") }
        if (!parent.isGroup) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "'${parent.name}' is not a product group")
        }
        if (id == null) return
        val visited = mutableSetOf<UUID>()
        var cursor: Product? = parent
        while (cursor != null) {
            if (cursor.id == id) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Assigning '${parent.name}' as the group would create a cycle")
            }
            if (!visited.add(cursor.id!!)) break
            cursor = cursor.productGroupId?.let { repository.findById(it).orElse(null) }
        }
    }

    private fun updateStageCrons(productId: UUID, entries: List<ProductStageCronRequest>) {
        val duplicateIds = entries.groupingBy { it.stageId }.eachCount().filterValues { it > 1 }.keys
        if (duplicateIds.isNotEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate stage id(s): $duplicateIds")
        }

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
        try {
            repository.deleteById(id)
            repository.flush()
        } catch (e: DataIntegrityViolationException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Product is still referenced by one or more workloads")
        }
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
            isGroup = isGroup,
            productGroupId = productGroupId,
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
