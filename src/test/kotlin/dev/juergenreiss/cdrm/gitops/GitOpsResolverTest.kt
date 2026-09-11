// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.gitops

import dev.juergenreiss.cdrm.cluster.Cluster
import dev.juergenreiss.cdrm.cluster.ClusterRepository
import dev.juergenreiss.cdrm.cluster.ClusterType
import dev.juergenreiss.cdrm.cluster.GitOpsNamespaceMode
import dev.juergenreiss.cdrm.cluster.K8sGitopsConfig
import dev.juergenreiss.cdrm.cluster.K8sNamespaceGitopsConfig
import dev.juergenreiss.cdrm.product.Product
import dev.juergenreiss.cdrm.stage.DeploymentPolicy
import dev.juergenreiss.cdrm.stage.Stage
import dev.juergenreiss.cdrm.workload.KubernetesKind
import dev.juergenreiss.cdrm.workload.Workload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import java.net.URI
import java.time.Instant
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class GitOpsResolverTest {

    @Mock
    private lateinit var clusterRepository: ClusterRepository

    @Mock
    private lateinit var templateEngine: GitOpsTemplateEngine

    private lateinit var resolver: GitOpsResolver

    @BeforeEach
    fun setUp() {
        resolver = GitOpsResolver(clusterRepository, templateEngine)
    }

    private fun stage(namespacePrefix: String? = "p-qa-", name: String = "paris-qa") = Stage(
        id = UUID.randomUUID(),
        pipeline = "paris",
        name = name,
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

    private fun workload(kubernetesNameSpace: String? = "platform", name: String = "platform-interface-emea") = Workload(
        id = UUID.randomUUID(),
        name = name,
        productId = UUID.randomUUID(),
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

    private fun product(name: String = "platform") = Product(
        id = UUID.randomUUID(),
        name = name,
        description = null,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    private fun cluster(gitOpsConfig: K8sGitopsConfig?) = Cluster(
        id = UUID.randomUUID(),
        name = "minikube",
        description = null,
        clusterType = ClusterType.K8S,
        url = URI("https://cluster.example.com").toURL(),
        k8sGitOpsConfig = gitOpsConfig,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    private fun resolve(config: K8sGitopsConfig?, stage: Stage = stage(), workload: Workload = workload(), image: String = "new:2.0"): GitOpsResolution {
        given(clusterRepository.findAll()).willReturn(listOf(cluster(config)))
        return resolver.resolve(workload, stage, product(), image)
    }

    @Test
    fun `returns NotManaged when no cluster has a gitops config`() {
        assertEquals(GitOpsResolution.NotManaged, resolve(null))
    }

    @Test
    fun `returns NotManaged when the cluster's gitops config is disabled`() {
        val config = K8sGitopsConfig(
            useGitOps = false,
            gitRepo = "http://localhost:3000/cdrm/gitops-demo.git",
            namespaces = mutableMapOf(
                "p-qa-platform" to K8sNamespaceGitopsConfig(
                    namespace = "p-qa-platform", useGitOps = true,
                    fileExpression = "environments/{namespace}/{workload}.yaml", yamlExpression = "image",
                )
            ),
        )

        assertEquals(GitOpsResolution.NotManaged, resolve(config))
    }

    @Test
    fun `returns NotManaged when the namespace itself is not registered`() {
        val config = K8sGitopsConfig(useGitOps = true, gitRepo = "http://localhost:3000/cdrm/gitops-demo.git")

        assertEquals(GitOpsResolution.NotManaged, resolve(config))
    }

    @Test
    fun `returns NotManaged when the namespace's own useGitOps is false`() {
        val config = K8sGitopsConfig(
            useGitOps = true,
            gitRepo = "http://localhost:3000/cdrm/gitops-demo.git",
            namespaces = mutableMapOf(
                "p-qa-platform" to K8sNamespaceGitopsConfig(
                    namespace = "p-qa-platform", useGitOps = false,
                    fileExpression = "environments/{namespace}/{workload}.yaml", yamlExpression = "image",
                )
            ),
        )

        assertEquals(GitOpsResolution.NotManaged, resolve(config))
    }

    @Test
    fun `returns NotManaged when the workload has no kubernetes namespace configured`() {
        // Not routed through the resolve() helper: this short-circuits before ever
        // reaching clusterRepository.findAll(), so stubbing it here would be flagged as
        // unnecessary (strict stubbing) rather than actually exercising anything.
        val resolution = resolver.resolve(workload(kubernetesNameSpace = null), stage(), product(), "new:2.0")

        assertEquals(GitOpsResolution.NotManaged, resolution)
    }

    @Test
    fun `resolves the target, substituting placeholders, using the cluster-wide branch by default`() {
        val config = K8sGitopsConfig(
            useGitOps = true,
            gitRepo = "http://localhost:3000/cdrm/gitops-demo.git",
            gitBranch = "main",
            namespaces = mutableMapOf(
                "p-qa-platform" to K8sNamespaceGitopsConfig(
                    namespace = "p-qa-platform", useGitOps = true,
                    fileExpression = "environments/{namespace}/{workload}.yaml",
                    yamlExpression = "spec.template.spec.containers[0].image",
                )
            ),
        )

        val resolution = resolve(config, image = "new:2.0")

        assertEquals(
            GitOpsResolution.Resolved(
                GitOpsTarget(
                    repositoryUrl = "http://localhost:3000/cdrm/gitops-demo.git",
                    edits = listOf(
                        GitOpsEdit(
                            branch = "main",
                            filePath = "environments/p-qa-platform/platform-interface-emea.yaml",
                            yamlKeyPath = "spec.template.spec.containers[0].image",
                            value = "new:2.0",
                        )
                    ),
                )
            ),
            resolution,
        )
    }

    @Test
    fun `a namespace's own branch overrides the cluster-wide default`() {
        val config = K8sGitopsConfig(
            useGitOps = true,
            gitRepo = "http://localhost:3000/cdrm/gitops-demo.git",
            gitBranch = "main",
            namespaces = mutableMapOf(
                "p-prod-platform" to K8sNamespaceGitopsConfig(
                    namespace = "p-prod-platform", useGitOps = true,
                    fileExpression = "environments/{namespace}/{workload}.yaml",
                    yamlExpression = "spec.template.spec.containers[0].image",
                    gitBranch = "release",
                )
            ),
        )

        val resolution = resolve(config, stage = stage(namespacePrefix = "p-prod-", name = "paris-production"))

        val resolved = resolution as GitOpsResolution.Resolved
        assertEquals("release", resolved.target.edits.single().branch)
    }

    @Test
    fun `a namespace's own repo overrides the cluster-wide default`() {
        val config = K8sGitopsConfig(
            useGitOps = true,
            gitRepo = "http://localhost:3000/cdrm/gitops-demo.git",
            gitBranch = "main",
            namespaces = mutableMapOf(
                "p-prod-platform" to K8sNamespaceGitopsConfig(
                    namespace = "p-prod-platform", useGitOps = true,
                    fileExpression = "environments/{namespace}/{workload}.yaml",
                    yamlExpression = "spec.template.spec.containers[0].image",
                    gitRepo = "http://localhost:3000/cdrm/gitops-demo-2.git",
                )
            ),
        )

        val resolution = resolve(config, stage = stage(namespacePrefix = "p-prod-", name = "paris-production"))

        val resolved = resolution as GitOpsResolution.Resolved
        assertEquals("http://localhost:3000/cdrm/gitops-demo-2.git", resolved.target.repositoryUrl)
    }

    // GitOpsTemplateEngine.evaluate's parameters are Kotlin-declared non-null on a
    // Kotlin-declared class — stubbing with ArgumentMatchers.any()/anyString() would
    // trip Kotlin's compiler-inserted null-check on those matchers themselves (they
    // return null at runtime by design), so this stubs with the exact context
    // GitOpsResolver.resolve() actually builds for the test fixtures above instead.
    private val expectedTemplateContext = GitOpsTemplateContext(
        gitRepoName = "http://localhost:3000/cdrm/gitops-demo.git",
        clusterName = "minikube",
        namespace = "p-qa-platform",
        productName = "platform",
        stageName = "paris-qa",
        workloadName = "platform-interface-emea",
        releaseBinary = "new:2.0",
        targetStage = "paris-qa",
    )

    @Test
    fun `TEMPLATE mode invokes the template engine and returns its edits`() {
        val script = "return [{gitBranch: 'main', filePath: 'a.yaml', yamlKeyPath: 'image', value: 'x'}]"
        val config = K8sGitopsConfig(
            useGitOps = true,
            gitRepo = "http://localhost:3000/cdrm/gitops-demo.git",
            namespaces = mutableMapOf(
                "p-qa-platform" to K8sNamespaceGitopsConfig(
                    namespace = "p-qa-platform", useGitOps = true,
                    mode = GitOpsNamespaceMode.TEMPLATE,
                    fileExpression = null, yamlExpression = null,
                    templateScript = script,
                )
            ),
        )
        val edits = listOf(GitOpsEdit("main", "a.yaml", "image", "x"))
        given(templateEngine.evaluate(script, expectedTemplateContext)).willReturn(GitOpsTemplateResult.Success(edits))

        val resolution = resolve(config)

        assertEquals(GitOpsResolution.Resolved(GitOpsTarget("http://localhost:3000/cdrm/gitops-demo.git", edits)), resolution)
    }

    @Test
    fun `TEMPLATE mode surfaces a template failure as TemplateFailed, not NotManaged`() {
        val script = "throw new Error('boom')"
        val config = K8sGitopsConfig(
            useGitOps = true,
            gitRepo = "http://localhost:3000/cdrm/gitops-demo.git",
            namespaces = mutableMapOf(
                "p-qa-platform" to K8sNamespaceGitopsConfig(
                    namespace = "p-qa-platform", useGitOps = true,
                    mode = GitOpsNamespaceMode.TEMPLATE,
                    fileExpression = null, yamlExpression = null,
                    templateScript = script,
                )
            ),
        )
        given(templateEngine.evaluate(script, expectedTemplateContext)).willReturn(GitOpsTemplateResult.Failed("template script failed: boom"))

        val resolution = resolve(config)

        assertTrue(resolution is GitOpsResolution.TemplateFailed)
        assertEquals("template script failed: boom", (resolution as GitOpsResolution.TemplateFailed).reason)
    }

    @Test
    fun `TEMPLATE mode with a blank script is NotManaged, never invoking the engine`() {
        val config = K8sGitopsConfig(
            useGitOps = true,
            gitRepo = "http://localhost:3000/cdrm/gitops-demo.git",
            namespaces = mutableMapOf(
                "p-qa-platform" to K8sNamespaceGitopsConfig(
                    namespace = "p-qa-platform", useGitOps = true,
                    mode = GitOpsNamespaceMode.TEMPLATE,
                    fileExpression = null, yamlExpression = null,
                    templateScript = null,
                )
            ),
        )

        val resolution = resolve(config)

        assertEquals(GitOpsResolution.NotManaged, resolution)
        org.mockito.Mockito.verifyNoInteractions(templateEngine)
    }

    @Test
    fun `isManaged is true whenever the namespace is registered, regardless of mode field completeness`() {
        val config = K8sGitopsConfig(
            useGitOps = true,
            gitRepo = "http://localhost:3000/cdrm/gitops-demo.git",
            namespaces = mutableMapOf(
                "p-qa-platform" to K8sNamespaceGitopsConfig(
                    namespace = "p-qa-platform", useGitOps = true,
                    fileExpression = null, yamlExpression = null,
                )
            ),
        )
        given(clusterRepository.findAll()).willReturn(listOf(cluster(config)))

        assertTrue(resolver.isManaged(workload(), stage()))
    }

    @Test
    fun `isManaged is false when the namespace isn't registered, and never touches the template engine`() {
        given(clusterRepository.findAll()).willReturn(listOf(cluster(null)))

        assertFalse(resolver.isManaged(workload(), stage()))
        org.mockito.Mockito.verifyNoInteractions(templateEngine)
    }
}
