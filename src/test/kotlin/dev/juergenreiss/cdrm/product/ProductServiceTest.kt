package dev.juergenreiss.cdrm.product

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
import org.mockito.ArgumentMatchers.argThat
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
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

    private lateinit var service: ProductService

    @BeforeEach
    fun setUp() {
        service = ProductService(repository, productStageRepository, stageRepository, currentUser)
    }

    private fun persistedStage(deploymentPolicy: DeploymentPolicy, name: String = "Stage") = Stage(
        id = UUID.randomUUID(),
        name = name,
        description = null,
        order = 1,
        deploymentPolicy = deploymentPolicy,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    private fun persistedProduct(id: UUID = UUID.randomUUID()) = Product(
        id = id,
        name = "Product",
        description = null,
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
}
