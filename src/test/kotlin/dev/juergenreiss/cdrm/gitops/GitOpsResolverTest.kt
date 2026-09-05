// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.gitops

import dev.juergenreiss.cdrm.cluster.Cluster
import dev.juergenreiss.cdrm.cluster.ClusterRepository
import dev.juergenreiss.cdrm.cluster.ClusterType
import dev.juergenreiss.cdrm.cluster.K8sGitopsConfig
import dev.juergenreiss.cdrm.cluster.K8sNamespaceGitopsConfig
import dev.juergenreiss.cdrm.stage.DeploymentPolicy
import dev.juergenreiss.cdrm.stage.Stage
import dev.juergenreiss.cdrm.workload.KubernetesKind
import dev.juergenreiss.cdrm.workload.Workload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    private lateinit var resolver: GitOpsResolver

    @BeforeEach
    fun setUp() {
        resolver = GitOpsResolver(clusterRepository)
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

    @Test
    fun `returns null when no cluster has a gitops config`() {
        given(clusterRepository.findAll()).willReturn(listOf(cluster(null)))

        assertNull(resolver.resolve(workload(), stage()))
    }

    @Test
    fun `returns null when the cluster's gitops config is disabled`() {
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
        given(clusterRepository.findAll()).willReturn(listOf(cluster(config)))

        assertNull(resolver.resolve(workload(), stage()))
    }

    @Test
    fun `returns null when the namespace itself is not registered`() {
        val config = K8sGitopsConfig(useGitOps = true, gitRepo = "http://localhost:3000/cdrm/gitops-demo.git")
        given(clusterRepository.findAll()).willReturn(listOf(cluster(config)))

        assertNull(resolver.resolve(workload(), stage()))
    }

    @Test
    fun `returns null when the namespace's own useGitOps is false`() {
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
        given(clusterRepository.findAll()).willReturn(listOf(cluster(config)))

        assertNull(resolver.resolve(workload(), stage()))
    }

    @Test
    fun `returns null when the workload has no kubernetes namespace configured`() {
        assertNull(resolver.resolve(workload(kubernetesNameSpace = null), stage()))
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
        given(clusterRepository.findAll()).willReturn(listOf(cluster(config)))

        val target = resolver.resolve(workload(), stage())

        assertEquals(
            GitOpsTarget(
                repositoryUrl = "http://localhost:3000/cdrm/gitops-demo.git",
                branch = "main",
                filePath = "environments/p-qa-platform/platform-interface-emea.yaml",
                yamlKeyPath = "spec.template.spec.containers[0].image",
            ),
            target,
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
        given(clusterRepository.findAll()).willReturn(listOf(cluster(config)))

        val target = resolver.resolve(workload(), stage(namespacePrefix = "p-prod-", name = "paris-production"))

        assertEquals("release", target?.branch)
    }
}
