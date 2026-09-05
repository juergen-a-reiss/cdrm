// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.gitops

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

class GitCommitException(message: String) : RuntimeException(message)

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
) {
    private val log = LoggerFactory.getLogger(GitCommitClient::class.java)

    // One local clone per repo URL, fast-forwarded before every use — a
    // ReentrantLock per repo so two concurrent deploys touching the same repo (even
    // different files/branches) never race on the same working tree.
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    // Returns null on success, else a short human-readable reason — same contract
    // KubernetesDeploymentClient.patchImage's caller (DeploymentExecutor) already uses.
    fun commitImageChange(target: GitOpsTarget, image: String, commitMessage: String): String? {
        val lock = locks.computeIfAbsent(target.repositoryUrl) { ReentrantLock() }
        lock.lock()
        try {
            val dir = ensureClone(target.repositoryUrl)
            checkout(dir, target.branch)

            val file = File(dir, target.filePath)
            if (!file.isFile) return "file '${target.filePath}' not found in repo"
            val yaml = Yaml(DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK })
            @Suppress("UNCHECKED_CAST")
            val root = (yaml.load(file.readText()) as? MutableMap<String, Any?>)
                ?: return "'${target.filePath}' is not a YAML mapping"
            try {
                YamlPathEditor.setValue(root, target.yamlKeyPath, image)
            } catch (e: IllegalArgumentException) {
                return "yamlKeyPath '${target.yamlKeyPath}' in '${target.filePath}': ${e.message}"
            }
            file.writeText(yaml.dump(root))

            git(dir, "add", target.filePath)
            val commit = git(dir, "commit", "-m", commitMessage, allowFailure = true)
            if (commit.exitCode != 0) {
                if ("nothing to commit" in commit.output) return null
                return "git commit failed: ${commit.output.trim()}"
            }
            git(dir, "push", "origin", target.branch)
            log.info("Committed {} = '{}' to {}#{} ({})", target.yamlKeyPath, image, target.repositoryUrl, target.branch, target.filePath)
            return null
        } catch (e: GitCommitException) {
            log.error("GitOps commit failed for {}: {}", target.repositoryUrl, e.message)
            return e.message
        } finally {
            lock.unlock()
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
