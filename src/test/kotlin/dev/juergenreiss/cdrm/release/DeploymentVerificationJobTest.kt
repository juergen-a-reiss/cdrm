package dev.juergenreiss.cdrm.release

import dev.juergenreiss.cdrm.kubernetes.KubernetesDeploymentClient
import dev.juergenreiss.cdrm.kubernetes.RolloutStatus
import dev.juergenreiss.cdrm.stage.DeploymentPolicy
import dev.juergenreiss.cdrm.stage.Stage
import dev.juergenreiss.cdrm.stage.StageRepository
import dev.juergenreiss.cdrm.workload.KubernetesKind
import dev.juergenreiss.cdrm.workload.Workload
import dev.juergenreiss.cdrm.workload.WorkloadRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
class DeploymentVerificationJobTest {

    @Mock
    private lateinit var releaseHistoryRepository: ReleaseHistoryRepository

    @Mock
    private lateinit var releaseRepository: ReleaseRepository

    @Mock
    private lateinit var workloadRepository: WorkloadRepository

    @Mock
    private lateinit var stageRepository: StageRepository

    @Mock
    private lateinit var kubernetesDeploymentClient: KubernetesDeploymentClient

    private lateinit var job: DeploymentVerificationJob

    @BeforeEach
    fun setUp() {
        job = DeploymentVerificationJob(
            releaseHistoryRepository, releaseRepository, workloadRepository, stageRepository,
            kubernetesDeploymentClient, SimpleMeterRegistry(),
        )
    }

    private fun persistedWorkload(id: UUID, productId: UUID, kubernetes: Boolean) = Workload(
        id = id,
        name = "workload-$id",
        productId = productId,
        description = null,
        kubernetes = kubernetes,
        kubernetesKind = if (kubernetes) KubernetesKind.DEPLOYMENT else null,
        kubernetesNameSpace = if (kubernetes) "platform" else null,
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

    private fun persistedStage(id: UUID, context: String? = "my-context") = Stage(
        id = id,
        pipeline = "pipeline",
        name = "stage-$id",
        description = null,
        order = 1,
        deploymentPolicy = DeploymentPolicy.IMMEDIATE,
        kubernetesContext = context,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    private fun awaitingEntry(releaseId: UUID, stageId: UUID, deployedAt: Instant) = ReleaseHistory(
        releaseId = releaseId,
        workloadName = "workload",
        productId = UUID.randomUUID(),
        productName = "product",
        image = "registry.example.com/app:1.0.0",
        stageId = stageId,
        stageName = "stage",
        deployedAt = deployedAt,
        createdBy = UUID.randomUUID(),
    )

    @Test
    fun `marks a Kubernetes deploy finished once the rollout is confirmed`() {
        val releaseId = UUID.randomUUID()
        val workloadId = UUID.randomUUID()
        val productId = UUID.randomUUID()
        val stageId = UUID.randomUUID()

        val entry = awaitingEntry(releaseId, stageId, deployedAt = Instant.now())
        given(releaseHistoryRepository.findAwaitingVerification()).willReturn(listOf(entry))
        given(releaseRepository.findById(releaseId)).willReturn(Optional.of(persistedRelease(releaseId, workloadId)))
        val workload = persistedWorkload(workloadId, productId, kubernetes = true)
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(workload))
        given(stageRepository.findById(stageId)).willReturn(Optional.of(persistedStage(stageId)))
        given(kubernetesDeploymentClient.checkRollout("my-context", "platform", KubernetesKind.DEPLOYMENT, workload.name, entry.image))
            .willReturn(RolloutStatus(true, "rollout complete"))

        job.verifyPendingDeployments()

        assertNotNull(entry.deploymentFinished)
        assertFalse(entry.deploymentFailed)
        assertNull(entry.deployError)
        verify(releaseHistoryRepository).save(entry)
    }

    @Test
    fun `leaves a not-yet-ready rollout pending within the grace period`() {
        val releaseId = UUID.randomUUID()
        val workloadId = UUID.randomUUID()
        val productId = UUID.randomUUID()
        val stageId = UUID.randomUUID()

        val entry = awaitingEntry(releaseId, stageId, deployedAt = Instant.now().minus(1, ChronoUnit.MINUTES))
        given(releaseHistoryRepository.findAwaitingVerification()).willReturn(listOf(entry))
        given(releaseRepository.findById(releaseId)).willReturn(Optional.of(persistedRelease(releaseId, workloadId)))
        val workload = persistedWorkload(workloadId, productId, kubernetes = true)
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(workload))
        given(stageRepository.findById(stageId)).willReturn(Optional.of(persistedStage(stageId)))
        given(kubernetesDeploymentClient.checkRollout("my-context", "platform", KubernetesKind.DEPLOYMENT, workload.name, entry.image))
            .willReturn(RolloutStatus(false, "1/2 pods present"))

        job.verifyPendingDeployments()

        assertNull(entry.deploymentFinished)
        assertFalse(entry.deploymentFailed)
        verify(releaseHistoryRepository, never()).save(entry)
    }

    @Test
    fun `marks the deployment failed once the grace period elapses without a ready rollout`() {
        val releaseId = UUID.randomUUID()
        val workloadId = UUID.randomUUID()
        val productId = UUID.randomUUID()
        val stageId = UUID.randomUUID()

        val entry = awaitingEntry(releaseId, stageId, deployedAt = Instant.now().minus(6, ChronoUnit.MINUTES))
        given(releaseHistoryRepository.findAwaitingVerification()).willReturn(listOf(entry))
        given(releaseRepository.findById(releaseId)).willReturn(Optional.of(persistedRelease(releaseId, workloadId)))
        val workload = persistedWorkload(workloadId, productId, kubernetes = true)
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(workload))
        given(stageRepository.findById(stageId)).willReturn(Optional.of(persistedStage(stageId)))
        given(kubernetesDeploymentClient.checkRollout("my-context", "platform", KubernetesKind.DEPLOYMENT, workload.name, entry.image))
            .willReturn(RolloutStatus(false, "1 pod(s) restarting (restart count > 0)"))

        job.verifyPendingDeployments()

        assertNotNull(entry.deploymentFinished)
        assertTrue(entry.deploymentFailed)
        assertEquals("1 pod(s) restarting (restart count > 0)", entry.deployError)
        verify(releaseHistoryRepository).save(entry)
    }

    @Test
    fun `marks a non-Kubernetes workload finished immediately without calling the cluster`() {
        val releaseId = UUID.randomUUID()
        val workloadId = UUID.randomUUID()
        val productId = UUID.randomUUID()
        val stageId = UUID.randomUUID()

        val entry = awaitingEntry(releaseId, stageId, deployedAt = Instant.now())
        given(releaseHistoryRepository.findAwaitingVerification()).willReturn(listOf(entry))
        given(releaseRepository.findById(releaseId)).willReturn(Optional.of(persistedRelease(releaseId, workloadId)))
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(persistedWorkload(workloadId, productId, kubernetes = false)))

        job.verifyPendingDeployments()

        assertEquals(entry.deployedAt, entry.deploymentFinished)
        verify(releaseHistoryRepository).save(entry)
        verify(stageRepository, never()).findById(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `skips an entry whose release no longer exists`() {
        val releaseId = UUID.randomUUID()
        val entry = awaitingEntry(releaseId, UUID.randomUUID(), deployedAt = Instant.now())
        given(releaseHistoryRepository.findAwaitingVerification()).willReturn(listOf(entry))
        given(releaseRepository.findById(releaseId)).willReturn(Optional.empty())

        job.verifyPendingDeployments()

        assertNull(entry.deploymentFinished)
        verify(releaseHistoryRepository, never()).save(entry)
    }

    @Test
    fun `does nothing when there are no rows awaiting verification`() {
        given(releaseHistoryRepository.findAwaitingVerification()).willReturn(emptyList())

        job.verifyPendingDeployments()

        verify(releaseRepository, never()).findById(org.mockito.ArgumentMatchers.any())
    }
}
