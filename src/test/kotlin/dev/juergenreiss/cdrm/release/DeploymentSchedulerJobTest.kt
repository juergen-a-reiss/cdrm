package dev.juergenreiss.cdrm.release

import dev.juergenreiss.cdrm.notification.ReleaseHistoryNotificationKind
import dev.juergenreiss.cdrm.notification.ReleaseHistoryRecordedEvent
import dev.juergenreiss.cdrm.product.Product
import dev.juergenreiss.cdrm.product.ProductRepository
import dev.juergenreiss.cdrm.product.ProductStage
import dev.juergenreiss.cdrm.product.ProductStageRepository
import dev.juergenreiss.cdrm.stage.DeploymentPolicy
import dev.juergenreiss.cdrm.stage.Stage
import dev.juergenreiss.cdrm.stage.StageRepository
import dev.juergenreiss.cdrm.workload.Workload
import dev.juergenreiss.cdrm.workload.WorkloadRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class DeploymentSchedulerJobTest {

    @Mock
    private lateinit var releaseHistoryRepository: ReleaseHistoryRepository

    @Mock
    private lateinit var releaseRepository: ReleaseRepository

    @Mock
    private lateinit var workloadRepository: WorkloadRepository

    @Mock
    private lateinit var stageRepository: StageRepository

    @Mock
    private lateinit var productRepository: ProductRepository

    @Mock
    private lateinit var productStageRepository: ProductStageRepository

    @Mock
    private lateinit var deploymentExecutor: DeploymentExecutor

    @Mock
    private lateinit var eventPublisher: org.springframework.context.ApplicationEventPublisher

    private lateinit var job: DeploymentSchedulerJob

    @BeforeEach
    fun setUp() {
        job = DeploymentSchedulerJob(
            releaseHistoryRepository, releaseRepository, workloadRepository, stageRepository, productRepository, productStageRepository,
            deploymentExecutor, eventPublisher,
        )
    }

    private fun persistedWorkload(id: UUID, productId: UUID) = Workload(
        id = id,
        name = "workload-$id",
        productId = productId,
        description = null,
        kubernetes = false,
        pipeline = "--",
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    private fun persistedRelease(id: UUID, workloadId: UUID) = Release(
        id = id,
        image = "registry.example.com/app:1.0.0",
        description = null,
        workloadId = workloadId,
        currentStageId = UUID.randomUUID(),
        commitId = "no-id",
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    private fun persistedProduct(id: UUID) = Product(
        id = id,
        name = "product-$id",
        description = null,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    private fun persistedStage(id: UUID, policy: DeploymentPolicy) = Stage(
        id = id,
        pipeline = "pipeline",
        name = "stage-$id",
        description = null,
        order = 1,
        deploymentPolicy = policy,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    private fun persistedHistoryEntry(
        releaseId: UUID,
        stageId: UUID,
        deployedAt: Instant? = null,
        createdAt: Instant = Instant.now(),
        gitOpsManaged: Boolean = false,
        gitopsRetryCount: Int = 0,
    ) = ReleaseHistory(
        releaseId = releaseId,
        productId = UUID.randomUUID(),
        productName = "product",
        workloadName = "workload",
        image = "registry.example.com/app:1.0.0",
        stageId = stageId,
        stageName = "stage",
        deployedAt = deployedAt,
        createdAt = createdAt,
        gitOpsManaged = gitOpsManaged,
        gitopsRetryCount = gitopsRetryCount,
        createdBy = UUID.randomUUID(),
    )

    @Test
    fun `deploys a scheduled entry once its cron time has passed`() {
        val releaseId = UUID.randomUUID()
        val workloadId = UUID.randomUUID()
        val productId = UUID.randomUUID()
        val stageId = UUID.randomUUID()

        val pending = persistedHistoryEntry(releaseId = releaseId, stageId = stageId, createdAt = Instant.now().minus(2, ChronoUnit.DAYS))
        given(releaseHistoryRepository.findPendingForUpdate()).willReturn(listOf(pending))
        given(releaseRepository.findById(releaseId)).willReturn(Optional.of(persistedRelease(releaseId, workloadId)))
        val workload = persistedWorkload(workloadId, productId)
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(workload))
        val product = persistedProduct(productId)
        given(productRepository.findById(productId)).willReturn(Optional.of(product))
        val stage = persistedStage(stageId, DeploymentPolicy.SCHEDULED)
        given(stageRepository.findById(stageId)).willReturn(Optional.of(stage))
        given(productStageRepository.findByProductIdAndStageId(productId, stageId)).willReturn(
            ProductStage(productId = productId, stageId = stageId, deploymentCron = "0 0 0 * * *")
        )
        given(deploymentExecutor.attemptDeploy(workload, stage, product, pending.image)).willReturn(DeployAttemptResult.Success)

        job.processPendingDeployments()

        assertNotNull(pending.deployedAt)
        verify(releaseHistoryRepository).save(pending)

        val eventCaptor = org.mockito.ArgumentCaptor.forClass(ReleaseHistoryRecordedEvent::class.java)
        verify(eventPublisher).publishEvent(eventCaptor.capture())
        assertEquals(ReleaseHistoryNotificationKind.DEPLOYED, eventCaptor.value.kind)
    }

    @Test
    fun `leaves a scheduled entry pending when its cron time has not passed yet`() {
        val releaseId = UUID.randomUUID()
        val workloadId = UUID.randomUUID()
        val productId = UUID.randomUUID()
        val stageId = UUID.randomUUID()

        val pending = persistedHistoryEntry(releaseId = releaseId, stageId = stageId, createdAt = Instant.now())
        given(releaseHistoryRepository.findPendingForUpdate()).willReturn(listOf(pending))
        given(releaseRepository.findById(releaseId)).willReturn(Optional.of(persistedRelease(releaseId, workloadId)))
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(persistedWorkload(workloadId, productId)))
        given(productRepository.findById(productId)).willReturn(Optional.of(persistedProduct(productId)))
        given(stageRepository.findById(stageId)).willReturn(Optional.of(persistedStage(stageId, DeploymentPolicy.SCHEDULED)))
        // Cron fires once a year on Jan 1st — the next occurrence is always well in the future.
        given(productStageRepository.findByProductIdAndStageId(productId, stageId)).willReturn(
            ProductStage(productId = productId, stageId = stageId, deploymentCron = "0 0 0 1 1 *")
        )

        job.processPendingDeployments()

        assertNull(pending.deployedAt)
        verify(releaseHistoryRepository, never()).save(pending)
    }

    @Test
    fun `deploys an immediate-policy entry unconditionally`() {
        val releaseId = UUID.randomUUID()
        val workloadId = UUID.randomUUID()
        val productId = UUID.randomUUID()
        val stageId = UUID.randomUUID()

        val pending = persistedHistoryEntry(releaseId = releaseId, stageId = stageId, createdAt = Instant.now())
        given(releaseHistoryRepository.findPendingForUpdate()).willReturn(listOf(pending))
        given(releaseRepository.findById(releaseId)).willReturn(Optional.of(persistedRelease(releaseId, workloadId)))
        val workload = persistedWorkload(workloadId, productId)
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(workload))
        val product = persistedProduct(productId)
        given(productRepository.findById(productId)).willReturn(Optional.of(product))
        val stage = persistedStage(stageId, DeploymentPolicy.IMMEDIATE)
        given(stageRepository.findById(stageId)).willReturn(Optional.of(stage))
        given(deploymentExecutor.attemptDeploy(workload, stage, product, pending.image)).willReturn(DeployAttemptResult.Success)

        job.processPendingDeployments()

        assertNotNull(pending.deployedAt)
        verify(releaseHistoryRepository).save(pending)

        val eventCaptor = org.mockito.ArgumentCaptor.forClass(ReleaseHistoryRecordedEvent::class.java)
        verify(eventPublisher).publishEvent(eventCaptor.capture())
        assertEquals(ReleaseHistoryNotificationKind.DEPLOYED, eventCaptor.value.kind)
    }

    @Test
    fun `publishes GITOPS_PUSHED, not DEPLOYED, when a GitOps push succeeds for a Kubernetes workload still awaiting rollout verification`() {
        val releaseId = UUID.randomUUID()
        val workloadId = UUID.randomUUID()
        val productId = UUID.randomUUID()
        val stageId = UUID.randomUUID()

        val pending = persistedHistoryEntry(releaseId = releaseId, stageId = stageId, createdAt = Instant.now(), gitOpsManaged = true)
        given(releaseHistoryRepository.findPendingForUpdate()).willReturn(listOf(pending))
        given(releaseRepository.findById(releaseId)).willReturn(Optional.of(persistedRelease(releaseId, workloadId)))
        val workload = persistedWorkload(workloadId, productId).apply { kubernetes = true }
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(workload))
        val product = persistedProduct(productId)
        given(productRepository.findById(productId)).willReturn(Optional.of(product))
        val stage = persistedStage(stageId, DeploymentPolicy.IMMEDIATE)
        given(stageRepository.findById(stageId)).willReturn(Optional.of(stage))
        given(deploymentExecutor.attemptDeploy(workload, stage, product, pending.image)).willReturn(DeployAttemptResult.Success)

        job.processPendingDeployments()

        assertNotNull(pending.deployedAt)
        assertNull(pending.deploymentFinished)
        verify(releaseHistoryRepository).save(pending)

        val eventCaptor = org.mockito.ArgumentCaptor.forClass(ReleaseHistoryRecordedEvent::class.java)
        verify(eventPublisher).publishEvent(eventCaptor.capture())
        assertEquals(ReleaseHistoryNotificationKind.GITOPS_PUSHED, eventCaptor.value.kind)
    }

    @Test
    fun `leaves an entry pending and records the failure reason when the deployment attempt fails`() {
        val releaseId = UUID.randomUUID()
        val workloadId = UUID.randomUUID()
        val productId = UUID.randomUUID()
        val stageId = UUID.randomUUID()

        val pending = persistedHistoryEntry(releaseId = releaseId, stageId = stageId, createdAt = Instant.now())
        given(releaseHistoryRepository.findPendingForUpdate()).willReturn(listOf(pending))
        given(releaseRepository.findById(releaseId)).willReturn(Optional.of(persistedRelease(releaseId, workloadId)))
        val workload = persistedWorkload(workloadId, productId)
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(workload))
        val product = persistedProduct(productId)
        given(productRepository.findById(productId)).willReturn(Optional.of(product))
        val stage = persistedStage(stageId, DeploymentPolicy.IMMEDIATE)
        given(stageRepository.findById(stageId)).willReturn(Optional.of(stage))
        given(deploymentExecutor.attemptDeploy(workload, stage, product, pending.image)).willReturn(DeployAttemptResult.Failed("cluster not reachable"))

        job.processPendingDeployments()

        assertNull(pending.deployedAt)
        assertEquals("cluster not reachable", pending.deployError)
        verify(releaseHistoryRepository).save(pending)
        // Still retrying, not the final go/no-go — must not notify on every retry.
        verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `increments the GitOps retry count and sets gitopsError, not deployError, on a GitOps push failure`() {
        val releaseId = UUID.randomUUID()
        val workloadId = UUID.randomUUID()
        val productId = UUID.randomUUID()
        val stageId = UUID.randomUUID()

        val pending = persistedHistoryEntry(releaseId = releaseId, stageId = stageId, createdAt = Instant.now(), gitOpsManaged = true)
        given(releaseHistoryRepository.findPendingForUpdate()).willReturn(listOf(pending))
        given(releaseRepository.findById(releaseId)).willReturn(Optional.of(persistedRelease(releaseId, workloadId)))
        val workload = persistedWorkload(workloadId, productId)
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(workload))
        val product = persistedProduct(productId)
        given(productRepository.findById(productId)).willReturn(Optional.of(product))
        val stage = persistedStage(stageId, DeploymentPolicy.IMMEDIATE)
        given(stageRepository.findById(stageId)).willReturn(Optional.of(stage))
        given(deploymentExecutor.attemptDeploy(workload, stage, product, pending.image)).willReturn(DeployAttemptResult.Failed("git push failed"))

        job.processPendingDeployments()

        assertNull(pending.deployedAt)
        assertEquals(1, pending.gitopsRetryCount)
        assertEquals("git push failed", pending.gitopsError)
        assertNull(pending.deployError)
        assertFalse(pending.deploymentFailed)
        assertNull(pending.deploymentFinished)
        verify(releaseHistoryRepository).save(pending)
        // Still retrying, not the final go/no-go — must not notify on every retry.
        verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `gives up on a GitOps row after the 5th consecutive failed push attempt`() {
        val releaseId = UUID.randomUUID()
        val workloadId = UUID.randomUUID()
        val productId = UUID.randomUUID()
        val stageId = UUID.randomUUID()

        val pending = persistedHistoryEntry(
            releaseId = releaseId, stageId = stageId, createdAt = Instant.now(), gitOpsManaged = true, gitopsRetryCount = 4,
        )
        given(releaseHistoryRepository.findPendingForUpdate()).willReturn(listOf(pending))
        given(releaseRepository.findById(releaseId)).willReturn(Optional.of(persistedRelease(releaseId, workloadId)))
        val workload = persistedWorkload(workloadId, productId)
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(workload))
        val product = persistedProduct(productId)
        given(productRepository.findById(productId)).willReturn(Optional.of(product))
        val stage = persistedStage(stageId, DeploymentPolicy.IMMEDIATE)
        given(stageRepository.findById(stageId)).willReturn(Optional.of(stage))
        given(deploymentExecutor.attemptDeploy(workload, stage, product, pending.image)).willReturn(DeployAttemptResult.Failed("git push failed"))

        job.processPendingDeployments()

        assertEquals(5, pending.gitopsRetryCount)
        assertTrue(pending.deploymentFailed)
        assertNotNull(pending.deploymentFinished)
        assertNull(pending.deployedAt)
        verify(releaseHistoryRepository).save(pending)

        val eventCaptor = org.mockito.ArgumentCaptor.forClass(ReleaseHistoryRecordedEvent::class.java)
        verify(eventPublisher).publishEvent(eventCaptor.capture())
        assertEquals(ReleaseHistoryNotificationKind.DEPLOY_FAILED, eventCaptor.value.kind)
    }

    @Test
    fun `skips silently without spending retry budget when the git lock is busy`() {
        val releaseId = UUID.randomUUID()
        val workloadId = UUID.randomUUID()
        val productId = UUID.randomUUID()
        val stageId = UUID.randomUUID()

        val pending = persistedHistoryEntry(releaseId = releaseId, stageId = stageId, createdAt = Instant.now(), gitOpsManaged = true)
        given(releaseHistoryRepository.findPendingForUpdate()).willReturn(listOf(pending))
        given(releaseRepository.findById(releaseId)).willReturn(Optional.of(persistedRelease(releaseId, workloadId)))
        val workload = persistedWorkload(workloadId, productId)
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(workload))
        val product = persistedProduct(productId)
        given(productRepository.findById(productId)).willReturn(Optional.of(product))
        val stage = persistedStage(stageId, DeploymentPolicy.IMMEDIATE)
        given(stageRepository.findById(stageId)).willReturn(Optional.of(stage))
        given(deploymentExecutor.attemptDeploy(workload, stage, product, pending.image)).willReturn(DeployAttemptResult.GitLockBusy)

        job.processPendingDeployments()

        assertNull(pending.deployedAt)
        assertEquals(0, pending.gitopsRetryCount)
        assertNull(pending.gitopsError)
        verify(releaseHistoryRepository, never()).save(pending)
    }

    @Test
    fun `skips an entry whose release no longer exists`() {
        val releaseId = UUID.randomUUID()
        val pending = persistedHistoryEntry(releaseId = releaseId, stageId = UUID.randomUUID(), createdAt = Instant.now().minus(1, ChronoUnit.DAYS))
        given(releaseHistoryRepository.findPendingForUpdate()).willReturn(listOf(pending))
        given(releaseRepository.findById(releaseId)).willReturn(Optional.empty())

        job.processPendingDeployments()

        assertNull(pending.deployedAt)
        verify(releaseHistoryRepository, never()).save(pending)
    }

    @Test
    fun `does nothing when there are no pending entries`() {
        given(releaseHistoryRepository.findPendingForUpdate()).willReturn(emptyList())

        job.processPendingDeployments()

        verify(releaseRepository, never()).findById(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `skips a scheduled entry with no configured deployment time`() {
        val releaseId = UUID.randomUUID()
        val workloadId = UUID.randomUUID()
        val productId = UUID.randomUUID()
        val stageId = UUID.randomUUID()

        val pending = persistedHistoryEntry(releaseId = releaseId, stageId = stageId, createdAt = Instant.now().minus(1, ChronoUnit.DAYS))
        given(releaseHistoryRepository.findPendingForUpdate()).willReturn(listOf(pending))
        given(releaseRepository.findById(releaseId)).willReturn(Optional.of(persistedRelease(releaseId, workloadId)))
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(persistedWorkload(workloadId, productId)))
        given(productRepository.findById(productId)).willReturn(Optional.of(persistedProduct(productId)))
        given(stageRepository.findById(stageId)).willReturn(Optional.of(persistedStage(stageId, DeploymentPolicy.SCHEDULED)))
        given(productStageRepository.findByProductIdAndStageId(productId, stageId)).willReturn(null)

        job.processPendingDeployments()

        assertNull(pending.deployedAt)
        verify(releaseHistoryRepository, never()).save(pending)
    }
}
