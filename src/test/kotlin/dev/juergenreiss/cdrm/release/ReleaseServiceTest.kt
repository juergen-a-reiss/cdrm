package dev.juergenreiss.cdrm.release

import dev.juergenreiss.cdrm.product.Product
import dev.juergenreiss.cdrm.product.ProductRepository
import dev.juergenreiss.cdrm.product.ProductStage
import dev.juergenreiss.cdrm.product.ProductStageRepository
import dev.juergenreiss.cdrm.security.RebacContext
import dev.juergenreiss.cdrm.security.ReleaseActionClaim
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
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.lenient
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Spy
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.domain.AuditorAware
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
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
    private lateinit var releaseHistoryAggregationRepository: ReleaseHistoryAggregationRepository

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

    // A real (spied) instance, not a full mock: with no SecurityContext set up in these
    // unit tests, its canSeeWorkload()/allowsReleaseAction() naturally behave as
    // "unrestricted"/"claim not set" without needing to stub them — only hasRole() (used
    // as the fallback when a claim isn't set) needs overriding below.
    @Spy
    private lateinit var rebac: RebacContext

    private val meterRegistry = SimpleMeterRegistry()

    private lateinit var service: ReleaseService

    @BeforeEach
    fun setUp() {
        service = ReleaseService(
            repository,
            releaseHistoryRepository,
            releaseHistoryAggregationRepository,
            stageRepository,
            workloadRepository,
            workloadStageRepository,
            productRepository,
            productStageRepository,
            deploymentExecutor,
            currentUser,
            meterRegistry,
            rebac,
        )
        // Unrestricted by default — matches every test written before ReBAC existed.
        // Tests that specifically exercise ReBAC restrictions override this.
        lenient().doReturn(true).`when`(rebac).hasRole(anyString())
    }

    private fun persistedStage(
        order: Int,
        name: String = "Stage-$order",
        deploymentPolicy: DeploymentPolicy = DeploymentPolicy.IMMEDIATE,
        kubernetesContext: String? = null,
    ) = Stage(
        id = UUID.randomUUID(),
        pipeline = "pipeline",
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
        pipeline = "--",
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
        image: String = "registry.example.com/app:1.0.0",
        workloadId: UUID = UUID.randomUUID(),
        currentStageId: UUID,
    ) = Release(
        id = id,
        image = image,
        description = null,
        workloadId = workloadId,
        currentStageId = currentStageId,
        commitId = "no-id",
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    private fun persistedHistoryEntry(
        id: UUID = UUID.randomUUID(),
        releaseId: UUID,
        workloadId: UUID? = null,
        productId: UUID = UUID.randomUUID(),
        productName: String = "product",
        workloadName: String = "workload",
        image: String = "registry.example.com/app:1.0.0",
        stageId: UUID,
        stageName: String = "stage",
        action: ReleaseHistoryAction = ReleaseHistoryAction.PROMOTED,
        deployedAt: Instant? = null,
        deploymentFinished: Instant? = null,
        deploymentFailed: Boolean = false,
        createdAt: Instant = Instant.now(),
        createdBy: UUID = UUID.randomUUID(),
    ) = ReleaseHistory(
        id = id,
        releaseId = releaseId,
        workloadId = workloadId,
        productId = productId,
        productName = productName,
        workloadName = workloadName,
        image = image,
        stageId = stageId,
        stageName = stageName,
        action = action,
        deployedAt = deployedAt,
        deploymentFinished = deploymentFinished,
        deploymentFailed = deploymentFailed,
        createdAt = createdAt,
        createdBy = createdBy,
    )

    private fun stubWorkloadStages(workloadId: UUID, stages: List<Stage>) {
        given(workloadStageRepository.findByWorkloadId(workloadId)).willReturn(
            stages.map { WorkloadStage(workloadId = workloadId, stageId = it.id!!) }
        )
    }

    // requireVisible()/requireWorkloadVisible() need a real workload+product to check
    // ReBAC visibility against — stubs both with a throwaway product, since these tests
    // don't care about the product's identity, just that lookup succeeds.
    private fun stubWorkloadVisible(workloadId: UUID) {
        val product = persistedProduct()
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(persistedWorkload(id = workloadId, productId = product.id!!)))
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))
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
        given(deploymentExecutor.attemptDeploy(workload, dev, saved.image)).willReturn(null)
        stubHistorySaveEchoesArgument()

        val result = service.create(
            ReleaseRequest(image = "registry.example.com/app:1.0.0", description = null, commitId = "no-id", workloadId = workloadId)
        )

        assertEquals(dev.id, result.currentStage.id)
        assertEquals("Dev", result.currentStage.name)
        assertTrue(result.canPromote)

        val captor = ArgumentCaptor.forClass(ReleaseHistory::class.java)
        // Saved twice: once to create the row, once more after a successful immediate deploy sets deployedAt.
        verify(releaseHistoryRepository, org.mockito.Mockito.times(2)).save(captor.capture())
        assertEquals(saved.id, captor.value.releaseId)
        assertEquals(dev.id, captor.value.stageId)
        assertEquals(saved.image, captor.value.image)
        assertEquals(userId, captor.value.createdBy)
        assertEquals(ReleaseHistoryAction.CREATED, captor.value.action)
        assertNotNull(captor.value.deployedAt)
    }

    @Test
    fun `create passes the request's commitId through to the saved release and the response`() {
        val workloadId = UUID.randomUUID()
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        val product = persistedProduct()
        val workload = persistedWorkload(id = workloadId, productId = product.id!!)
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(workload))
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))

        val dev = persistedStage(order = 1, name = "Dev")
        stubWorkloadStages(workloadId, listOf(dev))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev))
        given(stageRepository.findById(dev.id!!)).willReturn(Optional.of(dev))

        val saved = persistedRelease(workloadId = workloadId, currentStageId = dev.id!!).apply { commitId = "abc123" }
        given(repository.save(any())).willReturn(saved)
        given(deploymentExecutor.attemptDeploy(workload, dev, saved.image)).willReturn(null)
        stubHistorySaveEchoesArgument()

        val result = service.create(
            ReleaseRequest(image = "registry.example.com/app:1.0.0", description = null, commitId = "abc123", workloadId = workloadId)
        )

        val captor = ArgumentCaptor.forClass(Release::class.java)
        verify(repository).save(captor.capture())
        assertEquals("abc123", captor.value.commitId)
        assertEquals("abc123", result.commitId)
    }

    @Test
    fun `create throws 400 when workload has no linked stages`() {
        val workloadId = UUID.randomUUID()
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        stubWorkloadVisible(workloadId)
        stubWorkloadStages(workloadId, emptyList())

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.create(
                ReleaseRequest(image = "registry.example.com/app:1.0.0", description = null, commitId = "no-id", workloadId = workloadId)
            )
        }

        assertEquals(400, exception.statusCode.value())
    }

    @Test
    fun `create rejects an image reference that carries a URL scheme for a kubernetes workload`() {
        val workloadId = UUID.randomUUID()
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        val product = persistedProduct()
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(persistedWorkload(id = workloadId, productId = product.id!!, kubernetes = true)))
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.create(
                ReleaseRequest(image = "https://registry.example.com/app:1.0.0", description = null, commitId = "no-id", workloadId = workloadId)
            )
        }

        assertEquals(400, exception.statusCode.value())
    }

    @Test
    fun `create rejects an image reference with a space in it for a kubernetes workload`() {
        val workloadId = UUID.randomUUID()
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        val product = persistedProduct()
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(persistedWorkload(id = workloadId, productId = product.id!!, kubernetes = true)))
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.create(ReleaseRequest(image = "nginx 30", description = null, commitId = "no-id", workloadId = workloadId))
        }

        assertEquals(400, exception.statusCode.value())
    }

    @Test
    fun `create accepts a bare unqualified image reference for a kubernetes workload`() {
        val workloadId = UUID.randomUUID()
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        val product = persistedProduct()
        val workload = persistedWorkload(id = workloadId, productId = product.id!!, kubernetes = true)
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(workload))
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))

        val dev = persistedStage(order = 1, name = "Dev", kubernetesContext = "prod-cluster")
        stubWorkloadStages(workloadId, listOf(dev))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev))

        val saved = persistedRelease(workloadId = workloadId, currentStageId = dev.id!!, image = "nginx:30")
        given(repository.save(any())).willReturn(saved)
        given(stageRepository.findById(dev.id!!)).willReturn(Optional.of(dev))
        given(deploymentExecutor.attemptDeploy(workload, dev, "nginx:30")).willReturn(null)
        stubHistorySaveEchoesArgument()

        val result = service.create(ReleaseRequest(image = "nginx:30", description = null, commitId = "no-id", workloadId = workloadId))

        assertEquals("nginx:30", result.image)
    }

    @Test
    fun `create accepts a non-image artifact reference for a non-kubernetes workload`() {
        val workloadId = UUID.randomUUID()
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        val product = persistedProduct()
        val workload = persistedWorkload(id = workloadId, productId = product.id!!, kubernetes = false)
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(workload))
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))

        val dev = persistedStage(order = 1, name = "Dev")
        stubWorkloadStages(workloadId, listOf(dev))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev))

        val artifact = "https://artifacts.example.com/mobile-bff/1.0.0/mobile-bff.tar.gz"
        val saved = persistedRelease(workloadId = workloadId, currentStageId = dev.id!!, image = artifact)
        given(repository.save(any())).willReturn(saved)
        given(stageRepository.findById(dev.id!!)).willReturn(Optional.of(dev))
        stubHistorySaveEchoesArgument()

        val result = service.create(ReleaseRequest(image = artifact, description = null, commitId = "no-id", workloadId = workloadId))

        assertEquals(artifact, result.image)
    }

    @Test
    fun `create rejects a SCHEDULED stage with no configured deployment time`() {
        val workloadId = UUID.randomUUID()
        val productId = UUID.randomUUID()
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(persistedWorkload(id = workloadId, productId = productId)))
        given(productRepository.findById(productId)).willReturn(Optional.of(persistedProduct(id = productId)))

        val dev = persistedStage(order = 1, name = "Dev", deploymentPolicy = DeploymentPolicy.SCHEDULED)
        stubWorkloadStages(workloadId, listOf(dev))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev))
        given(productStageRepository.findByProductIdAndStageId(productId, dev.id!!)).willReturn(null)

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.create(
                ReleaseRequest(image = "registry.example.com/app:1.0.0", description = null, commitId = "no-id", workloadId = workloadId)
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

        service.create(ReleaseRequest(image = "registry.example.com/app:1.0.0", description = null, commitId = "no-id", workloadId = workloadId))

        val captor = ArgumentCaptor.forClass(ReleaseHistory::class.java)
        verify(releaseHistoryRepository).save(captor.capture())
        assertNull(captor.value.deployedAt)
    }

    @Test
    fun `create rejects a kubernetes workload with no cluster configured for the stage`() {
        val workloadId = UUID.randomUUID()
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        val product = persistedProduct()
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(persistedWorkload(id = workloadId, productId = product.id!!, kubernetes = true)))
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))

        val dev = persistedStage(order = 1, name = "Dev", kubernetesContext = null)
        stubWorkloadStages(workloadId, listOf(dev))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev))

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.create(
                ReleaseRequest(image = "registry.example.com/app:1.0.0", description = null, commitId = "no-id", workloadId = workloadId)
            )
        }

        assertEquals(400, exception.statusCode.value())
        verify(repository, never()).save(any())
    }

    @Test
    fun `create throws 409 when another release is still deploying to the same initial stage`() {
        val workloadId = UUID.randomUUID()
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        val product = persistedProduct()
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(persistedWorkload(id = workloadId, productId = product.id!!)))
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))

        val dev = persistedStage(order = 1, name = "Dev")
        stubWorkloadStages(workloadId, listOf(dev))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev))
        given(releaseHistoryRepository.findFirstByWorkloadIdAndStageIdAndDeploymentFinishedIsNullOrderByCreatedAtDesc(workloadId, dev.id!!))
            .willReturn(persistedHistoryEntry(releaseId = UUID.randomUUID(), image = "other:1.0", stageId = dev.id!!, deployedAt = Instant.now()))

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.create(
                ReleaseRequest(image = "registry.example.com/app:1.0.0", description = null, commitId = "no-id", workloadId = workloadId)
            )
        }

        assertEquals(409, exception.statusCode.value())
        assertTrue(exception.reason?.contains("other:1.0") == true)
        verify(repository, never()).save(any())
    }

    @Test
    fun `create leaves deployedAt null and records the failure reason when the immediate deployment attempt fails`() {
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
        given(deploymentExecutor.attemptDeploy(workload, dev, saved.image)).willReturn("cluster not reachable")
        stubHistorySaveEchoesArgument()

        val result = service.create(
            ReleaseRequest(image = "registry.example.com/app:1.0.0", description = null, commitId = "no-id", workloadId = workloadId)
        )

        assertEquals("cluster not reachable", result.deployError)

        val captor = ArgumentCaptor.forClass(ReleaseHistory::class.java)
        // Saved twice: once to create the row, once more after the failed immediate deploy sets deployError.
        verify(releaseHistoryRepository, org.mockito.Mockito.times(2)).save(captor.capture())
        assertNull(captor.value.deployedAt)
        assertEquals("cluster not reachable", captor.value.deployError)
    }

    @Test
    fun `update rejects changing image`() {
        val releaseId = UUID.randomUUID()
        val stageId = UUID.randomUUID()
        val workloadId = UUID.randomUUID()
        val release = persistedRelease(id = releaseId, image = "registry.example.com/app:1.0.0", workloadId = workloadId, currentStageId = stageId)
        given(repository.findById(releaseId)).willReturn(Optional.of(release))
        val product = persistedProduct()
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(persistedWorkload(id = workloadId, productId = product.id!!)))
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.update(
                releaseId,
                ReleaseRequest(image = "registry.example.com/app:2.0.0", description = null, commitId = "no-id", workloadId = release.workloadId)
            )
        }

        assertEquals(400, exception.statusCode.value())
    }

    @Test
    fun `update rejects changing commitId`() {
        val releaseId = UUID.randomUUID()
        val stageId = UUID.randomUUID()
        val workloadId = UUID.randomUUID()
        val release = persistedRelease(id = releaseId, workloadId = workloadId, currentStageId = stageId)
        given(repository.findById(releaseId)).willReturn(Optional.of(release))
        val product = persistedProduct()
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(persistedWorkload(id = workloadId, productId = product.id!!)))
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.update(
                releaseId,
                ReleaseRequest(image = release.image, description = null, commitId = "a-different-sha", workloadId = release.workloadId)
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
        given(deploymentExecutor.attemptDeploy(workload, qa, release.image)).willReturn(null)
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
        assertEquals(ReleaseHistoryAction.PROMOTED, captor.value.action)
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
    fun `promote surfaces the deploy error on the response when the immediate attempt fails`() {
        val workloadId = UUID.randomUUID()
        val dev = persistedStage(order = 1, name = "Dev")
        val qa = persistedStage(order = 2, name = "QA", kubernetesContext = "prod-cluster")
        stubWorkloadStages(workloadId, listOf(dev, qa))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev, qa))

        val releaseId = UUID.randomUUID()
        val release = persistedRelease(id = releaseId, workloadId = workloadId, currentStageId = dev.id!!)
        given(repository.findById(releaseId)).willReturn(Optional.of(release))
        given(repository.save(release)).willReturn(release)
        val product = persistedProduct(name = "Platform")
        val workload = persistedWorkload(id = workloadId, productId = product.id!!, kubernetes = true)
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(workload))
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        given(stageRepository.findById(qa.id!!)).willReturn(Optional.of(qa))
        given(deploymentExecutor.attemptDeploy(workload, qa, release.image)).willReturn("cluster not reachable")
        stubHistorySaveEchoesArgument()

        val result = service.promote(releaseId)

        assertEquals("cluster not reachable", result.deployError)
        assertEquals(qa.id, release.currentStageId)
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
        given(productRepository.findById(productId)).willReturn(Optional.of(persistedProduct(id = productId)))
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
        stubWorkloadVisible(workloadId)

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
        stubWorkloadVisible(workloadId)

        val exception = assertThrows(ResponseStatusException::class.java) { service.promote(releaseId) }

        assertEquals(409, exception.statusCode.value())
    }

    @Test
    fun `promote throws 409 when the current stage's deployment has not finished yet`() {
        val workloadId = UUID.randomUUID()
        val dev = persistedStage(order = 1, name = "Dev")
        val qa = persistedStage(order = 2, name = "QA")
        stubWorkloadStages(workloadId, listOf(dev, qa))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev, qa))

        val releaseId = UUID.randomUUID()
        val release = persistedRelease(id = releaseId, workloadId = workloadId, currentStageId = dev.id!!)
        given(repository.findById(releaseId)).willReturn(Optional.of(release))
        stubWorkloadVisible(workloadId)
        given(releaseHistoryRepository.findFirstByReleaseIdAndStageIdOrderByCreatedAtDesc(releaseId, dev.id!!)).willReturn(
            persistedHistoryEntry(releaseId = releaseId, stageId = dev.id!!, deployedAt = Instant.now(), deploymentFinished = null)
        )

        val exception = assertThrows(ResponseStatusException::class.java) { service.promote(releaseId) }

        assertEquals(409, exception.statusCode.value())
        assertTrue(exception.reason?.contains("has not finished yet") == true)
    }

    @Test
    fun `promote throws 409 when the current stage's deployment failed verification`() {
        val workloadId = UUID.randomUUID()
        val dev = persistedStage(order = 1, name = "Dev")
        val qa = persistedStage(order = 2, name = "QA")
        stubWorkloadStages(workloadId, listOf(dev, qa))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev, qa))

        val releaseId = UUID.randomUUID()
        val release = persistedRelease(id = releaseId, workloadId = workloadId, currentStageId = dev.id!!)
        given(repository.findById(releaseId)).willReturn(Optional.of(release))
        stubWorkloadVisible(workloadId)
        val failed = persistedHistoryEntry(releaseId = releaseId, stageId = dev.id!!, deployedAt = Instant.now())
        failed.deploymentFinished = Instant.now()
        failed.deploymentFailed = true
        failed.deployError = "1 pod(s) restarting (restart count > 0)"
        given(releaseHistoryRepository.findFirstByReleaseIdAndStageIdOrderByCreatedAtDesc(releaseId, dev.id!!)).willReturn(failed)

        val exception = assertThrows(ResponseStatusException::class.java) { service.promote(releaseId) }

        assertEquals(409, exception.statusCode.value())
        assertTrue(exception.reason?.contains("1 pod(s) restarting (restart count > 0)") == true)
    }

    @Test
    fun `promote succeeds once the current stage's deployment finished successfully`() {
        val workloadId = UUID.randomUUID()
        val dev = persistedStage(order = 1, name = "Dev")
        val qa = persistedStage(order = 2, name = "QA")
        stubWorkloadStages(workloadId, listOf(dev, qa))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev, qa))

        val releaseId = UUID.randomUUID()
        val release = persistedRelease(id = releaseId, workloadId = workloadId, currentStageId = dev.id!!)
        given(repository.findById(releaseId)).willReturn(Optional.of(release))
        given(repository.save(release)).willReturn(release)
        val product = persistedProduct()
        val workload = persistedWorkload(id = workloadId, productId = product.id!!)
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(workload))
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        given(stageRepository.findById(qa.id!!)).willReturn(Optional.of(qa))
        given(deploymentExecutor.attemptDeploy(workload, qa, release.image)).willReturn(null)
        stubHistorySaveEchoesArgument()
        given(releaseHistoryRepository.findFirstByReleaseIdAndStageIdOrderByCreatedAtDesc(releaseId, dev.id!!)).willReturn(
            persistedHistoryEntry(releaseId = releaseId, stageId = dev.id!!, deployedAt = Instant.now(), deploymentFinished = Instant.now())
        )

        val result = service.promote(releaseId)

        assertEquals(qa.id, result.currentStage.id)
    }

    @Test
    fun `promote throws 409 when another release is still deploying to the target stage`() {
        val workloadId = UUID.randomUUID()
        val dev = persistedStage(order = 1, name = "Dev")
        val qa = persistedStage(order = 2, name = "QA")
        stubWorkloadStages(workloadId, listOf(dev, qa))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev, qa))

        val releaseId = UUID.randomUUID()
        val release = persistedRelease(id = releaseId, workloadId = workloadId, currentStageId = dev.id!!)
        given(repository.findById(releaseId)).willReturn(Optional.of(release))
        val product = persistedProduct()
        val workload = persistedWorkload(id = workloadId, productId = product.id!!)
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(workload))
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))
        given(releaseHistoryRepository.findFirstByReleaseIdAndStageIdOrderByCreatedAtDesc(releaseId, dev.id!!)).willReturn(
            persistedHistoryEntry(releaseId = releaseId, stageId = dev.id!!, deployedAt = Instant.now(), deploymentFinished = Instant.now())
        )
        given(releaseHistoryRepository.findFirstByWorkloadIdAndStageIdAndDeploymentFinishedIsNullOrderByCreatedAtDesc(workloadId, qa.id!!))
            .willReturn(persistedHistoryEntry(releaseId = UUID.randomUUID(), image = "other:2.0", stageId = qa.id!!, deployedAt = Instant.now()))

        val exception = assertThrows(ResponseStatusException::class.java) { service.promote(releaseId) }

        assertEquals(409, exception.statusCode.value())
        assertTrue(exception.reason?.contains("other:2.0") == true)
        verify(repository, never()).save(any())
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
            persistedHistoryEntry(releaseId = headReleaseId, image = "registry.example.com/head", stageId = prod.id!!)
        )

        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        given(deploymentExecutor.attemptDeploy(workload, prod, target.image)).willReturn(null)
        stubHistorySaveEchoesArgument()

        val result = service.rollback(targetReleaseId)

        assertEquals(prod.id, result.currentStage.id)

        val captor = ArgumentCaptor.forClass(ReleaseHistory::class.java)
        verify(releaseHistoryRepository, org.mockito.Mockito.times(2)).save(captor.capture())
        assertEquals(targetReleaseId, captor.value.releaseId)
        assertEquals(prod.id, captor.value.stageId)
        assertEquals(ReleaseHistoryAction.ROLLED_BACK, captor.value.action)
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
        stubWorkloadVisible(workloadId)
        given(stageRepository.findById(prod.id!!)).willReturn(Optional.of(prod))
        given(repository.findByWorkloadIdAndCurrentStageId(workloadId, prod.id!!)).willReturn(listOf(release))
        given(
            releaseHistoryRepository.findFirstByStageIdAndReleaseIdInOrderByCreatedAtDesc(prod.id!!, listOf(releaseId))
        ).willReturn(
            persistedHistoryEntry(releaseId = releaseId, image = release.image, stageId = prod.id!!)
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
        given(deploymentExecutor.attemptDeploy(workload, dev, release.image)).willReturn(null)
        stubHistorySaveEchoesArgument()

        val result = service.redeploy(releaseId, RedeployRequest(stageId = dev.id!!))

        assertEquals(prod.id, result.currentStage.id)
        assertEquals(prod.id, release.currentStageId)
        verify(repository, never()).save(any())

        val captor = ArgumentCaptor.forClass(ReleaseHistory::class.java)
        verify(releaseHistoryRepository, org.mockito.Mockito.times(2)).save(captor.capture())
        assertEquals(dev.id, captor.value.stageId)
        assertEquals(ReleaseHistoryAction.REDEPLOYED, captor.value.action)
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
            persistedHistoryEntry(releaseId = releaseId, image = release.image, stageId = prod.id!!)
        )
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        given(deploymentExecutor.attemptDeploy(workload, prod, release.image)).willReturn(null)
        stubHistorySaveEchoesArgument()

        val result = service.redeploy(releaseId, RedeployRequest(stageId = prod.id!!))

        assertEquals(prod.id, result.currentStage.id)
        val captor = ArgumentCaptor.forClass(ReleaseHistory::class.java)
        verify(releaseHistoryRepository, org.mockito.Mockito.times(2)).save(captor.capture())
        assertEquals(prod.id, captor.value.stageId)
        assertEquals(ReleaseHistoryAction.REDEPLOYED, captor.value.action)
    }

    @Test
    fun `redeploy to the current stage is unaffected by a previously failed deployment there`() {
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
        val failedEntry = persistedHistoryEntry(releaseId = releaseId, image = release.image, stageId = prod.id!!, deployedAt = Instant.now())
        failedEntry.deploymentFinished = Instant.now()
        failedEntry.deploymentFailed = true
        given(
            releaseHistoryRepository.findFirstByStageIdAndReleaseIdInOrderByCreatedAtDesc(prod.id!!, listOf(releaseId))
        ).willReturn(failedEntry)
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        given(deploymentExecutor.attemptDeploy(workload, prod, release.image)).willReturn(null)
        stubHistorySaveEchoesArgument()

        val result = service.redeploy(releaseId, RedeployRequest(stageId = prod.id!!))

        assertEquals(prod.id, result.currentStage.id)
        verify(releaseHistoryRepository, never()).findFirstByReleaseIdAndStageIdOrderByCreatedAtDesc(releaseId, prod.id!!)
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
        stubWorkloadVisible(workloadId)
        given(repository.findByWorkloadIdAndCurrentStageId(workloadId, prod.id!!)).willReturn(
            listOf(release, persistedRelease(id = headReleaseId, workloadId = workloadId, currentStageId = prod.id!!))
        )
        given(
            releaseHistoryRepository.findFirstByStageIdAndReleaseIdInOrderByCreatedAtDesc(
                prod.id!!, listOf(releaseId, headReleaseId)
            )
        ).willReturn(
            persistedHistoryEntry(releaseId = headReleaseId, image = "registry.example.com/head", stageId = prod.id!!)
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
        stubWorkloadVisible(workloadId)

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
        stubWorkloadVisible(workloadId)

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.redeploy(releaseId, RedeployRequest(stageId = UUID.randomUUID()))
        }

        assertEquals(400, exception.statusCode.value())
    }

    @Test
    fun `toResponse exposes redeployableStages up to and including the current stage when head`() {
        val workloadId = UUID.randomUUID()
        stubWorkloadVisible(workloadId)
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
            persistedHistoryEntry(releaseId = releaseId, image = release.image, stageId = qa.id!!)
        )

        val result = service.findById(releaseId)

        assertEquals(listOf(dev.id, qa.id), result.redeployableStages.map { it.id })
    }

    @Test
    fun `toResponse excludes the current stage from redeployableStages when not head`() {
        val workloadId = UUID.randomUUID()
        stubWorkloadVisible(workloadId)
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
            persistedHistoryEntry(releaseId = headReleaseId, image = "registry.example.com/head", stageId = qa.id!!)
        )

        val result = service.findById(releaseId)

        assertEquals(listOf(dev.id), result.redeployableStages.map { it.id })
    }

    @Test
    fun `toResponse reports canRollback false when the release is head`() {
        val workloadId = UUID.randomUUID()
        stubWorkloadVisible(workloadId)
        val prod = persistedStage(order = 1, name = "Prod")
        val releaseId = UUID.randomUUID()
        val release = persistedRelease(id = releaseId, workloadId = workloadId, currentStageId = prod.id!!)
        given(repository.findById(releaseId)).willReturn(Optional.of(release))
        given(stageRepository.findById(prod.id!!)).willReturn(Optional.of(prod))
        given(repository.findByWorkloadIdAndCurrentStageId(workloadId, prod.id!!)).willReturn(listOf(release))
        given(
            releaseHistoryRepository.findFirstByStageIdAndReleaseIdInOrderByCreatedAtDesc(prod.id!!, listOf(releaseId))
        ).willReturn(
            persistedHistoryEntry(releaseId = releaseId, image = release.image, stageId = prod.id!!)
        )

        val result = service.findById(releaseId)

        assertFalse(result.canRollback)
    }

    @Test
    fun `toResponse reports canPromote false at the final stage`() {
        val workloadId = UUID.randomUUID()
        stubWorkloadVisible(workloadId)
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
        stubWorkloadVisible(workloadId)
        val dev = persistedStage(order = 1, name = "Dev")
        stubWorkloadStages(workloadId, listOf(dev))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(dev))
        given(stageRepository.findById(dev.id!!)).willReturn(Optional.of(dev))

        val releaseId = UUID.randomUUID()
        val release = persistedRelease(id = releaseId, workloadId = workloadId, currentStageId = dev.id!!)
        given(repository.findById(releaseId)).willReturn(Optional.of(release))

        val deployedAt = Instant.now()
        given(releaseHistoryRepository.findTopByReleaseIdAndDeployedAtIsNotNullOrderByDeployedAtDesc(releaseId)).willReturn(
            persistedHistoryEntry(releaseId = releaseId, image = release.image, stageId = dev.id!!, deployedAt = deployedAt)
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
        val oldProduct = persistedProduct()
        given(workloadRepository.findById(oldWorkloadId)).willReturn(Optional.of(persistedWorkload(id = oldWorkloadId, productId = oldProduct.id!!)))
        given(productRepository.findById(oldProduct.id!!)).willReturn(Optional.of(oldProduct))
        given(stageRepository.findById(oldStage.id!!)).willReturn(Optional.of(oldStage))
        val product = persistedProduct()
        given(workloadRepository.findById(newWorkloadId)).willReturn(Optional.of(persistedWorkload(id = newWorkloadId, productId = product.id!!)))
        given(productRepository.findById(product.id!!)).willReturn(Optional.of(product))

        stubWorkloadStages(newWorkloadId, listOf(newStage))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(newStage))
        given(stageRepository.findById(newStage.id!!)).willReturn(Optional.of(newStage))
        stubHistorySaveEchoesArgument()

        val result = service.update(
            releaseId,
            ReleaseRequest(image = release.image, description = null, commitId = "no-id", workloadId = newWorkloadId)
        )

        assertEquals(newStage.id, release.currentStageId)
        assertEquals(newStage.id, result.currentStage.id)
        // Saved twice: once to create the row, once more after the (non-kubernetes, so
        // no-op-success) immediate deploy sets deployedAt.
        verify(releaseHistoryRepository, org.mockito.Mockito.times(2)).save(any())
    }

    @Test
    fun `update to the same workload does not record history`() {
        val workloadId = UUID.randomUUID()
        stubWorkloadVisible(workloadId)
        val stage = persistedStage(order = 1, name = "Dev")

        val releaseId = UUID.randomUUID()
        val release = persistedRelease(id = releaseId, workloadId = workloadId, currentStageId = stage.id!!)
        given(repository.findById(releaseId)).willReturn(Optional.of(release))
        given(repository.save(release)).willReturn(release)
        given(currentUser.currentAuditor).willReturn(Optional.of(UUID.randomUUID()))
        stubWorkloadStages(workloadId, listOf(stage))
        given(stageRepository.findAll(Sort.by("order"))).willReturn(listOf(stage))
        given(stageRepository.findById(stage.id!!)).willReturn(Optional.of(stage))

        service.update(releaseId, ReleaseRequest(image = release.image, description = "updated", commitId = "no-id", workloadId = workloadId))

        verify(releaseHistoryRepository, never()).save(any())
    }

    @Test
    fun `history returns entries mapped with resolved stage info and deployedAt`() {
        val workloadId = UUID.randomUUID()
        stubWorkloadVisible(workloadId)
        val stage = persistedStage(order = 1, name = "Dev")
        val releaseId = UUID.randomUUID()
        given(repository.findById(releaseId)).willReturn(
            Optional.of(persistedRelease(id = releaseId, workloadId = workloadId, currentStageId = stage.id!!))
        )
        val deployedAt = Instant.now()
        val entry = persistedHistoryEntry(
            releaseId = releaseId,
            image = "registry.example.com/app:1.0.0",
            stageId = stage.id!!,
            stageName = stage.name,
            deployedAt = deployedAt,
        )
        given(releaseHistoryRepository.findByReleaseIdOrderByCreatedAtDesc(releaseId)).willReturn(listOf(entry))
        given(stageRepository.findAllById(setOf(stage.id!!))).willReturn(listOf(stage))

        val result = service.history(releaseId)

        assertEquals(1, result.size)
        assertEquals(stage.name, result[0].stage.name)
        assertEquals(entry.image, result[0].image)
        assertEquals(deployedAt, result[0].deployedAt)
    }

    @Test
    fun `historyOverview reads product, workload and stage directly off the row`() {
        val stage = persistedStage(order = 1, name = "Prod")
        val product = persistedProduct(name = "Platform")
        val workload = persistedWorkload(productId = product.id!!)
        val entry = persistedHistoryEntry(
            releaseId = UUID.randomUUID(),
            workloadId = workload.id,
            productId = product.id!!,
            productName = product.name,
            workloadName = workload.name,
            stageId = stage.id!!,
            stageName = stage.name,
            action = ReleaseHistoryAction.PROMOTED,
        )
        given(releaseHistoryRepository.findAll(any<Specification<ReleaseHistory>>(), any<Pageable>())).willReturn(PageImpl(listOf(entry)))
        given(stageRepository.findAllById(setOf(stage.id!!))).willReturn(listOf(stage))

        val result = service.historyOverview()

        assertEquals(1, result.content.size)
        assertEquals(1L, result.totalElements)
        assertEquals(entry.releaseId, result.content[0].releaseId)
        assertEquals(ReleaseHistoryAction.PROMOTED, result.content[0].action)
        assertEquals(workload.id, result.content[0].workloadId)
        assertEquals(workload.name, result.content[0].workloadName)
        assertEquals(product.id, result.content[0].productId)
        assertEquals(product.name, result.content[0].productName)
        assertEquals(stage.name, result.content[0].stage.name)
    }

    @Test
    fun `historyOverview keeps the snapshotted product and workload names once those entities are deleted`() {
        val stage = persistedStage(order = 1, name = "Prod")
        val entry = persistedHistoryEntry(
            releaseId = UUID.randomUUID(),
            workloadId = null,
            productName = "Platform",
            workloadName = "checkout",
            stageId = stage.id!!,
            stageName = stage.name,
            action = ReleaseHistoryAction.ROLLED_BACK,
        )
        given(releaseHistoryRepository.findAll(any<Specification<ReleaseHistory>>(), any<Pageable>())).willReturn(PageImpl(listOf(entry)))
        given(stageRepository.findAllById(setOf(stage.id!!))).willReturn(listOf(stage))

        val result = service.historyOverview()

        assertEquals(1, result.content.size)
        assertEquals(ReleaseHistoryAction.ROLLED_BACK, result.content[0].action)
        assertNull(result.content[0].workloadId)
        assertEquals("checkout", result.content[0].workloadName)
        assertEquals(entry.productId, result.content[0].productId)
        assertEquals("Platform", result.content[0].productName)
        assertEquals(stage.name, result.content[0].stage.name)
    }

    @Test
    fun `historyOverview honors an explicit sort param mapped to its entity property`() {
        val stage = persistedStage(order = 1, name = "Prod")
        val entry = persistedHistoryEntry(releaseId = UUID.randomUUID(), stageId = stage.id!!, stageName = stage.name)
        given(releaseHistoryRepository.findAll(any<Specification<ReleaseHistory>>(), eq(PageRequest.of(0, 25, Sort.by(Sort.Direction.ASC, "productName")))))
            .willReturn(PageImpl(listOf(entry)))
        given(stageRepository.findAllById(setOf(stage.id!!))).willReturn(listOf(stage))

        val result = service.historyOverview("productName,asc")

        assertEquals(1, result.content.size)
        assertEquals(entry.releaseId, result.content[0].releaseId)
    }

    @Test
    fun `historyOverview rejects an unknown sort key`() {
        val exception = assertThrows(ResponseStatusException::class.java) { service.historyOverview("bogus,asc") }

        assertEquals(400, exception.statusCode.value())
    }

    @Test
    fun `findAll sorts by createdAt descending by default`() {
        val stage = persistedStage(order = 1)
        given(stageRepository.findById(stage.id!!)).willReturn(Optional.of(stage))
        val workloadId = UUID.randomUUID()
        given(workloadRepository.findAllById(setOf(workloadId))).willReturn(listOf(persistedWorkload(id = workloadId)))
        val older = persistedRelease(workloadId = workloadId, currentStageId = stage.id!!, image = "app:1").apply { createdAt = Instant.now().minusSeconds(60) }
        val newer = persistedRelease(workloadId = workloadId, currentStageId = stage.id!!, image = "app:2").apply { createdAt = Instant.now() }
        given(repository.findAll()).willReturn(listOf(older, newer))

        val result = service.findAll()

        assertEquals(listOf("app:2", "app:1"), result.map { it.image })
    }

    @Test
    fun `findAll honors an explicit sort param`() {
        val stage = persistedStage(order = 1)
        given(stageRepository.findById(stage.id!!)).willReturn(Optional.of(stage))
        val workloadId = UUID.randomUUID()
        given(workloadRepository.findAllById(setOf(workloadId))).willReturn(listOf(persistedWorkload(id = workloadId)))
        val b = persistedRelease(workloadId = workloadId, currentStageId = stage.id!!, image = "b-app:1")
        val a = persistedRelease(workloadId = workloadId, currentStageId = stage.id!!, image = "a-app:1")
        given(repository.findAll()).willReturn(listOf(b, a))

        val result = service.findAll("image,asc")

        assertEquals(listOf("a-app:1", "b-app:1"), result.map { it.image })
    }

    @Test
    fun `findAll rejects an unknown sort key`() {
        given(repository.findAll()).willReturn(emptyList())

        val exception = assertThrows(ResponseStatusException::class.java) { service.findAll("bogus,asc") }

        assertEquals(400, exception.statusCode.value())
    }

    @Test
    fun `history throws 404 when release is missing`() {
        val releaseId = UUID.randomUUID()
        given(repository.findById(releaseId)).willReturn(Optional.empty())

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
