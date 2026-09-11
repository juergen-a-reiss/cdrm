// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.gitops

import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

// NONE: no credential at all — fine for a repo that allows anonymous push (e.g. the
// local dev Gitea demo). BASIC: HTTP Basic auth (username/password or a token as the
// username) — for an http(s):// repo. SSH_KEY: a private key file already present on
// disk (mounted by whoever deploys cdrm) — for a git@host:path/ssh:// repo, the way a
// "serious" (non-dev) environment is expected to authenticate.
enum class GitAuthMode { NONE, BASIC, SSH_KEY }

class GitOpsRepositoryConfig {
    var url: String = ""
    var auth: GitAuthMode = GitAuthMode.NONE
    var username: String? = null
    var password: String? = null
    // A private key file's path on disk — never the key material itself: env vars are a
    // poor place for multi-line PEM content, and a real deployment already has to mount
    // secrets as files for other things. GitCommitClient passes this to ssh via
    // GIT_SSH_COMMAND, it's never read into memory here.
    var sshPrivateKeyPath: String? = null
    // Null uses ssh's own default (~/.ssh/known_hosts, normal host-key checking) — only
    // set this to point at a specific known_hosts file instead.
    var sshKnownHostsPath: String? = null
}

// The set of git repositories cdrm is allowed to push GitOps commits to, and how to
// authenticate with each — one source of truth for both GitCommitClient (which looks up
// the entry matching a GitOpsTarget's repositoryUrl before every push, by exact string
// equality) and the frontend's cluster-editing dropdown (see GitOpsRepositoryController,
// which only ever lists these URLs, never credentials). A cluster's or namespace's own
// gitRepo (see K8sGitopsConfig/K8sNamespaceGitopsConfig) is just a URL string, entered
// via that same dropdown so it can only ever be one of these — GitCommitClient still
// falls back to an anonymous push if it somehow doesn't match (e.g. a repository entry
// removed after a cluster already referenced it), rather than failing the deploy outright.
//
// Configured entirely via env vars in production — Spring's relaxed binding maps indexed
// names like CDRM_GITOPS_REPOSITORIES_0_URL / _0_AUTH / _0_USERNAME / _0_PASSWORD /
// _0_SSH_PRIVATE_KEY_PATH onto repositories[0]'s fields, _1_* onto repositories[1], and
// so on. application-dev.yaml lists the two local Gitea demo repos directly instead,
// since that's simpler for a fixed local setup than one env var per field per repo.
@Component
@ConfigurationProperties(prefix = "cdrm.gitops")
class GitOpsProperties {
    var repositories: MutableList<GitOpsRepositoryConfig> = mutableListOf()

    @PostConstruct
    fun validate() {
        val seenUrls = mutableSetOf<String>()
        for (repo in repositories) {
            require(repo.url.isNotBlank()) { "cdrm.gitops.repositories: every entry needs a url" }
            require(seenUrls.add(repo.url)) { "cdrm.gitops.repositories: duplicate url '${repo.url}'" }
            when (repo.auth) {
                GitAuthMode.NONE -> Unit
                GitAuthMode.BASIC -> require(!repo.username.isNullOrBlank()) {
                    "cdrm.gitops.repositories: '${repo.url}' has auth=BASIC but no username"
                }
                GitAuthMode.SSH_KEY -> require(!repo.sshPrivateKeyPath.isNullOrBlank()) {
                    "cdrm.gitops.repositories: '${repo.url}' has auth=SSH_KEY but no sshPrivateKeyPath"
                }
            }
        }
    }

    fun find(repositoryUrl: String): GitOpsRepositoryConfig? = repositories.find { it.url == repositoryUrl }
}
