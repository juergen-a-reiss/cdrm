package dev.juergenreiss.cdrm.product

import dev.juergenreiss.cdrm.security.RebacContext
import dev.juergenreiss.cdrm.stage.DeploymentPolicy
import dev.juergenreiss.cdrm.stage.Stage
import dev.juergenreiss.cdrm.stage.StageRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.argThat
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.AuditorAware
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class ProductServiceTest {

    @Mock
    private lateinit var repository: ProductRepository

    @Mock
    private lateinit var productStageRepository: ProductStageRepository

    @Mock
    private lateinit var stageRepository: StageRepository

    @Mock
    private lateinit var currentUser: AuditorAware<UUID>

    @Mock
    private lateinit var rebac: RebacContext

    private lateinit var service: ProductService

    @BeforeEach
    fun setUp() {
        service = ProductService(repository, productStageRepository, stageRepository, currentUser, rebac)
    }

    private fun persistedStage(deploymentPolicy: DeploymentPolicy, name: String = "Stage") = Stage(
        id = UUID.randomUUID(),
        pipeline = "pipeline",
        name = name,
        description = null,
        order = 1,
        deploymentPolicy = deploymentPolicy,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    private fun persistedProduct(
        id: UUID = UUID.randomUUID(),
        name: String = "Product",
        isGroup: Boolean = false,
        productGroupId: UUID? = null,
    ) = Product(
        id = id,
        name = name,
        description = null,
        isGroup = isGroup,
        productGroupId = productGroupId,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    @Test
    fun `create without stageDeploymentCrons saves no ProductStage rows`() {
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        given(repository.save(any())).willReturn(persistedProduct())

        service.create(ProductRequest(name = "Product", description = null))

        verify(productStageRepository, never()).save(any())
    }

    @Test
    fun `create with a valid cron for a SCHEDULED stage saves it`() {
        val userId = UUID.randomUUID()
        given(currentUser.currentAuditor).willReturn(Optional.of(userId))
        val saved = persistedProduct()
        given(repository.save(any())).willReturn(saved)

        val stage = persistedStage(DeploymentPolicy.SCHEDULED, name = "Prod")
        given(stageRepository.findAllById(listOf(stage.id!!))).willReturn(listOf(stage))
        given(productStageRepository.findByProductId(saved.id!!)).willReturn(emptyList())

        service.create(
            ProductRequest(
                name = "Product",
                description = null,
                stageDeploymentCrons = listOf(ProductStageCronRequest(stageId = stage.id!!, deploymentCron = "0 0 2 * * *")),
            )
        )

        verify(productStageRepository).save(any())
    }

    @Test
    fun `create rejects a cron for a stage that is not SCHEDULED`() {
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        val saved = persistedProduct()
        given(repository.save(any())).willReturn(saved)

        val stage = persistedStage(DeploymentPolicy.IMMEDIATE, name = "Dev")
        given(stageRepository.findAllById(listOf(stage.id!!))).willReturn(listOf(stage))

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.create(
                ProductRequest(
                    name = "Product",
                    description = null,
                    stageDeploymentCrons = listOf(ProductStageCronRequest(stageId = stage.id!!, deploymentCron = "0 0 2 * * *")),
                )
            )
        }

        assertEquals(400, exception.statusCode.value())
        verify(productStageRepository, never()).save(any())
    }

    @Test
    fun `create rejects an invalid cron expression`() {
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        val saved = persistedProduct()
        given(repository.save(any())).willReturn(saved)

        val stage = persistedStage(DeploymentPolicy.SCHEDULED, name = "Prod")
        given(stageRepository.findAllById(listOf(stage.id!!))).willReturn(listOf(stage))

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.create(
                ProductRequest(
                    name = "Product",
                    description = null,
                    stageDeploymentCrons = listOf(ProductStageCronRequest(stageId = stage.id!!, deploymentCron = "not a cron")),
                )
            )
        }

        assertEquals(400, exception.statusCode.value())
    }

    @Test
    fun `create rejects an unknown stage id`() {
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        val saved = persistedProduct()
        given(repository.save(any())).willReturn(saved)

        val unknownId = UUID.randomUUID()
        given(stageRepository.findAllById(listOf(unknownId))).willReturn(emptyList())

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.create(
                ProductRequest(
                    name = "Product",
                    description = null,
                    stageDeploymentCrons = listOf(ProductStageCronRequest(stageId = unknownId, deploymentCron = "0 0 2 * * *")),
                )
            )
        }

        assertEquals(400, exception.statusCode.value())
    }

    @Test
    fun `create rejects duplicate stage ids in the same request`() {
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        val saved = persistedProduct()
        given(repository.save(any())).willReturn(saved)

        val stage = persistedStage(DeploymentPolicy.SCHEDULED, name = "Prod")

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.create(
                ProductRequest(
                    name = "Product",
                    description = null,
                    stageDeploymentCrons = listOf(
                        ProductStageCronRequest(stageId = stage.id!!, deploymentCron = "0 0 2 * * *"),
                        ProductStageCronRequest(stageId = stage.id!!, deploymentCron = "0 0 3 * * *"),
                    ),
                )
            )
        }

        assertEquals(400, exception.statusCode.value())
        verify(productStageRepository, never()).save(any())
    }

    @Test
    fun `update reconciles cron config, adding, updating and removing`() {
        val productId = UUID.randomUUID()
        val product = persistedProduct(id = productId)
        given(repository.findById(productId)).willReturn(Optional.of(product))
        given(repository.save(product)).willReturn(product)
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))

        val keepStage = persistedStage(DeploymentPolicy.SCHEDULED, name = "QA")
        val addStage = persistedStage(DeploymentPolicy.SCHEDULED, name = "Prod")
        val removeStage = persistedStage(DeploymentPolicy.SCHEDULED, name = "Staging")

        val existingKeep = ProductStage(productId = productId, stageId = keepStage.id!!, deploymentCron = "0 0 1 * * *")
        val existingRemove = ProductStage(productId = productId, stageId = removeStage.id!!, deploymentCron = "0 0 3 * * *")
        given(productStageRepository.findByProductId(productId)).willReturn(listOf(existingKeep, existingRemove))

        val requested = listOf(
            ProductStageCronRequest(stageId = keepStage.id!!, deploymentCron = "0 30 1 * * *"),
            ProductStageCronRequest(stageId = addStage.id!!, deploymentCron = "0 0 2 * * *"),
        )
        given(stageRepository.findAllById(requested.map { it.stageId })).willReturn(listOf(keepStage, addStage))

        service.update(productId, ProductRequest(name = "Product", description = null, stageDeploymentCrons = requested))

        verify(productStageRepository).deleteAll(listOf(existingRemove))
        verify(productStageRepository).save(existingKeep)
        assertEquals("0 30 1 * * *", existingKeep.deploymentCron)
        verify(productStageRepository).save(argThat<ProductStage> { it.stageId == addStage.id })
    }

    @Test
    fun `delete removes existing product after resolving current user`() {
        val id = UUID.randomUUID()
        given(repository.existsById(id)).willReturn(true)
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))

        service.delete(id)

        verify(repository).deleteById(id)
    }

    @Test
    fun `delete throws 404 when missing`() {
        val id = UUID.randomUUID()
        given(repository.existsById(id)).willReturn(false)

        val exception = assertThrows(ResponseStatusException::class.java) { service.delete(id) }

        assertEquals(404, exception.statusCode.value())
        verify(repository, never()).deleteById(id)
    }

    @Test
    fun `delete throws 409 when product is still referenced by a workload`() {
        val id = UUID.randomUUID()
        given(repository.existsById(id)).willReturn(true)
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        given(repository.flush()).willThrow(DataIntegrityViolationException::class.java)

        val exception = assertThrows(ResponseStatusException::class.java) { service.delete(id) }

        assertEquals(409, exception.statusCode.value())
    }

    @Test
    fun `update with null stageDeploymentCrons leaves configuration untouched`() {
        val productId = UUID.randomUUID()
        val product = persistedProduct(id = productId)
        given(repository.findById(productId)).willReturn(Optional.of(product))
        given(repository.save(product)).willReturn(product)
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))

        service.update(productId, ProductRequest(name = "Product", description = null, stageDeploymentCrons = null))

        // findByProductId is still called once, by toResponse() to report current config —
        // what must NOT happen is any write.
        verify(productStageRepository, never()).save(any())
        verify(productStageRepository, never()).deleteAll(any<List<ProductStage>>())
    }

    @Test
    fun `findAll returns only products the caller's cdrm-products claim allows`() {
        val visible = persistedProduct().apply { name = "Platform" }
        val hidden = persistedProduct().apply { name = "Payments" }
        given(repository.findAll()).willReturn(listOf(visible, hidden))
        given(rebac.canSeeProduct("Platform")).willReturn(true)
        given(rebac.canSeeProduct("Payments")).willReturn(false)

        val result = service.findAll()

        assertEquals(listOf("Platform"), result.map { it.name })
    }

    @Test
    fun `findAll sorts by name by default`() {
        val a = persistedProduct().apply { name = "a-product" }
        val b = persistedProduct().apply { name = "b-product" }
        given(repository.findAll()).willReturn(listOf(b, a))
        given(rebac.canSeeProduct(anyString())).willReturn(true)

        val result = service.findAll()

        assertEquals(listOf("a-product", "b-product"), result.map { it.name })
    }

    @Test
    fun `findAll rejects an unknown sort key`() {
        given(repository.findAll()).willReturn(listOf(persistedProduct()))
        given(rebac.canSeeProduct(anyString())).willReturn(true)

        val exception = assertThrows(ResponseStatusException::class.java) { service.findAll("bogus,asc") }

        assertEquals(400, exception.statusCode.value())
    }

    @Test
    fun `findById throws 404 for a product the caller's cdrm-products claim hides`() {
        val product = persistedProduct().apply { name = "Payments" }
        given(repository.findById(product.id!!)).willReturn(Optional.of(product))
        given(rebac.canSeeProduct("Payments")).willReturn(false)

        val exception = assertThrows(ResponseStatusException::class.java) { service.findById(product.id!!) }

        assertEquals(404, exception.statusCode.value())
    }

    @Test
    fun `create allows a product group with no crons`() {
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        val saved = persistedProduct(isGroup = true)
        given(repository.save(any())).willReturn(saved)

        val result = service.create(ProductRequest(name = "Core Products", description = null, isGroup = true))

        assertTrue(result.isGroup)
        verify(productStageRepository, never()).save(any())
    }

    @Test
    fun `create rejects deployment crons on a product group`() {
        val stage = persistedStage(DeploymentPolicy.SCHEDULED, name = "Prod")

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.create(
                ProductRequest(
                    name = "Core Products",
                    description = null,
                    isGroup = true,
                    stageDeploymentCrons = listOf(ProductStageCronRequest(stageId = stage.id!!, deploymentCron = "0 0 2 * * *")),
                )
            )
        }

        assertEquals(400, exception.statusCode.value())
        verify(repository, never()).save(any())
    }

    @Test
    fun `create rejects a productGroupId that is not a group`() {
        val notAGroup = persistedProduct(name = "Platform", isGroup = false)
        given(repository.findById(notAGroup.id!!)).willReturn(Optional.of(notAGroup))

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.create(ProductRequest(name = "Product", description = null, productGroupId = notAGroup.id))
        }

        assertEquals(400, exception.statusCode.value())
        verify(repository, never()).save(any())
    }

    @Test
    fun `create rejects an unknown productGroupId`() {
        val unknownId = UUID.randomUUID()
        given(repository.findById(unknownId)).willReturn(Optional.empty())

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.create(ProductRequest(name = "Product", description = null, productGroupId = unknownId))
        }

        assertEquals(400, exception.statusCode.value())
    }

    @Test
    fun `create with a valid productGroupId saves the group link`() {
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        val group = persistedProduct(name = "Core Products", isGroup = true)
        given(repository.findById(group.id!!)).willReturn(Optional.of(group))
        val saved = persistedProduct(productGroupId = group.id)
        given(repository.save(any())).willReturn(saved)

        val result = service.create(ProductRequest(name = "Product", description = null, productGroupId = group.id))

        assertEquals(group.id, result.productGroupId)
    }

    @Test
    fun `update rejects a product being its own group`() {
        val productId = UUID.randomUUID()
        val product = persistedProduct(id = productId)
        given(repository.findById(productId)).willReturn(Optional.of(product))

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.update(productId, ProductRequest(name = "Product", description = null, productGroupId = productId))
        }

        assertEquals(400, exception.statusCode.value())
        verify(repository, never()).save(any())
    }

    @Test
    fun `update rejects a productGroupId that would create a cycle`() {
        val aId = UUID.randomUUID()
        val bId = UUID.randomUUID()
        val a = persistedProduct(id = aId, name = "A", isGroup = true)
        val b = persistedProduct(id = bId, name = "B", isGroup = true, productGroupId = aId)
        given(repository.findById(aId)).willReturn(Optional.of(a))
        given(repository.findById(bId)).willReturn(Optional.of(b))

        // A is currently B's parent; trying to also make B A's parent would close the loop.
        val exception = assertThrows(ResponseStatusException::class.java) {
            service.update(aId, ProductRequest(name = "A", description = null, isGroup = true, productGroupId = bId))
        }

        assertEquals(400, exception.statusCode.value())
        verify(repository, never()).save(any())
    }

    @Test
    fun `update rejects demoting a group that still has member products`() {
        val groupId = UUID.randomUUID()
        val group = persistedProduct(id = groupId, name = "Core Products", isGroup = true)
        given(repository.findById(groupId)).willReturn(Optional.of(group))
        given(repository.existsByProductGroupId(groupId)).willReturn(true)

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.update(groupId, ProductRequest(name = "Core Products", description = null, isGroup = false))
        }

        assertEquals(409, exception.statusCode.value())
        verify(repository, never()).save(any())
    }

    @Test
    fun `update allows demoting an empty group to a regular product`() {
        val groupId = UUID.randomUUID()
        val group = persistedProduct(id = groupId, name = "Core Products", isGroup = true)
        given(repository.findById(groupId)).willReturn(Optional.of(group))
        given(repository.existsByProductGroupId(groupId)).willReturn(false)
        given(repository.save(group)).willReturn(group)
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))

        val result = service.update(groupId, ProductRequest(name = "Core Products", description = null, isGroup = false))

        assertEquals(false, result.isGroup)
    }

    @Test
    fun `update allows reparenting a product to a valid group`() {
        val productId = UUID.randomUUID()
        val product = persistedProduct(id = productId)
        given(repository.findById(productId)).willReturn(Optional.of(product))
        val group = persistedProduct(name = "Core Products", isGroup = true)
        given(repository.findById(group.id!!)).willReturn(Optional.of(group))
        given(repository.save(product)).willReturn(product)
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))

        val result = service.update(productId, ProductRequest(name = "Product", description = null, productGroupId = group.id))

        assertEquals(group.id, result.productGroupId)
    }
}
