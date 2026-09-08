// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.security

import dev.juergenreiss.cdrm.config.UserDisplayFormat
import dev.juergenreiss.cdrm.config.UserIdStorageMode
import dev.juergenreiss.cdrm.config.UserIdStorageSettings
import dev.juergenreiss.cdrm.user.KnownUser
import dev.juergenreiss.cdrm.user.KnownUserRepository
import org.springframework.data.domain.AuditorAware
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import java.util.UUID

// The single place every *Service's currentUserId() resolves the acting user through —
// wraps the raw JWT-subject AuditorAware<UUID> bean (still used, unmodified, by JPA's own
// @CreatedDate/@LastModifiedDate auditing — see AuditConfig, which has nothing to do with
// created_by/modified_by) with the user.id.storage setting:
//
// - NONE: returns UserIdStorageSettings.ANONYMOUS_USER_ID instead of the real subject, so
//   no personal or even pseudonymous identifier is ever persisted as an actor. The JWT's
//   name/email claims are never even read in this case.
// - USER_UUID: returns the real subject, same as before this feature existed. If the
//   configured display format needs more than the bare UUID, this is also the one place
//   that captures the acting user's name/email into KnownUser — exactly when, and only
//   when, that UUID is about to be written somewhere as an actor, which is the only
//   reason it would ever need to be displayed later.
@Component
class CurrentActorResolver(
    private val auditorAware: AuditorAware<UUID>,
    private val settings: UserIdStorageSettings,
    private val knownUserRepository: KnownUserRepository,
) {

    fun resolve(): UUID {
        val actual = auditorAware.currentAuditor.orElseThrow { IllegalStateException("Current user could not be determined") }
        val config = settings.current()
        if (config.mode == UserIdStorageMode.NONE) return UserIdStorageSettings.ANONYMOUS_USER_ID
        if (config.displayFormat != UserDisplayFormat.UUID) captureKnownUser(actual)
        return actual
    }

    private fun captureKnownUser(id: UUID) {
        val jwt = SecurityContextHolder.getContext().authentication?.principal as? Jwt ?: return
        val firstName = jwt.getClaimAsString("given_name")
        val lastName = jwt.getClaimAsString("family_name")
        val email = jwt.getClaimAsString("email")
        val existing = knownUserRepository.findById(id).orElse(null)
        if (existing != null && existing.firstName == firstName && existing.lastName == lastName && existing.email == email) {
            return
        }
        if (existing != null) {
            existing.firstName = firstName
            existing.lastName = lastName
            existing.email = email
            knownUserRepository.save(existing)
        } else {
            knownUserRepository.save(KnownUser(id = id, firstName = firstName, lastName = lastName, email = email))
        }
    }
}
