// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.gitops

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
        git(seed, "add", "-A")
        git(seed, "-c", "user.email=test@test.com", "-c", "user.name=test", "commit", "-m", "seed")
        git(seed, "push", "origin", "main")
        git(seed, "branch", "release")
        git(seed, "push", "origin", "release")

        client = GitCommitClient(tempDir.resolve("workdir").toString(), "", "")
    }

    private fun target(branch: String = "main") = GitOpsTarget(
        repositoryUrl = bareRepoUrl,
        branch = branch,
        filePath = "environments/p-qa-platform/workload.yaml",
        yamlKeyPath = "spec.template.spec.containers[0].image",
    )

    private fun cloneAndReadFile(branch: String): String {
        val checkDir = tempDir.resolve("check-$branch-${System.nanoTime()}").toFile()
        git(tempDir.toFile(), "clone", "--branch", branch, bareRepoUrl, checkDir.absolutePath)
        return File(checkDir, "environments/p-qa-platform/workload.yaml").readText()
    }

    @Test
    fun `commits and pushes the new image, actually visible in a fresh clone`() {
        val result = client.commitImageChange(target(), "new:2.0", "test commit")

        assertNull(result)
        assertTrue(cloneAndReadFile("main").contains("new:2.0"))
    }

    @Test
    fun `is idempotent — committing the same image again is a no-op success`() {
        client.commitImageChange(target(), "new:2.0", "test commit")

        val result = client.commitImageChange(target(), "new:2.0", "test commit again")

        assertNull(result)
    }

    @Test
    fun `commits to the requested branch independently of others`() {
        client.commitImageChange(target(branch = "main"), "main-image:1.0", "test commit")
        client.commitImageChange(target(branch = "release"), "release-image:1.0", "test commit")

        assertTrue(cloneAndReadFile("main").contains("main-image:1.0"))
        assertTrue(cloneAndReadFile("release").contains("release-image:1.0"))
    }

    @Test
    fun `reuses and fast-forwards the same local clone across calls`() {
        client.commitImageChange(target(), "first:1.0", "test commit")
        client.commitImageChange(target(), "second:1.0", "test commit")

        assertTrue(cloneAndReadFile("main").contains("second:1.0"))
    }

    @Test
    fun `returns an error when the file does not exist in the repo`() {
        val result = client.commitImageChange(target().copy(filePath = "does/not/exist.yaml"), "new:2.0", "test commit")

        assertEquals("file 'does/not/exist.yaml' not found in repo", result)
    }

    @Test
    fun `returns an error when the yaml key path does not resolve`() {
        val result = client.commitImageChange(target().copy(yamlKeyPath = "spec.nonexistent.image"), "new:2.0", "test commit")

        assertTrue(result?.startsWith("yamlKeyPath 'spec.nonexistent.image'") == true)
    }

    @Test
    fun `returns an error for a branch that doesn't exist on the remote`() {
        val result = client.commitImageChange(target(branch = "no-such-branch"), "new:2.0", "test commit")

        assertTrue(result?.contains("git checkout") == true)
    }
}
