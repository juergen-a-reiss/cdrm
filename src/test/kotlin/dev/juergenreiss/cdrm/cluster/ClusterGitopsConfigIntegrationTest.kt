// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.cluster

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.time.Instant
import java.util.UUID

// k8s_gitops_config is a jsonb column mapped via K8sGitopsConfigConverter +
// @JdbcTypeCode(SqlTypes.JSON) — the first JSON column in this schema, and a combination
// (a String-producing converter paired with the JSON JDBC type, rather than relying on
// Hibernate's own object<->JSON serialization) not exercised anywhere else in this
// codebase, so this is the one place it actually round-trips through a real Postgres
// rather than being trusted against a mocked repository.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ClusterGitopsConfigIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18"))
    }

    @Autowired
    private lateinit var repository: ClusterRepository

    private fun newCluster(gitopsConfig: K8sGitopsConfig?) = Cluster(
        name = "cluster-${UUID.randomUUID()}",
        description = null,
        clusterType = ClusterType.K8S,
        url = URI("https://${UUID.randomUUID()}.example.com").toURL(),
        k8sGitOpsConfig = gitopsConfig,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        modifiedBy = UUID.randomUUID(),
    )

    @Test
    fun `round-trips a gitops config, including its nested namespace map, through the jsonb column`() {
        val config = K8sGitopsConfig(
            useGitOps = true,
            gitRepo = "git@github.com:example/gitops.git",
            gitBranch = "main",
            namespaces = mutableMapOf(
                "dev-platform" to K8sNamespaceGitopsConfig(
                    namespace = "dev-platform",
                    useGitOps = true,
                    fileExpression = "environments/{namespace}/{workload}.yaml",
                    yamlExpression = "spec.template.spec.containers[0].image",
                ),
                "prod-platform" to K8sNamespaceGitopsConfig(
                    namespace = "prod-platform",
                    useGitOps = true,
                    fileExpression = "environments/{namespace}/{workload}.yaml",
                    yamlExpression = "spec.template.spec.containers[0].image",
                    gitBranch = "release",
                ),
            ),
        )
        val saved = repository.saveAndFlush(newCluster(config))
        repository.flush()

        val reloaded = repository.findById(saved.id!!).orElseThrow()

        val reloadedConfig = reloaded.k8sGitOpsConfig!!
        assertTrue(reloadedConfig.useGitOps)
        assertEquals("git@github.com:example/gitops.git", reloadedConfig.gitRepo)
        assertEquals("main", reloadedConfig.gitBranch)
        val ns = reloadedConfig.namespaces["dev-platform"]!!
        assertEquals("dev-platform", ns.namespace)
        assertTrue(ns.useGitOps)
        assertEquals("environments/{namespace}/{workload}.yaml", ns.fileExpression)
        assertEquals("spec.template.spec.containers[0].image", ns.yamlExpression)
        assertNull(ns.gitBranch)
        val prodNs = reloadedConfig.namespaces["prod-platform"]!!
        assertEquals("release", prodNs.gitBranch)
    }

    @Test
    fun `leaves the column null when no gitops config is set`() {
        val saved = repository.saveAndFlush(newCluster(null))
        repository.flush()

        val reloaded = repository.findById(saved.id!!).orElseThrow()

        assertNull(reloaded.k8sGitOpsConfig)
    }
}
