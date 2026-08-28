package dev.juergenreiss.cdrm.release

import dev.juergenreiss.cdrm.product.Product
import dev.juergenreiss.cdrm.product.ProductRepository
import dev.juergenreiss.cdrm.product.ProductStage
import dev.juergenreiss.cdrm.product.ProductStageRepository
import dev.juergenreiss.cdrm.stage.DeploymentPolicy
import dev.juergenreiss.cdrm.stage.Stage
import dev.juergenreiss.cdrm.stage.StageRepository
import dev.juergenreiss.cdrm.workload.Workload
import dev.juergenreiss.cdrm.workload.WorkloadRepository
import dev.juergenreiss.cdrm.workload.WorkloadStage
import dev.juergenreiss.cdrm.workload.WorkloadStageRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.domain.AuditorAware
import org.springframework.data.domain.Sort
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class ReleaseServiceTest {

    @Mock
    private lateinit var repository: ReleaseRepository

    @Mock
    private lateinit var releaseHistoryRepository: ReleaseHistoryRepository

    @Mock
    private lateinit var stageRepository: StageRepository

    @Mock
    private lateinit var workloadRepository: WorkloadRepository

    @Mock
    private lateinit var workloadStageRepository: WorkloadStageRepository

    @Mock
    private lateinit var productRepository: ProductRepository

    @Mock
    private lateinit var productStageRepository: ProductStageRepository

    @Mock
    private lateinit var deploymentExecutor: DeploymentExecutor

    @Mock
    private lateinit var currentUser: AuditorAware<UUID>

    private val meterRegistry = SimpleMeterRegistry()

    private lateinit var service: ReleaseService

    @BeforeEach
    fun setUp() {
        service = ReleaseService(
            repository,
            releaseHistoryRepository,
            stageRepository,
            workloadRepository,
            workloadStageRepository,
            productRepository,
            productStageRepository,
            deploymentExecutor,
            currentUser,
            meterRegistry,
        )
    }

    private fun persistedStage(
        order: Int,
        name: String = "Stage-$order",
        deploymentPolicy: DeploymentPolicy = DeploymentPolicy.IMMEDIATE,
        kubernetesContext: String? = null,
    ) = Stage(
        id = UUID.randomUUID(),
        name = name,
        description = null,
        order = order,
        deploymentPolicy = deploymentPolicy,
        kubernetesContext = kubernetesContext,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    private fun persistedWorkload(
        id: UUID = UUID.randomUUID(),
        productId: UUID = UUID.randomUUID(),
        kubernetes: Boolean = false,
    ) = Workload(
        id = id,
        name = "workload-$id",
        productId = productId,
        description = null,
        kubernetes = kubernetes,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    private fun persistedProduct(id: UUID = UUID.randomUUID(), name: String = "product-$id") = Product(
        id = id,
        name = name,
        description = null,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    private fun persistedRelease(
        id: UUID = UUID.randomUUID(),
        binaryUrl: String = "https://registry.example.com/app:1.0.0",
        workloadId: UUID = UUID.randomUUID(),
        currentStageId: UUID,
    ) = Release(
        id = id,
        binaryUrl = binaryUrl,
        description = null,
        workloadId = workloadId,
        currentStageId = currentStageId,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    private fun stubWorkloadStages(workloadId: UUID, stages: List<Stage>) {
        given(workloadStageRepository.findByWorkloadId(workloadId)).willReturn(
            stages.map { WorkloadStage(workloadId = workloadId, stageId = it.id!!) }
        )
    }

    // recordHistory() reads back the row it just created (to mutate/re-save it on a
    // successful immediate deploy), so the mock must echo its argument instead of the
    // Mockito default of null.
    private fun stubHistorySaveEchoesArgument() {
        given(releaseHistoryRepository.save(any())).willAnswer { it.arguments[0] as ReleaseHistory }
    }

    @Test
    fun `create starts the release at the lowest-order linked stage and deploys immediately`() {
        val workloadId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        given(currentUser.currentAuditor).willReturn(Optional.of(userId))
        val product = persistedProduct()
        val workload = persistedWorkload(id = workloadId, productId = product.id!!)
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(workload))
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))

        val dev = persistedStage(order = 1, name = "Dev")
        val qa = persistedStage(order = 2, name = "QA")
        stubWorkloadStages(workloadId, listOf(dev, qa))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev, qa))

        val saved = persistedRelease(workloadId = workloadId, currentStageId = dev.id!!)
        given(repository.save(any())).willReturn(saved)
        given(stageRepository.findById(dev.id!!)).willReturn(Optional.of(dev))
        given(deploymentExecutor.attemptDeploy(workload, dev, saved.binaryUrl)).willReturn(true)
        stubHistorySaveEchoesArgument()

        val result = service.create(
            ReleaseRequest(binaryUrl = "https://registry.example.com/app:1.0.0", description = null, workloadId = workloadId)
        )

        assertEquals(dev.id, result.currentStage.id)
        assertEquals("Dev", result.currentStage.name)
        assertTrue(result.canPromote)

        val captor = ArgumentCaptor.forClass(ReleaseHistory::class.java)
        // Saved twice: once to create the row, once more after a successful immediate deploy sets deployedAt.
        verify(releaseHistoryRepository, org.mockito.Mockito.times(2)).save(captor.capture())
        assertEquals(saved.id, captor.value.releaseId)
        assertEquals(dev.id, captor.value.stageId)
        assertEquals(saved.binaryUrl, captor.value.binaryUrl)
        assertEquals(userId, captor.value.createdBy)
        assertNotNull(captor.value.deployedAt)
    }

    @Test
    fun `create throws 400 when workload has no linked stages`() {
        val workloadId = UUID.randomUUID()
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(persistedWorkload(id = workloadId)))
        stubWorkloadStages(workloadId, emptyList())

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.create(
                ReleaseRequest(binaryUrl = "https://registry.example.com/app:1.0.0", description = null, workloadId = workloadId)
            )
        }

        assertEquals(400, exception.statusCode.value())
    }

    @Test
    fun `create rejects a non-http binaryUrl`() {
        val exception = assertThrows(ResponseStatusException::class.java) {
            service.create(ReleaseRequest(binaryUrl = "not-a-url", description = null, workloadId = UUID.randomUUID()))
        }

        assertEquals(400, exception.statusCode.value())
    }

    @Test
    fun `create rejects a SCHEDULED stage with no configured deployment time`() {
        val workloadId = UUID.randomUUID()
        val productId = UUID.randomUUID()
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(persistedWorkload(id = workloadId, productId = productId)))

        val dev = persistedStage(order = 1, name = "Dev", deploymentPolicy = DeploymentPolicy.SCHEDULED)
        stubWorkloadStages(workloadId, listOf(dev))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev))
        given(productStageRepository.findByProductIdAndStageId(productId, dev.id!!)).willReturn(null)

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.create(
                ReleaseRequest(binaryUrl = "https://registry.example.com/app:1.0.0", description = null, workloadId = workloadId)
            )
        }

        assertEquals(400, exception.statusCode.value())
        verify(repository, never()).save(any())
    }

    @Test
    fun `create leaves deployedAt null for a SCHEDULED stage with a configured deployment time`() {
        val workloadId = UUID.randomUUID()
        val productId = UUID.randomUUID()
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(persistedWorkload(id = workloadId, productId = productId)))
        given(productRepository.findById(productId)).willReturn(Optional.of(persistedProduct(id = productId)))

        val dev = persistedStage(order = 1, name = "Dev", deploymentPolicy = DeploymentPolicy.SCHEDULED)
        stubWorkloadStages(workloadId, listOf(dev))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev))
        given(productStageRepository.findByProductIdAndStageId(productId, dev.id!!)).willReturn(
            ProductStage(productId = productId, stageId = dev.id!!, deploymentCron = "0 0 2 * * *")
        )

        val saved = persistedRelease(workloadId = workloadId, currentStageId = dev.id!!)
        given(repository.save(any())).willReturn(saved)
        given(stageRepository.findById(dev.id!!)).willReturn(Optional.of(dev))
        stubHistorySaveEchoesArgument()

        service.create(ReleaseRequest(binaryUrl = "https://registry.example.com/app:1.0.0", description = null, workloadId = workloadId))

        val captor = ArgumentCaptor.forClass(ReleaseHistory::class.java)
        verify(releaseHistoryRepository).save(captor.capture())
        assertNull(captor.value.deployedAt)
    }

    @Test
    fun `create rejects a kubernetes workload with no cluster configured for the stage`() {
        val workloadId = UUID.randomUUID()
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(persistedWorkload(id = workloadId, kubernetes = true)))

        val dev = persistedStage(order = 1, name = "Dev", kubernetesContext = null)
        stubWorkloadStages(workloadId, listOf(dev))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev))

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.create(
                ReleaseRequest(binaryUrl = "https://registry.example.com/app:1.0.0", description = null, workloadId = workloadId)
            )
        }

        assertEquals(400, exception.statusCode.value())
        verify(repository, never()).save(any())
    }

    @Test
    fun `create leaves deployedAt null when the immediate deployment attempt fails`() {
        val workloadId = UUID.randomUUID()
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        val product = persistedProduct()
        val workload = persistedWorkload(id = workloadId, productId = product.id!!, kubernetes = true)
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(workload))
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))

        val dev = persistedStage(order = 1, name = "Dev", kubernetesContext = "prod-cluster")
        stubWorkloadStages(workloadId, listOf(dev))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev))

        val saved = persistedRelease(workloadId = workloadId, currentStageId = dev.id!!)
        given(repository.save(any())).willReturn(saved)
        given(stageRepository.findById(dev.id!!)).willReturn(Optional.of(dev))
        given(deploymentExecutor.attemptDeploy(workload, dev, saved.binaryUrl)).willReturn(false)
        stubHistorySaveEchoesArgument()

        service.create(ReleaseRequest(binaryUrl = "https://registry.example.com/app:1.0.0", description = null, workloadId = workloadId))

        val captor = ArgumentCaptor.forClass(ReleaseHistory::class.java)
        verify(releaseHistoryRepository).save(captor.capture())
        assertNull(captor.value.deployedAt)
    }

    @Test
    fun `update rejects changing binaryUrl`() {
        val releaseId = UUID.randomUUID()
        val stageId = UUID.randomUUID()
        val release = persistedRelease(id = releaseId, binaryUrl = "https://registry.example.com/app:1.0.0", currentStageId = stageId)
        given(repository.findById(releaseId)).willReturn(Optional.of(release))

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.update(
                releaseId,
                ReleaseRequest(binaryUrl = "https://registry.example.com/app:2.0.0", description = null, workloadId = release.workloadId)
            )
        }

        assertEquals(400, exception.statusCode.value())
    }

    @Test
    fun `promote moves to the next stage and records history`() {
        val workloadId = UUID.randomUUID()
        val dev = persistedStage(order = 1, name = "Dev")
        val qa = persistedStage(order = 2, name = "QA")
        val prod = persistedStage(order = 3, name = "Prod")
        stubWorkloadStages(workloadId, listOf(dev, qa, prod))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev, qa, prod))

        val releaseId = UUID.randomUUID()
        val release = persistedRelease(id = releaseId, workloadId = workloadId, currentStageId = dev.id!!)
        given(repository.findById(releaseId)).willReturn(Optional.of(release))
        given(repository.save(release)).willReturn(release)
        val product = persistedProduct(name = "Platform")
        val workload = persistedWorkload(id = workloadId, productId = product.id!!)
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(workload))
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))
        val userId = UUID.randomUUID()
        given(currentUser.currentAuditor).willReturn(Optional.of(userId))
        given(stageRepository.findById(qa.id!!)).willReturn(Optional.of(qa))
        given(deploymentExecutor.attemptDeploy(workload, qa, release.binaryUrl)).willReturn(true)
        stubHistorySaveEchoesArgument()

        val result = service.promote(releaseId)

        assertEquals(qa.id, release.currentStageId)
        assertEquals(qa.id, result.currentStage.id)
        assertTrue(result.canPromote)

        val captor = ArgumentCaptor.forClass(ReleaseHistory::class.java)
        // Saved twice: once to create the row, once more after a successful immediate deploy sets deployedAt.
        verify(releaseHistoryRepository, org.mockito.Mockito.times(2)).save(captor.capture())
        assertEquals(qa.id, captor.value.stageId)
        assertEquals(userId, captor.value.createdBy)
        assertNotNull(captor.value.deployedAt)
        assertEquals(
            1.0,
            meterRegistry.get("cdrm.releases.promoted")
                .tags("product", "Platform", "workload", workload.name, "stage", "QA")
                .counter()
                .count(),
        )
    }

    @Test
    fun `promote rejects a SCHEDULED next stage with no configured deployment time`() {
        val workloadId = UUID.randomUUID()
        val productId = UUID.randomUUID()
        val dev = persistedStage(order = 1, name = "Dev")
        val qa = persistedStage(order = 2, name = "QA", deploymentPolicy = DeploymentPolicy.SCHEDULED)
        stubWorkloadStages(workloadId, listOf(dev, qa))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev, qa))

        val releaseId = UUID.randomUUID()
        val release = persistedRelease(id = releaseId, workloadId = workloadId, currentStageId = dev.id!!)
        given(repository.findById(releaseId)).willReturn(Optional.of(release))
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(persistedWorkload(id = workloadId, productId = productId)))
        given(productStageRepository.findByProductIdAndStageId(productId, qa.id!!)).willReturn(null)

        val exception = assertThrows(ResponseStatusException::class.java) { service.promote(releaseId) }

        assertEquals(400, exception.statusCode.value())
        verify(repository, never()).save(any())
    }

    @Test
    fun `promote throws 409 when already at the final stage`() {
        val workloadId = UUID.randomUUID()
        val dev = persistedStage(order = 1, name = "Dev")
        val qa = persistedStage(order = 2, name = "QA")
        stubWorkloadStages(workloadId, listOf(dev, qa))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev, qa))

        val releaseId = UUID.randomUUID()
        val release = persistedRelease(id = releaseId, workloadId = workloadId, currentStageId = qa.id!!)
        given(repository.findById(releaseId)).willReturn(Optional.of(release))
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(persistedWorkload(id = workloadId)))

        val exception = assertThrows(ResponseStatusException::class.java) { service.promote(releaseId) }

        assertEquals(409, exception.statusCode.value())
    }

    @Test
    fun `promote throws 409 when current stage is no longer linked to the workload`() {
        val workloadId = UUID.randomUUID()
        val dev = persistedStage(order = 1, name = "Dev")
        val orphanStageId = UUID.randomUUID()
        stubWorkloadStages(workloadId, listOf(dev))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev))

        val releaseId = UUID.randomUUID()
        val release = persistedRelease(id = releaseId, workloadId = workloadId, currentStageId = orphanStageId)
        given(repository.findById(releaseId)).willReturn(Optional.of(release))
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(persistedWorkload(id = workloadId)))

        val exception = assertThrows(ResponseStatusException::class.java) { service.promote(releaseId) }

        assertEquals(409, exception.statusCode.value())
    }

    @Test
    fun `rollback redeploys a superseded release without moving its stage`() {
        val workloadId = UUID.randomUUID()
        val prod = persistedStage(order = 1, name = "Prod")
        stubWorkloadStages(workloadId, listOf(prod))

        val product = persistedProduct(name = "Platform")
        val workload = persistedWorkload(id = workloadId, productId = product.id!!)
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(workload))
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))
        given(stageRepository.findById(prod.id!!)).willReturn(Optional.of(prod))

        val headReleaseId = UUID.randomUUID()
        val targetReleaseId = UUID.randomUUID()
        val target = persistedRelease(id = targetReleaseId, workloadId = workloadId, currentStageId = prod.id!!)
        given(repository.findById(targetReleaseId)).willReturn(Optional.of(target))
        given(repository.save(target)).willReturn(target)
        given(repository.findByWorkloadIdAndCurrentStageId(workloadId, prod.id!!)).willReturn(
            listOf(persistedRelease(id = headReleaseId, workloadId = workloadId, currentStageId = prod.id!!), target)
        )
        given(
            releaseHistoryRepository.findFirstByStageIdAndReleaseIdInOrderByCreatedAtDesc(
                prod.id!!, listOf(headReleaseId, targetReleaseId)
            )
        ).willReturn(
            ReleaseHistory(releaseId = headReleaseId, binaryUrl = "https://registry.example.com/head", stageId = prod.id!!, createdBy = UUID.randomUUID())
        )

        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        given(deploymentExecutor.attemptDeploy(workload, prod, target.binaryUrl)).willReturn(true)
        stubHistorySaveEchoesArgument()

        val result = service.rollback(targetReleaseId)

        assertEquals(prod.id, result.currentStage.id)

        val captor = ArgumentCaptor.forClass(ReleaseHistory::class.java)
        verify(releaseHistoryRepository, org.mockito.Mockito.times(2)).save(captor.capture())
        assertEquals(targetReleaseId, captor.value.releaseId)
        assertEquals(prod.id, captor.value.stageId)
        assertNotNull(captor.value.deployedAt)

        assertEquals(
            1.0,
            meterRegistry.get("cdrm.releases.rollback")
                .tags("product", "Platform", "workload", workload.name, "stage", "Prod")
                .counter()
                .count(),
        )
    }

    @Test
    fun `rollback throws 409 when the release is already head at its stage`() {
        val workloadId = UUID.randomUUID()
        val prod = persistedStage(order = 1, name = "Prod")
        val releaseId = UUID.randomUUID()
        val release = persistedRelease(id = releaseId, workloadId = workloadId, currentStageId = prod.id!!)
        given(repository.findById(releaseId)).willReturn(Optional.of(release))
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(persistedWorkload(id = workloadId)))
        given(stageRepository.findById(prod.id!!)).willReturn(Optional.of(prod))
        given(repository.findByWorkloadIdAndCurrentStageId(workloadId, prod.id!!)).willReturn(listOf(release))
        given(
            releaseHistoryRepository.findFirstByStageIdAndReleaseIdInOrderByCreatedAtDesc(prod.id!!, listOf(releaseId))
        ).willReturn(
            ReleaseHistory(releaseId = releaseId, binaryUrl = release.binaryUrl, stageId = prod.id!!, createdBy = UUID.randomUUID())
        )

        val exception = assertThrows(ResponseStatusException::class.java) { service.rollback(releaseId) }

        assertEquals(409, exception.statusCode.value())
        verify(repository, never()).save(any())
    }

    @Test
    fun `redeploy to a lower stage deploys there without moving currentStageId`() {
        val workloadId = UUID.randomUUID()
        val dev = persistedStage(order = 1, name = "Dev")
        val qa = persistedStage(order = 2, name = "QA")
        val prod = persistedStage(order = 3, name = "Prod")
        stubWorkloadStages(workloadId, listOf(dev, qa, prod))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev, qa, prod))
        given(stageRepository.findById(prod.id!!)).willReturn(Optional.of(prod))

        val product = persistedProduct(name = "Platform")
        val workload = persistedWorkload(id = workloadId, productId = product.id!!)
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(workload))
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))

        val releaseId = UUID.randomUUID()
        val release = persistedRelease(id = releaseId, workloadId = workloadId, currentStageId = prod.id!!)
        given(repository.findById(releaseId)).willReturn(Optional.of(release))
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        given(deploymentExecutor.attemptDeploy(workload, dev, release.binaryUrl)).willReturn(true)
        stubHistorySaveEchoesArgument()

        val result = service.redeploy(releaseId, RedeployRequest(stageId = dev.id!!))

        assertEquals(prod.id, result.currentStage.id)
        assertEquals(prod.id, release.currentStageId)
        verify(repository, never()).save(any())

        val captor = ArgumentCaptor.forClass(ReleaseHistory::class.java)
        verify(releaseHistoryRepository, org.mockito.Mockito.times(2)).save(captor.capture())
        assertEquals(dev.id, captor.value.stageId)
        assertNotNull(captor.value.deployedAt)

        assertEquals(
            1.0,
            meterRegistry.get("cdrm.releases.redeploy")
                .tags("product", "Platform", "workload", workload.name, "stage", "Dev")
                .counter()
                .count(),
        )
    }

    @Test
    fun `redeploy to the current stage succeeds when the release is head there`() {
        val workloadId = UUID.randomUUID()
        val prod = persistedStage(order = 1, name = "Prod")
        stubWorkloadStages(workloadId, listOf(prod))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(prod))
        given(stageRepository.findById(prod.id!!)).willReturn(Optional.of(prod))

        val product = persistedProduct(name = "Platform")
        val workload = persistedWorkload(id = workloadId, productId = product.id!!)
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(workload))
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))

        val releaseId = UUID.randomUUID()
        val release = persistedRelease(id = releaseId, workloadId = workloadId, currentStageId = prod.id!!)
        given(repository.findById(releaseId)).willReturn(Optional.of(release))
        given(repository.findByWorkloadIdAndCurrentStageId(workloadId, prod.id!!)).willReturn(listOf(release))
        given(
            releaseHistoryRepository.findFirstByStageIdAndReleaseIdInOrderByCreatedAtDesc(prod.id!!, listOf(releaseId))
        ).willReturn(
            ReleaseHistory(releaseId = releaseId, binaryUrl = release.binaryUrl, stageId = prod.id!!, createdBy = UUID.randomUUID())
        )
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        given(deploymentExecutor.attemptDeploy(workload, prod, release.binaryUrl)).willReturn(true)
        stubHistorySaveEchoesArgument()

        val result = service.redeploy(releaseId, RedeployRequest(stageId = prod.id!!))

        assertEquals(prod.id, result.currentStage.id)
        val captor = ArgumentCaptor.forClass(ReleaseHistory::class.java)
        verify(releaseHistoryRepository, org.mockito.Mockito.times(2)).save(captor.capture())
        assertEquals(prod.id, captor.value.stageId)
    }

    @Test
    fun `redeploy throws 409 when targeting the current stage while not head`() {
        val workloadId = UUID.randomUUID()
        val prod = persistedStage(order = 1, name = "Prod")
        stubWorkloadStages(workloadId, listOf(prod))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(prod))

        val releaseId = UUID.randomUUID()
        val headReleaseId = UUID.randomUUID()
        val release = persistedRelease(id = releaseId, workloadId = workloadId, currentStageId = prod.id!!)
        given(repository.findById(releaseId)).willReturn(Optional.of(release))
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(persistedWorkload(id = workloadId)))
        given(repository.findByWorkloadIdAndCurrentStageId(workloadId, prod.id!!)).willReturn(
            listOf(release, persistedRelease(id = headReleaseId, workloadId = workloadId, currentStageId = prod.id!!))
        )
        given(
            releaseHistoryRepository.findFirstByStageIdAndReleaseIdInOrderByCreatedAtDesc(
                prod.id!!, listOf(releaseId, headReleaseId)
            )
        ).willReturn(
            ReleaseHistory(releaseId = headReleaseId, binaryUrl = "https://registry.example.com/head", stageId = prod.id!!, createdBy = UUID.randomUUID())
        )

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.redeploy(releaseId, RedeployRequest(stageId = prod.id!!))
        }

        assertEquals(409, exception.statusCode.value())
        verify(releaseHistoryRepository, never()).save(any())
    }

    @Test
    fun `redeploy throws 400 when target stage is later than the current stage`() {
        val workloadId = UUID.randomUUID()
        val dev = persistedStage(order = 1, name = "Dev")
        val qa = persistedStage(order = 2, name = "QA")
        stubWorkloadStages(workloadId, listOf(dev, qa))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev, qa))

        val releaseId = UUID.randomUUID()
        val release = persistedRelease(id = releaseId, workloadId = workloadId, currentStageId = dev.id!!)
        given(repository.findById(releaseId)).willReturn(Optional.of(release))
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(persistedWorkload(id = workloadId)))

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.redeploy(releaseId, RedeployRequest(stageId = qa.id!!))
        }

        assertEquals(400, exception.statusCode.value())
        verify(releaseHistoryRepository, never()).save(any())
    }

    @Test
    fun `redeploy throws 400 when target stage is unknown to the workload`() {
        val workloadId = UUID.randomUUID()
        val dev = persistedStage(order = 1, name = "Dev")
        stubWorkloadStages(workloadId, listOf(dev))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev))

        val releaseId = UUID.randomUUID()
        val release = persistedRelease(id = releaseId, workloadId = workloadId, currentStageId = dev.id!!)
        given(repository.findById(releaseId)).willReturn(Optional.of(release))
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(persistedWorkload(id = workloadId)))

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.redeploy(releaseId, RedeployRequest(stageId = UUID.randomUUID()))
        }

        assertEquals(400, exception.statusCode.value())
    }

    @Test
    fun `toResponse exposes redeployableStages up to and including the current stage when head`() {
        val workloadId = UUID.randomUUID()
        val dev = persistedStage(order = 1, name = "Dev")
        val qa = persistedStage(order = 2, name = "QA")
        val prod = persistedStage(order = 3, name = "Prod")
        stubWorkloadStages(workloadId, listOf(dev, qa, prod))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev, qa, prod))
        given(stageRepository.findById(qa.id!!)).willReturn(Optional.of(qa))

        val releaseId = UUID.randomUUID()
        val release = persistedRelease(id = releaseId, workloadId = workloadId, currentStageId = qa.id!!)
        given(repository.findById(releaseId)).willReturn(Optional.of(release))
        given(repository.findByWorkloadIdAndCurrentStageId(workloadId, qa.id!!)).willReturn(listOf(release))
        given(
            releaseHistoryRepository.findFirstByStageIdAndReleaseIdInOrderByCreatedAtDesc(qa.id!!, listOf(releaseId))
        ).willReturn(
            ReleaseHistory(releaseId = releaseId, binaryUrl = release.binaryUrl, stageId = qa.id!!, createdBy = UUID.randomUUID())
        )

        val result = service.findById(releaseId)

        assertEquals(listOf(dev.id, qa.id), result.redeployableStages.map { it.id })
    }

    @Test
    fun `toResponse excludes the current stage from redeployableStages when not head`() {
        val workloadId = UUID.randomUUID()
        val dev = persistedStage(order = 1, name = "Dev")
        val qa = persistedStage(order = 2, name = "QA")
        stubWorkloadStages(workloadId, listOf(dev, qa))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev, qa))
        given(stageRepository.findById(qa.id!!)).willReturn(Optional.of(qa))

        val releaseId = UUID.randomUUID()
        val headReleaseId = UUID.randomUUID()
        val release = persistedRelease(id = releaseId, workloadId = workloadId, currentStageId = qa.id!!)
        given(repository.findById(releaseId)).willReturn(Optional.of(release))
        given(repository.findByWorkloadIdAndCurrentStageId(workloadId, qa.id!!)).willReturn(
            listOf(release, persistedRelease(id = headReleaseId, workloadId = workloadId, currentStageId = qa.id!!))
        )
        given(
            releaseHistoryRepository.findFirstByStageIdAndReleaseIdInOrderByCreatedAtDesc(qa.id!!, listOf(releaseId, headReleaseId))
        ).willReturn(
            ReleaseHistory(releaseId = headReleaseId, binaryUrl = "https://registry.example.com/head", stageId = qa.id!!, createdBy = UUID.randomUUID())
        )

        val result = service.findById(releaseId)

        assertEquals(listOf(dev.id), result.redeployableStages.map { it.id })
    }

    @Test
    fun `toResponse reports canRollback false when the release is head`() {
        val workloadId = UUID.randomUUID()
        val prod = persistedStage(order = 1, name = "Prod")
        val releaseId = UUID.randomUUID()
        val release = persistedRelease(id = releaseId, workloadId = workloadId, currentStageId = prod.id!!)
        given(repository.findById(releaseId)).willReturn(Optional.of(release))
        given(stageRepository.findById(prod.id!!)).willReturn(Optional.of(prod))
        given(repository.findByWorkloadIdAndCurrentStageId(workloadId, prod.id!!)).willReturn(listOf(release))
        given(
            releaseHistoryRepository.findFirstByStageIdAndReleaseIdInOrderByCreatedAtDesc(prod.id!!, listOf(releaseId))
        ).willReturn(
            ReleaseHistory(releaseId = releaseId, binaryUrl = release.binaryUrl, stageId = prod.id!!, createdBy = UUID.randomUUID())
        )

        val result = service.findById(releaseId)

        assertFalse(result.canRollback)
    }

    @Test
    fun `toResponse reports canPromote false at the final stage`() {
        val workloadId = UUID.randomUUID()
        val dev = persistedStage(order = 1, name = "Dev")
        val qa = persistedStage(order = 2, name = "QA")
        stubWorkloadStages(workloadId, listOf(dev, qa))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev, qa))
        given(stageRepository.findById(qa.id!!)).willReturn(Optional.of(qa))

        val releaseId = UUID.randomUUID()
        val release = persistedRelease(id = releaseId, workloadId = workloadId, currentStageId = qa.id!!)
        given(repository.findById(releaseId)).willReturn(Optional.of(release))

        val result = service.findById(releaseId)

        assertFalse(result.canPromote)
    }

    @Test
    fun `toResponse exposes lastDeployedAt from history`() {
        val workloadId = UUID.randomUUID()
        val dev = persistedStage(order = 1, name = "Dev")
        stubWorkloadStages(workloadId, listOf(dev))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev))
        given(stageRepository.findById(dev.id!!)).willReturn(Optional.of(dev))

        val releaseId = UUID.randomUUID()
        val release = persistedRelease(id = releaseId, workloadId = workloadId, currentStageId = dev.id!!)
        given(repository.findById(releaseId)).willReturn(Optional.of(release))

        val deployedAt = Instant.now()
        given(releaseHistoryRepository.findTopByReleaseIdAndDeployedAtIsNotNullOrderByDeployedAtDesc(releaseId)).willReturn(
            ReleaseHistory(releaseId = releaseId, binaryUrl = release.binaryUrl, stageId = dev.id!!, deployedAt = deployedAt, createdBy = UUID.randomUUID())
        )

        val result = service.findById(releaseId)

        assertEquals(deployedAt, result.lastDeployedAt)
    }

    @Test
    fun `update to a different workload recomputes the starting stage and records history`() {
        val oldWorkloadId = UUID.randomUUID()
        val newWorkloadId = UUID.randomUUID()
        val oldStage = persistedStage(order = 1, name = "Old-Dev")
        val newStage = persistedStage(order = 1, name = "New-Dev")

        val releaseId = UUID.randomUUID()
        val release = persistedRelease(id = releaseId, workloadId = oldWorkloadId, currentStageId = oldStage.id!!)
        given(repository.findById(releaseId)).willReturn(Optional.of(release))
        given(repository.save(release)).willReturn(release)
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        given(workloadRepository.findById(newWorkloadId)).willReturn(Optional.of(persistedWorkload(id = newWorkloadId)))

        stubWorkloadStages(newWorkloadId, listOf(newStage))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(newStage))
        given(stageRepository.findById(newStage.id!!)).willReturn(Optional.of(newStage))
        stubHistorySaveEchoesArgument()

        val result = service.update(
            releaseId,
            ReleaseRequest(binaryUrl = release.binaryUrl, description = null, workloadId = newWorkloadId)
        )

        assertEquals(newStage.id, release.currentStageId)
        assertEquals(newStage.id, result.currentStage.id)
        verify(releaseHistoryRepository).save(any())
    }

    @Test
    fun `update to the same workload does not record history`() {
        val workloadId = UUID.randomUUID()
        val stage = persistedStage(order = 1, name = "Dev")

        val releaseId = UUID.randomUUID()
        val release = persistedRelease(id = releaseId, workloadId = workloadId, currentStageId = stage.id!!)
        given(repository.findById(releaseId)).willReturn(Optional.of(release))
        given(repository.save(release)).willReturn(release)
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        stubWorkloadStages(workloadId, listOf(stage))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(stage))
        given(stageRepository.findById(stage.id!!)).willReturn(Optional.of(stage))

        service.update(releaseId, ReleaseRequest(binaryUrl = release.binaryUrl, description = "updated", workloadId = workloadId))

        verify(releaseHistoryRepository, never()).save(any())
    }

    @Test
    fun `history returns entries mapped with resolved stage info and deployedAt`() {
        val releaseId = UUID.randomUUID()
        given(repository.existsById(releaseId)).willReturn(true)

        val stage = persistedStage(order = 1, name = "Dev")
        val deployedAt = Instant.now()
        val entry = ReleaseHistory(
            id = UUID.randomUUID(),
            releaseId = releaseId,
            binaryUrl = "https://registry.example.com/app:1.0.0",
            stageId = stage.id!!,
            deployedAt = deployedAt,
            createdAt = Instant.now(),
            createdBy = UUID.randomUUID(),
        )
        given(releaseHistoryRepository.findByReleaseIdOrderByCreatedAtDesc(releaseId)).willReturn(listOf(entry))
        given(stageRepository.findAllById(setOf(stage.id!!))).willReturn(listOf(stage))

        val result = service.history(releaseId)

        assertEquals(1, result.size)
        assertEquals(stage.name, result[0].stage.name)
        assertEquals(entry.binaryUrl, result[0].binaryUrl)
        assertEquals(deployedAt, result[0].deployedAt)
    }

    @Test
    fun `history throws 404 when release is missing`() {
        val releaseId = UUID.randomUUID()
        given(repository.existsById(releaseId)).willReturn(false)

        val exception = assertThrows(ResponseStatusException::class.java) { service.history(releaseId) }

        assertEquals(404, exception.statusCode.value())
    }

    @Test
    fun `findById throws 404 when missing`() {
        val id = UUID.randomUUID()
        given(repository.findById(id)).willReturn(Optional.empty())

        val exception = assertThrows(ResponseStatusException::class.java) { service.findById(id) }

        assertEquals(404, exception.statusCode.value())
    }
}
