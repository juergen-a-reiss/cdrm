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

// Commits a GitOpsTarget's edit(s) into a GitOps repo instead of patching Kubernetes
// directly (see GitOpsResolver) — ArgoCD (or similar) reconciles the cluster from what
// this pushes. Looks up how to authenticate with a given target's repositoryUrl in
// GitOpsProperties (cdrm.gitops.repositories — a list, since more than one repo can now
// be configured, one per GitOpsRepositoryConfig.auth: NONE/BASIC/SSH_KEY); a repo with no
// matching entry is pushed to anonymously rather than failing the deploy outright.
// Credentials are applied per git invocation (`-c http.extraHeader=...` for BASIC,
// GIT_SSH_COMMAND for SSH_KEY), never written into the clone's .git/config.
// application-dev.yaml lists the local Gitea demo's two repos directly; a real deployment
// configures cdrm.gitops.repositories entirely via env vars (see GitOpsProperties).
@Component
class GitCommitClient(
    @Value("\${cdrm.gitops.work-dir:\${java.io.tmpdir}/cdrm-gitops}") private val workDir: String,
    private val properties: GitOpsProperties,
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
    fun commit(target: GitOpsTarget, commitMessage: String): GitCommitResult {
        return try {
            lockTransaction.execute {
                jdbcTemplate.queryForObject("select id from git_lock where id = 1 for update nowait", Int::class.java)
                doCommit(target, commitMessage)
            } ?: GitCommitResult.Failed("git lock transaction returned no result")
        } catch (e: CannotAcquireLockException) {
            log.info("GitOps lock busy — another git operation is in progress, will retry ({})", target.repositoryUrl)
            GitCommitResult.LockBusy
        }
    }

    private fun doCommit(target: GitOpsTarget, commitMessage: String): GitCommitResult {
        val repoConfig = properties.find(target.repositoryUrl)
        return try {
            val dir = ensureClone(target.repositoryUrl, repoConfig)
            // A single git commit belongs to exactly one branch — a template that
            // spreads edits across branches needs one checkout/commit/push per branch,
            // all edits for a branch applied together (grouped further by file below,
            // so "more edits per file" load/modify/write each file exactly once rather
            // than once per edit and clobbering an earlier edit to the same file).
            for ((branch, branchEdits) in target.edits.groupBy { it.branch }) {
                checkout(dir, branch, repoConfig)
                for ((filePath, fileEdits) in branchEdits.groupBy { it.filePath }) {
                    val file = File(dir, filePath)
                    if (!file.isFile) return GitCommitResult.Failed("file '$filePath' not found in repo")
                    val yaml = Yaml(DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK })
                    @Suppress("UNCHECKED_CAST")
                    val root = (yaml.load(file.readText()) as? MutableMap<String, Any?>)
                        ?: return GitCommitResult.Failed("'$filePath' is not a YAML mapping")
                    for (edit in fileEdits) {
                        try {
                            YamlPathEditor.setValue(root, edit.yamlKeyPath, edit.value)
                        } catch (e: IllegalArgumentException) {
                            return GitCommitResult.Failed("yamlKeyPath '${edit.yamlKeyPath}' in '$filePath': ${e.message}")
                        }
                    }
                    file.writeText(yaml.dump(root))
                }
                git(dir, repoConfig, "add", "-A")
                val commit = git(dir, repoConfig, "commit", "-m", commitMessage, allowFailure = true)
                if (commit.exitCode != 0) {
                    if ("nothing to commit" in commit.output) continue
                    return GitCommitResult.Failed("git commit failed: ${commit.output.trim()}")
                }
                git(dir, repoConfig, "push", "origin", branch)
                log.info("Committed {} edit(s) to {}#{}", branchEdits.size, target.repositoryUrl, branch)
            }
            GitCommitResult.Success
        } catch (e: GitCommitException) {
            log.error("GitOps commit failed for {}: {}", target.repositoryUrl, e.message)
            GitCommitResult.Failed(e.message ?: "git operation failed")
        }
    }

    private fun ensureClone(repositoryUrl: String, repoConfig: GitOpsRepositoryConfig?): File {
        val dir = File(workDir, sha256(repositoryUrl))
        if (File(dir, ".git").isDirectory) {
            git(dir, repoConfig, "fetch", "origin")
        } else {
            dir.parentFile.mkdirs()
            git(dir.parentFile, repoConfig, "clone", repositoryUrl, dir.name)
        }
        return dir
    }

    // Also the terminal recovery action after a GitOps row gives up (5 failed retries,
    // see DeploymentSchedulerJob): the local clone is already reset/cleaned to the
    // remote branch tip on every attempt, so there's nothing extra to unwind — the next
    // attempt (a fresh redeploy) starts from a known-good state by construction.
    private fun checkout(dir: File, branch: String, repoConfig: GitOpsRepositoryConfig?) {
        git(dir, repoConfig, "checkout", "-B", branch, "origin/$branch")
        git(dir, repoConfig, "reset", "--hard", "origin/$branch")
        git(dir, repoConfig, "clean", "-ffdx")
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private data class GitResult(val exitCode: Int, val output: String)

    private fun git(workingDir: File, repoConfig: GitOpsRepositoryConfig?, vararg args: String, allowFailure: Boolean = false): GitResult {
        val command = mutableListOf("git")
        // Never prompt interactively: if the repo genuinely needs credentials we didn't
        // supply (or supplied wrong), this should fail with a clear, captured error —
        // not hang (or pop up a GUI askpass prompt some credential-helper setups show
        // even when other auth was provided) waiting for input this backend process,
        // running headless, can never give it.
        command += listOf("-c", "credential.helper=")
        if (repoConfig?.auth == GitAuthMode.BASIC) {
            val basic = Base64.getEncoder().encodeToString("${repoConfig.username}:${repoConfig.password.orEmpty()}".toByteArray())
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
                // IdentitiesOnly: only try this one key, never fall back to the process's
                // own ssh-agent/default keys — a repo's auth must be exactly what's
                // configured for it, not whatever identity happens to be available.
                if (repoConfig?.auth == GitAuthMode.SSH_KEY) {
                    val knownHosts = repoConfig.sshKnownHostsPath?.let { " -o UserKnownHostsFile=$it" } ?: ""
                    environment()["GIT_SSH_COMMAND"] = "ssh -i ${repoConfig.sshPrivateKeyPath} -o IdentitiesOnly=yes$knownHosts"
                }
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
