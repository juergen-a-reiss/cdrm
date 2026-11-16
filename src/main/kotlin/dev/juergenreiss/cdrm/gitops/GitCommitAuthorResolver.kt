// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

package dev.juergenreiss.cdrm.gitops

import dev.juergenreiss.cdrm.config.UserDisplayFormat
import dev.juergenreiss.cdrm.config.UserIdStorageMode
import dev.juergenreiss.cdrm.config.UserIdStorageSettings
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component

// Who a GitOps commit is authored by. The committer is always cdrm itself
// (GitOpsProperties.commitAuthorName/Email) — git's own author/committer split, the same
// way a web-UI edit on a git hosting service records the human as author.
data class GitCommitAuthor(val name: String, val email: String)

// Decides whether a GitOps commit carries the acting cdrm user as its author, or falls
// back to cdrm's own configured identity (null). Follows the user.id.storage setting —
// a git history is permanent and copied to every clone, so a person's name/email only
// ever goes into it when the company has already opted into storing exactly that:
//
// - NONE: never (no identifier is stored anywhere, not even pseudonymous).
// - USER_UUID with display format UUID: never (only a pseudonymous id is stored; a git
//   author needs a name and an email, which this mode deliberately doesn't keep).
// - USER_UUID with a name/email display format: the JWT's given_name/family_name/email
//   — the same claims CurrentActorResolver captures into KnownUser for that format.
//
// Also null when there's no authenticated user at all (DeploymentSchedulerJob's
// background thread: scheduled deploys and retries) or the token has no email claim.
@Component
class GitCommitAuthorResolver(private val settings: UserIdStorageSettings) {

    fun currentUser(): GitCommitAuthor? {
        val jwt = SecurityContextHolder.getContext().authentication?.principal as? Jwt ?: return null
        val config = settings.current()
        if (config.mode == UserIdStorageMode.NONE || config.displayFormat == UserDisplayFormat.UUID) return null
        val email = jwt.getClaimAsString("email")?.takeIf { it.isNotBlank() } ?: return null
        val name = listOfNotNull(jwt.getClaimAsString("given_name"), jwt.getClaimAsString("family_name"))
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { email }
        return GitCommitAuthor(name, email)
    }
}
