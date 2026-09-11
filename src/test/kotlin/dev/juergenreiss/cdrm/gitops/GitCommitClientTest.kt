// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.gitops

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import java.io.File
import java.nio.file.Path

// Exercises the actual clone/edit/commit/push cycle against a local bare repo (no
// network, no Gitea) — this is the one place it round-trips through real git rather than
// being trusted against mocks.
class GitCommitClientTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var bareRepoUrl: String
    private lateinit var client: GitCommitClient

    private fun git(dir: File, vararg args: String): String {
        val process = ProcessBuilder("git", *args).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output
    }

    @BeforeEach
    fun setUp() {
        val bareRepo = tempDir.resolve("bare.git").toFile()
        git(tempDir.toFile(), "init", "--bare", "--initial-branch=main", bareRepo.absolutePath)
        // Not bareRepo.toURI().toString() — File.toURI() can produce a single-slash
        // "file:/..." URI that git misparses as SCP-like syntax ("file" as a hostname).
        bareRepoUrl = "file://${bareRepo.absolutePath}"

        val seed = tempDir.resolve("seed").toFile()
        git(tempDir.toFile(), "clone", bareRepoUrl, seed.absolutePath)
        File(seed, "environments/p-qa-platform").mkdirs()
        File(seed, "environments/p-qa-platform/workload.yaml").writeText(
            """
            spec:
              template:
                spec:
                  containers:
                    - name: app
                      image: old:1.0
            """.trimIndent()
        )
        File(seed, "environments/p-qa-platform/other.yaml").writeText(
            """
            spec:
              template:
                spec:
                  containers:
                    - name: app
                      image: old:1.0
            """.trimIndent()
        )
        git(seed, "add", "-A")
        git(seed, "-c", "user.email=test@test.com", "-c", "user.name=test", "commit", "-m", "seed")
        git(seed, "push", "origin", "main")
        git(seed, "branch", "release")
        git(seed, "push", "origin", "release")

        // No real database in this test — commit()'s git_lock acquisition is mocked out
        // to always succeed immediately, so this stays a pure git-mechanics test (see
        // the class doc comment) rather than needing a real Postgres/Testcontainers
        // dependency just to exercise SELECT ... FOR UPDATE.
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        `when`(jdbcTemplate.queryForObject("select id from git_lock where id = 1 for update nowait", Int::class.java)).thenReturn(1)
        val transactionManager = mock(PlatformTransactionManager::class.java)
        val transactionStatus = mock(TransactionStatus::class.java)
        `when`(transactionManager.getTransaction(org.mockito.ArgumentMatchers.any(TransactionDefinition::class.java))).thenReturn(transactionStatus)

        client = GitCommitClient(tempDir.resolve("workdir").toString(), GitOpsProperties(), jdbcTemplate, transactionManager)
    }

    private fun edit(
        branch: String = "main",
        filePath: String = "environments/p-qa-platform/workload.yaml",
        yamlKeyPath: String = "spec.template.spec.containers[0].image",
        value: String,
    ) = GitOpsEdit(branch, filePath, yamlKeyPath, value)

    private fun target(vararg edits: GitOpsEdit) = GitOpsTarget(repositoryUrl = bareRepoUrl, edits = edits.toList())

    private fun cloneAndReadFile(branch: String, filePath: String = "environments/p-qa-platform/workload.yaml"): String {
        val checkDir = tempDir.resolve("check-$branch-${System.nanoTime()}").toFile()
        git(tempDir.toFile(), "clone", "--branch", branch, bareRepoUrl, checkDir.absolutePath)
        return File(checkDir, filePath).readText()
    }

    @Test
    fun `commits and pushes the new image, actually visible in a fresh clone`() {
        val result = client.commit(target(edit(value = "new:2.0")), "test commit")

        assertEquals(GitCommitResult.Success, result)
        assertTrue(cloneAndReadFile("main").contains("new:2.0"))
    }

    @Test
    fun `still commits and pushes when the matching repository entry uses BASIC auth`() {
        // A bare file:// repo doesn't enforce or even see any auth header — this exercises
        // that adding one (GitCommitClient's auth=BASIC branch) doesn't break a normal
        // commit/push, without needing a real authenticating git-over-http server.
        val properties = GitOpsProperties().apply {
            repositories = mutableListOf(
                GitOpsRepositoryConfig().apply {
                    url = bareRepoUrl
                    auth = GitAuthMode.BASIC
                    username = "cdrm"
                    password = "cdrm"
                }
            )
        }
        val authedJdbcTemplate = mock(JdbcTemplate::class.java)
        `when`(authedJdbcTemplate.queryForObject("select id from git_lock where id = 1 for update nowait", Int::class.java)).thenReturn(1)
        val authedTransactionManager = mock(PlatformTransactionManager::class.java)
        `when`(authedTransactionManager.getTransaction(org.mockito.ArgumentMatchers.any(TransactionDefinition::class.java)))
            .thenReturn(mock(TransactionStatus::class.java))
        val authedClient =
            GitCommitClient(tempDir.resolve("workdir-basic").toString(), properties, authedJdbcTemplate, authedTransactionManager)

        val result = authedClient.commit(target(edit(value = "new:2.0")), "test commit")

        assertEquals(GitCommitResult.Success, result)
        assertTrue(cloneAndReadFile("main").contains("new:2.0"))
    }

    @Test
    fun `is idempotent — committing the same image again is a no-op success`() {
        client.commit(target(edit(value = "new:2.0")), "test commit")

        val result = client.commit(target(edit(value = "new:2.0")), "test commit again")

        assertEquals(GitCommitResult.Success, result)
    }

    @Test
    fun `commits to the requested branch independently of others`() {
        client.commit(target(edit(branch = "main", value = "main-image:1.0")), "test commit")
        client.commit(target(edit(branch = "release", value = "release-image:1.0")), "test commit")

        assertTrue(cloneAndReadFile("main").contains("main-image:1.0"))
        assertTrue(cloneAndReadFile("release").contains("release-image:1.0"))
    }

    @Test
    fun `reuses and fast-forwards the same local clone across calls`() {
        client.commit(target(edit(value = "first:1.0")), "test commit")
        client.commit(target(edit(value = "second:1.0")), "test commit")

        assertTrue(cloneAndReadFile("main").contains("second:1.0"))
    }

    @Test
    fun `applies multiple edits to the same file in one commit`() {
        val result = client.commit(
            target(
                edit(yamlKeyPath = "spec.template.spec.containers[0].image", value = "new:2.0"),
                edit(yamlKeyPath = "spec.template.spec.metadata", value = "updated"),
            ),
            "test commit",
        )

        assertEquals(GitCommitResult.Success, result)
        val content = cloneAndReadFile("main")
        assertTrue(content.contains("new:2.0"))
        assertTrue(content.contains("updated"))
    }

    @Test
    fun `applies edits across multiple different files in one commit`() {
        val result = client.commit(
            target(
                edit(filePath = "environments/p-qa-platform/workload.yaml", value = "workload:2.0"),
                edit(filePath = "environments/p-qa-platform/other.yaml", value = "other:2.0"),
            ),
            "test commit",
        )

        assertEquals(GitCommitResult.Success, result)
        assertTrue(cloneAndReadFile("main", "environments/p-qa-platform/workload.yaml").contains("workload:2.0"))
        assertTrue(cloneAndReadFile("main", "environments/p-qa-platform/other.yaml").contains("other:2.0"))
    }

    @Test
    fun `applies edits across multiple branches in one call, each its own commit`() {
        val result = client.commit(
            target(
                edit(branch = "main", value = "main-image:2.0"),
                edit(branch = "release", value = "release-image:2.0"),
            ),
            "test commit",
        )

        assertEquals(GitCommitResult.Success, result)
        assertTrue(cloneAndReadFile("main").contains("main-image:2.0"))
        assertTrue(cloneAndReadFile("release").contains("release-image:2.0"))
    }

    @Test
    fun `returns an error when the file does not exist in the repo`() {
        val result = client.commit(target(edit(filePath = "does/not/exist.yaml", value = "new:2.0")), "test commit")

        assertEquals(GitCommitResult.Failed("file 'does/not/exist.yaml' not found in repo"), result)
    }

    @Test
    fun `returns an error when the yaml key path does not resolve`() {
        val result = client.commit(target(edit(yamlKeyPath = "spec.nonexistent.image", value = "new:2.0")), "test commit")

        assertTrue((result as? GitCommitResult.Failed)?.reason?.startsWith("yamlKeyPath 'spec.nonexistent.image'") == true)
    }

    @Test
    fun `returns an error for a branch that doesn't exist on the remote`() {
        val result = client.commit(target(edit(branch = "no-such-branch", value = "new:2.0")), "test commit")

        assertTrue((result as? GitCommitResult.Failed)?.reason?.contains("git checkout") == true)
    }

    @Test
    fun `returns LockBusy without touching git when the git_lock row can't be acquired`() {
        val busyJdbcTemplate = mock(JdbcTemplate::class.java)
        `when`(busyJdbcTemplate.queryForObject("select id from git_lock where id = 1 for update nowait", Int::class.java))
            .thenThrow(org.springframework.dao.CannotAcquireLockException("lock not available"))
        val transactionManager = mock(PlatformTransactionManager::class.java)
        val transactionStatus = mock(TransactionStatus::class.java)
        `when`(transactionManager.getTransaction(org.mockito.ArgumentMatchers.any(TransactionDefinition::class.java))).thenReturn(transactionStatus)
        val busyClient = GitCommitClient(tempDir.resolve("workdir-busy").toString(), GitOpsProperties(), busyJdbcTemplate, transactionManager)

        val result = busyClient.commit(target(edit(value = "new:2.0")), "test commit")

        assertEquals(GitCommitResult.LockBusy, result)
    }
}
