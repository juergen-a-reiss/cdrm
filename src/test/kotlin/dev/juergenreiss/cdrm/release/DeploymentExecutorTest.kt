package dev.juergenreiss.cdrm.release

import dev.juergenreiss.cdrm.gitops.GitCommitClient
import dev.juergenreiss.cdrm.gitops.GitOpsResolver
import dev.juergenreiss.cdrm.gitops.GitOpsTarget
import dev.juergenreiss.cdrm.kubernetes.KubernetesDeploymentClient
import dev.juergenreiss.cdrm.kubernetes.KubernetesDeploymentException
import dev.juergenreiss.cdrm.stage.DeploymentPolicy
import dev.juergenreiss.cdrm.stage.Stage
import dev.juergenreiss.cdrm.workload.KubernetesKind
import dev.juergenreiss.cdrm.workload.Workload
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
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

    @Mock
    private lateinit var gitOpsResolver: GitOpsResolver

    @Mock
    private lateinit var gitCommitClient: GitCommitClient

    private val meterRegistry = SimpleMeterRegistry()

    private lateinit var executor: DeploymentExecutor

    @BeforeEach
    fun setUp() {
        executor = DeploymentExecutor(kubernetesDeploymentClient, gitOpsResolver, gitCommitClient, meterRegistry)
    }

    private fun stage(kubernetesContext: String? = "my-context", namespacePrefix: String? = null) = Stage(
        id = UUID.randomUUID(),
        pipeline = "pipeline",
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
        pipeline: String = "--",
    ) = Workload(
        id = UUID.randomUUID(),
        name = "workload",
        productId = UUID.randomUUID(),
        description = null,
        kubernetes = kubernetes,
        kubernetesKind = kubernetesKind,
        kubernetesNameSpace = kubernetesNameSpace,
        pipeline = pipeline,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    @Test
    fun `returns null without calling the client for a non-kubernetes workload`() {
        val result = executor.attemptDeploy(workload(kubernetes = false), stage(), "image:1.0")

        assertNull(result)
        verifyNoInteractions(kubernetesDeploymentClient)
    }

    @Test
    fun `returns a reason and increments the failure counter when the stage has no kubernetes context configured`() {
        val result = executor.attemptDeploy(workload(kubernetes = true), stage(kubernetesContext = null), "image:1.0")

        assertNotNull(result)
        verifyNoInteractions(kubernetesDeploymentClient)
        assertEquals(1.0, meterRegistry.get("cdrm.deploy.failed").counter().count())
    }

    @Test
    fun `returns null and patches the image on success`() {
        val stage = stage(kubernetesContext = "my-context")
        val workload = workload(kubernetes = true)

        val result = executor.attemptDeploy(workload, stage, "image:1.0")

        assertNull(result)
        verify(kubernetesDeploymentClient).patchImage("my-context", "platform", KubernetesKind.DEPLOYMENT, "workload", "image:1.0")
    }

    @Test
    fun `prepends the stage's namespace prefix when deploying`() {
        val stage = stage(kubernetesContext = "minikube", namespacePrefix = "dev-")
        val workload = workload(kubernetes = true)

        val result = executor.attemptDeploy(workload, stage, "image:1.0")

        assertNull(result)
        verify(kubernetesDeploymentClient).patchImage("minikube", "dev-platform", KubernetesKind.DEPLOYMENT, "workload", "image:1.0")
    }

    @Test
    fun `returns 'cluster not reachable' and increments the failure counter when the client throws`() {
        val stage = stage(kubernetesContext = "my-context")
        val workload = workload(kubernetes = true)
        willThrow(KubernetesDeploymentException("boom")).given(kubernetesDeploymentClient)
            .patchImage("my-context", "platform", KubernetesKind.DEPLOYMENT, "workload", "image:1.0")

        val result = executor.attemptDeploy(workload, stage, "image:1.0")

        assertEquals("cluster not reachable", result)
        assertEquals(1.0, meterRegistry.get("cdrm.deploy.failed").counter().count())
    }

    @Test
    fun `commits via GitCommitClient instead of patching when the namespace is GitOps-managed`() {
        val stage = stage(kubernetesContext = "my-context")
        val workload = workload(kubernetes = true)
        val target = GitOpsTarget(
            repositoryUrl = "http://localhost:3000/cdrm/gitops-demo.git",
            branch = "main",
            filePath = "environments/platform/workload.yaml",
            yamlKeyPath = "spec.template.spec.containers[0].image",
        )
        given(gitOpsResolver.resolve(workload, stage)).willReturn(target)
        // No ArgumentMatchers here on purpose: eq()/any() return null as a Mockito-stack
        // placeholder, which NPEs against a Kotlin-declared non-null parameter (unlike a
        // Java-inherited platform type) — plain equals-based stubbing with the exact
        // expected commit message sidesteps that entirely.
        given(
            gitCommitClient.commitImageChange(target, "image:1.0", "cdrm: deploy image:1.0 for workload 'workload' at stage 'Prod'")
        ).willReturn(null)

        val result = executor.attemptDeploy(workload, stage, "image:1.0")

        assertNull(result)
        verifyNoInteractions(kubernetesDeploymentClient)
    }

    @Test
    fun `returns the GitCommitClient's error and increments the failure counter when the commit fails`() {
        val stage = stage(kubernetesContext = "my-context")
        val workload = workload(kubernetes = true)
        val target = GitOpsTarget(
            repositoryUrl = "http://localhost:3000/cdrm/gitops-demo.git",
            branch = "main",
            filePath = "environments/platform/workload.yaml",
            yamlKeyPath = "spec.template.spec.containers[0].image",
        )
        given(gitOpsResolver.resolve(workload, stage)).willReturn(target)
        given(
            gitCommitClient.commitImageChange(target, "image:1.0", "cdrm: deploy image:1.0 for workload 'workload' at stage 'Prod'")
        ).willReturn("git push failed: connection refused")

        val result = executor.attemptDeploy(workload, stage, "image:1.0")

        assertEquals("git push failed: connection refused", result)
        assertEquals(1.0, meterRegistry.get("cdrm.deploy.failed").counter().count())
        verifyNoInteractions(kubernetesDeploymentClient)
    }
}
