// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.gitops

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.CannotAcquireLockException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.security.MessageDigest
import java.util.Base64

class GitCommitException(message: String) : RuntimeException(message)

sealed interface GitCommitResult {
    data object Success : GitCommitResult
    data class Failed(val reason: String) : GitCommitResult
    // git_lock (see the 005-create-git-lock migration) couldn't be taken with NOWAIT —
    // another git operation, anywhere in the app (any instance), is in progress right
    // now. Not this commit's own failure; the caller decides what that means (see
    // DeployAttemptResult.GitLockBusy).
    data object LockBusy : GitCommitResult
}

// Commits an image-tag change into a GitOps repo instead of patching Kubernetes
// directly (see GitOpsResolver) — ArgoCD (or similar) reconciles the cluster from what
// this pushes. cdrm.gitops.git-username/git-password (optional — the local demo repo
// allows anonymous read but needs them to push) are sent as a Basic auth header per git
// invocation (`-c http.extraHeader=...`), never written into the clone's .git/config —
// application-dev.yaml sets them to the local Gitea demo's credentials so a fresh dev
// setup doesn't need any manual environment configuration; a real deployment would
// override cdrm.gitops.* via env vars (CDRM_GITOPS_GIT_USERNAME etc. — Spring's relaxed
// binding maps those to this same property) pointing at its own repo credentials.
@Component
class GitCommitClient(
    @Value("\${cdrm.gitops.work-dir:\${java.io.tmpdir}/cdrm-gitops}") private val workDir: String,
    @Value("\${cdrm.gitops.git-username:}") private val gitUsername: String,
    @Value("\${cdrm.gitops.git-password:}") private val gitPassword: String,
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(GitCommitClient::class.java)

    // Every git operation across the whole app (and every instance of it) is strictly
    // serialized through the single git_lock row — a git clone's working tree isn't
    // safe for concurrent commits, and unlike a plain in-JVM lock this also holds across
    // multiple backend instances. PROPAGATION_REQUIRES_NEW: the lock (and the dedicated
    // connection holding it) must span exactly this git operation, independent of
    // whatever transaction the caller is already in.
    private val lockTransaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    // NOWAIT: a caller that can't get the lock right now fails fast instead of queuing
    // behind whichever git operation is currently running — DeployAttemptResult.GitLockBusy
    // maps to an immediate HTTP 429 for a synchronous request, or a same-tick retry
    // (without spending retry budget) for the scheduler. Postgres releases the row lock
    // automatically if the holding transaction/connection dies, so a crash mid-git-
    // operation can't wedge every future deploy behind it.
    fun commitImageChange(target: GitOpsTarget, image: String, commitMessage: String): GitCommitResult {
        return try {
            lockTransaction.execute {
                jdbcTemplate.queryForObject("select id from git_lock where id = 1 for update nowait", Int::class.java)
                doCommit(target, image, commitMessage)
            } ?: GitCommitResult.Failed("git lock transaction returned no result")
        } catch (e: CannotAcquireLockException) {
            log.info("GitOps lock busy — another git operation is in progress, will retry ({})", target.repositoryUrl)
            GitCommitResult.LockBusy
        }
    }

    private fun doCommit(target: GitOpsTarget, image: String, commitMessage: String): GitCommitResult {
        return try {
            val dir = ensureClone(target.repositoryUrl)
            checkout(dir, target.branch)

            val file = File(dir, target.filePath)
            if (!file.isFile) return GitCommitResult.Failed("file '${target.filePath}' not found in repo")
            val yaml = Yaml(DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK })
            @Suppress("UNCHECKED_CAST")
            val root = (yaml.load(file.readText()) as? MutableMap<String, Any?>)
                ?: return GitCommitResult.Failed("'${target.filePath}' is not a YAML mapping")
            try {
                YamlPathEditor.setValue(root, target.yamlKeyPath, image)
            } catch (e: IllegalArgumentException) {
                return GitCommitResult.Failed("yamlKeyPath '${target.yamlKeyPath}' in '${target.filePath}': ${e.message}")
            }
            file.writeText(yaml.dump(root))

            git(dir, "add", target.filePath)
            val commit = git(dir, "commit", "-m", commitMessage, allowFailure = true)
            if (commit.exitCode != 0) {
                if ("nothing to commit" in commit.output) return GitCommitResult.Success
                return GitCommitResult.Failed("git commit failed: ${commit.output.trim()}")
            }
            git(dir, "push", "origin", target.branch)
            log.info("Committed {} = '{}' to {}#{} ({})", target.yamlKeyPath, image, target.repositoryUrl, target.branch, target.filePath)
            GitCommitResult.Success
        } catch (e: GitCommitException) {
            log.error("GitOps commit failed for {}: {}", target.repositoryUrl, e.message)
            GitCommitResult.Failed(e.message ?: "git operation failed")
        }
    }

    private fun ensureClone(repositoryUrl: String): File {
        val dir = File(workDir, sha256(repositoryUrl))
        if (File(dir, ".git").isDirectory) {
            git(dir, "fetch", "origin")
        } else {
            dir.parentFile.mkdirs()
            git(dir.parentFile, "clone", repositoryUrl, dir.name)
        }
        return dir
    }

    // Also the terminal recovery action after a GitOps row gives up (5 failed retries,
    // see DeploymentSchedulerJob): the local clone is already reset/cleaned to the
    // remote branch tip on every attempt, so there's nothing extra to unwind — the next
    // attempt (a fresh redeploy) starts from a known-good state by construction.
    private fun checkout(dir: File, branch: String) {
        git(dir, "checkout", "-B", branch, "origin/$branch")
        git(dir, "reset", "--hard", "origin/$branch")
        git(dir, "clean", "-ffdx")
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private data class GitResult(val exitCode: Int, val output: String)

    private fun git(workingDir: File, vararg args: String, allowFailure: Boolean = false): GitResult {
        val command = mutableListOf("git")
        // Never prompt interactively: if the repo genuinely needs credentials we didn't
        // supply (or supplied wrong), this should fail with a clear, captured error —
        // not hang (or pop up a GUI askpass prompt some credential-helper setups show
        // even when other auth was provided) waiting for input this backend process,
        // running headless, can never give it.
        command += listOf("-c", "credential.helper=")
        if (gitUsername.isNotBlank()) {
            val basic = Base64.getEncoder().encodeToString("$gitUsername:$gitPassword".toByteArray())
            command += listOf("-c", "http.extraHeader=Authorization: Basic $basic")
        }
        command += args
        val process = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(true)
            .apply {
                environment()["GIT_TERMINAL_PROMPT"] = "0"
                environment().remove("GIT_ASKPASS")
                environment().remove("SSH_ASKPASS")
                environment()["SSH_ASKPASS_REQUIRE"] = "never"
            }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0 && !allowFailure) {
            throw GitCommitException("git ${args.joinToString(" ")} failed: ${output.trim()}")
        }
        return GitResult(exitCode, output)
    }
}
