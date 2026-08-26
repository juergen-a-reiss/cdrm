package dev.juergenreiss.cdrm.release

import dev.juergenreiss.cdrm.kubernetes.KubernetesDeploymentClient
import dev.juergenreiss.cdrm.kubernetes.KubernetesDeploymentException
import dev.juergenreiss.cdrm.stage.DeploymentPolicy
import dev.juergenreiss.cdrm.stage.Stage
import dev.juergenreiss.cdrm.workload.KubernetesKind
import dev.juergenreiss.cdrm.workload.Workload
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.willThrow
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Instant
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class DeploymentExecutorTest {

    @Mock
    private lateinit var kubernetesDeploymentClient: KubernetesDeploymentClient

    private lateinit var executor: DeploymentExecutor

    @BeforeEach
    fun setUp() {
        executor = DeploymentExecutor(kubernetesDeploymentClient)
    }

    private fun stage(kubernetesContext: String? = "my-context", namespacePrefix: String? = null) = Stage(
        id = UUID.randomUUID(),
        name = "Prod",
        description = null,
        order = 1,
        deploymentPolicy = DeploymentPolicy.IMMEDIATE,
        kubernetesContext = kubernetesContext,
        namespacePrefix = namespacePrefix,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    private fun workload(
        kubernetes: Boolean,
        kubernetesKind: KubernetesKind? = KubernetesKind.DEPLOYMENT,
        kubernetesNameSpace: String? = "platform",
    ) = Workload(
        id = UUID.randomUUID(),
        name = "workload",
        productId = UUID.randomUUID(),
        description = null,
        kubernetes = kubernetes,
        kubernetesKind = kubernetesKind,
        kubernetesNameSpace = kubernetesNameSpace,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    @Test
    fun `returns true without calling the client for a non-kubernetes workload`() {
        val result = executor.attemptDeploy(workload(kubernetes = false), stage(), "image:1.0")

        assertTrue(result)
        verifyNoInteractions(kubernetesDeploymentClient)
    }

    @Test
    fun `returns false when the stage has no kubernetes context configured`() {
        val result = executor.attemptDeploy(workload(kubernetes = true), stage(kubernetesContext = null), "image:1.0")

        assertFalse(result)
        verifyNoInteractions(kubernetesDeploymentClient)
    }

    @Test
    fun `returns true and patches the image on success`() {
        val stage = stage(kubernetesContext = "my-context")
        val workload = workload(kubernetes = true)

        val result = executor.attemptDeploy(workload, stage, "image:1.0")

        assertTrue(result)
        verify(kubernetesDeploymentClient).patchImage("my-context", "platform", KubernetesKind.DEPLOYMENT, "workload", "image:1.0")
    }

    @Test
    fun `prepends the stage's namespace prefix when deploying`() {
        val stage = stage(kubernetesContext = "minikube", namespacePrefix = "dev-")
        val workload = workload(kubernetes = true)

        val result = executor.attemptDeploy(workload, stage, "image:1.0")

        assertTrue(result)
        verify(kubernetesDeploymentClient).patchImage("minikube", "dev-platform", KubernetesKind.DEPLOYMENT, "workload", "image:1.0")
    }

    @Test
    fun `returns false when the client throws`() {
        val stage = stage(kubernetesContext = "my-context")
        val workload = workload(kubernetes = true)
        willThrow(KubernetesDeploymentException("boom")).given(kubernetesDeploymentClient)
            .patchImage("my-context", "platform", KubernetesKind.DEPLOYMENT, "workload", "image:1.0")

        val result = executor.attemptDeploy(workload, stage, "image:1.0")

        assertFalse(result)
    }
}
