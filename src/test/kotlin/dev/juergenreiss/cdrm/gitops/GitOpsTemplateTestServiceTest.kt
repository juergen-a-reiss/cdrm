// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.gitops

import dev.juergenreiss.cdrm.product.Product
import dev.juergenreiss.cdrm.product.ProductRepository
import dev.juergenreiss.cdrm.stage.DeploymentPolicy
import dev.juergenreiss.cdrm.stage.Stage
import dev.juergenreiss.cdrm.stage.StageRepository
import dev.juergenreiss.cdrm.workload.KubernetesKind
import dev.juergenreiss.cdrm.workload.Workload
import dev.juergenreiss.cdrm.workload.WorkloadRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class GitOpsTemplateTestServiceTest {

    @Mock
    private lateinit var workloadRepository: WorkloadRepository

    @Mock
    private lateinit var stageRepository: StageRepository

    @Mock
    private lateinit var productRepository: ProductRepository

    @Mock
    private lateinit var templateEngine: GitOpsTemplateEngine

    @Mock
    private lateinit var gitCommitClient: GitCommitClient

    private lateinit var service: GitOpsTemplateTestService

    private val workloadId = UUID.randomUUID()
    private val stageId = UUID.randomUUID()
    private val productId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        service = GitOpsTemplateTestService(workloadRepository, stageRepository, productRepository, templateEngine, gitCommitClient)
    }

    private fun workload(kubernetesNameSpace: String? = "platform") = Workload(
        id = workloadId,
        name = "platform-interface-emea",
        productId = productId,
        description = null,
        kubernetes = true,
        kubernetesKind = KubernetesKind.DEPLOYMENT,
        kubernetesNameSpace = kubernetesNameSpace,
        pipeline = "paris",
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    private fun stage(namespacePrefix: String? = "p-qa-") = Stage(
        id = stageId,
        pipeline = "paris",
        name = "paris-qa",
        description = null,
        order = 1,
        deploymentPolicy = DeploymentPolicy.IMMEDIATE,
        kubernetesContext = "minikube",
        namespacePrefix = namespacePrefix,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    private fun product() = Product(
        id = productId,
        name = "platform",
        description = null,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    private fun request(script: String = "return []", repositoryUrl: String = "http://localhost:3000/cdrm/gitops-demo.git") =
        GitOpsTemplateTestRequest(
            script = script,
            repositoryUrl = repositoryUrl,
            clusterName = "minikube",
            workloadId = workloadId,
            stageId = stageId,
            releaseBinary = "test:latest",
        )

    // GitOpsTemplateEngine.evaluate's parameters are Kotlin-declared non-null — stubbing
    // with any()/anyString() would trip Kotlin's null-check on the matchers themselves
    // (see GitOpsResolverTest), so this stubs with the exact context the service actually
    // builds from the fixtures above instead.
    private val expectedContext = GitOpsTemplateContext(
        gitRepoName = "http://localhost:3000/cdrm/gitops-demo.git",
        clusterName = "minikube",
        namespace = "p-qa-platform",
        productName = "platform",
        stageName = "paris-qa",
        workloadName = "platform-interface-emea",
        releaseBinary = "test:latest",
        targetStage = "paris-qa",
    )

    private fun stubLookups() {
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(workload()))
        given(stageRepository.findById(stageId)).willReturn(Optional.of(stage()))
        given(productRepository.findById(productId)).willReturn(Optional.of(product()))
    }

    @Test
    fun `rejects a blank repositoryUrl before touching any repository`() {
        assertThrows(ResponseStatusException::class.java) { service.test(request(repositoryUrl = "")) }

        verifyNoInteractions(workloadRepository, stageRepository, productRepository, templateEngine, gitCommitClient)
    }

    @Test
    fun `throws BAD_REQUEST for an unknown workload`() {
        given(workloadRepository.findById(workloadId)).willReturn(Optional.empty())

        val exception = assertThrows(ResponseStatusException::class.java) { service.test(request()) }

        assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
    }

    @Test
    fun `throws BAD_REQUEST for an unknown stage`() {
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(workload()))
        given(stageRepository.findById(stageId)).willReturn(Optional.empty())

        val exception = assertThrows(ResponseStatusException::class.java) { service.test(request()) }

        assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
    }

    @Test
    fun `throws BAD_REQUEST when the workload has no kubernetes namespace configured`() {
        given(workloadRepository.findById(workloadId)).willReturn(Optional.of(workload(kubernetesNameSpace = null)))
        given(stageRepository.findById(stageId)).willReturn(Optional.of(stage()))

        val exception = assertThrows(ResponseStatusException::class.java) { service.test(request()) }

        assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
    }

    @Test
    fun `returns success false with the reason when the script itself fails, without checking git`() {
        stubLookups()
        val script = "throw new Error('boom')"
        given(templateEngine.evaluate(script, expectedContext)).willReturn(GitOpsTemplateResult.Failed("template script failed: boom"))

        val response = service.test(request(script = script))

        assertFalse(response.success)
        assertEquals("template script failed: boom", response.reason)
        assertTrue(response.edits.isEmpty())
        verifyNoInteractions(gitCommitClient)
    }

    @Test
    fun `returns success false when the script returns no edits, without checking git`() {
        stubLookups()
        val script = "return []"
        given(templateEngine.evaluate(script, expectedContext)).willReturn(GitOpsTemplateResult.Success(emptyList()))

        val response = service.test(request(script = script))

        assertFalse(response.success)
        assertEquals("template script returned no edits", response.reason)
        verifyNoInteractions(gitCommitClient)
    }

    @Test
    fun `returns success true with each edit annotated by its git check`() {
        stubLookups()
        val script = "return [{gitBranch: 'main', filePath: 'a.yaml', yamlKeyPath: 'image', value: 'test:latest'}]"
        val edits = listOf(GitOpsEdit("main", "a.yaml", "image", "test:latest"))
        given(templateEngine.evaluate(script, expectedContext)).willReturn(GitOpsTemplateResult.Success(edits))
        given(gitCommitClient.checkEdits("http://localhost:3000/cdrm/gitops-demo.git", edits)).willReturn(
            GitOpsEditCheckOutcome.Success(listOf(GitOpsEditCheck(edits.single(), branchExists = true, fileExists = false, yamlKeyPathExists = false)))
        )

        val response = service.test(request(script = script))

        assertTrue(response.success)
        assertNull(response.reason)
        val edit = response.edits.single()
        assertEquals("main", edit.gitBranch)
        assertEquals("a.yaml", edit.filePath)
        assertEquals("image", edit.yamlKeyPath)
        assertEquals("test:latest", edit.value)
        assertTrue(edit.branchExists)
        assertFalse(edit.fileExists)
        assertFalse(edit.yamlKeyPathExists)
    }

    @Test
    fun `surfaces a git check failure as BAD_GATEWAY`() {
        stubLookups()
        val script = "return [{gitBranch: 'main', filePath: 'a.yaml', yamlKeyPath: 'image', value: 'test:latest'}]"
        val edits = listOf(GitOpsEdit("main", "a.yaml", "image", "test:latest"))
        given(templateEngine.evaluate(script, expectedContext)).willReturn(GitOpsTemplateResult.Success(edits))
        given(gitCommitClient.checkEdits("http://localhost:3000/cdrm/gitops-demo.git", edits))
            .willReturn(GitOpsEditCheckOutcome.Failed("clone failed"))

        val exception = assertThrows(ResponseStatusException::class.java) { service.test(request(script = script)) }

        assertEquals(HttpStatus.BAD_GATEWAY, exception.statusCode)
    }

    @Test
    fun `surfaces a busy git lock as TOO_MANY_REQUESTS`() {
        stubLookups()
        val script = "return [{gitBranch: 'main', filePath: 'a.yaml', yamlKeyPath: 'image', value: 'test:latest'}]"
        val edits = listOf(GitOpsEdit("main", "a.yaml", "image", "test:latest"))
        given(templateEngine.evaluate(script, expectedContext)).willReturn(GitOpsTemplateResult.Success(edits))
        given(gitCommitClient.checkEdits("http://localhost:3000/cdrm/gitops-demo.git", edits)).willReturn(GitOpsEditCheckOutcome.LockBusy)

        val exception = assertThrows(ResponseStatusException::class.java) { service.test(request(script = script)) }

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.statusCode)
    }
}
