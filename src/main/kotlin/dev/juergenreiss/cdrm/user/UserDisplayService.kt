// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.user

import dev.juergenreiss.cdrm.config.UserDisplayFormat
import dev.juergenreiss.cdrm.config.UserIdStorageSettings
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// Turns a set of actor UUIDs (whatever a caller already has from a createdBy/modifiedBy
// field elsewhere) into ready-to-show strings, per the currently configured display
// format (see UserIdStorageSettings) — formatting happens entirely here, so callers
// (frontend included) never need to know the format themselves, only render whatever
// string comes back. Read-only: nothing here writes KnownUser rows — see
// CurrentActorResolver for the one place that does.
@Service
@Transactional(readOnly = true)
class UserDisplayService(
    private val repository: KnownUserRepository,
    private val settings: UserIdStorageSettings,
) {

    fun resolve(ids: Set<UUID>): Map<UUID, String> {
        if (ids.isEmpty()) return emptyMap()
        val format = settings.current().displayFormat
        val knownById = if (format == UserDisplayFormat.UUID) emptyMap() else repository.findAllById(ids).associateBy { it.id }
        return ids.associateWith { id ->
            when {
                // Always shown as "Anonymous", regardless of the configured format — a
                // raw "00000000-0000-0000-0000-00005eed0000" would be meaningless to a
                // reader, and this id is shared by every user.id.storage=NONE action, not
                // tied to any one real identity to begin with.
                id == UserIdStorageSettings.ANONYMOUS_USER_ID -> "Anonymous"
                format == UserDisplayFormat.UUID -> id.toString()
                // Not found: either this actor never triggered a capture (e.g. the format
                // was UUID at the time, or this row predates the feature) — fall back to
                // the bare id rather than showing nothing.
                else -> knownById[id]?.let { formatUser(it, format) } ?: id.toString()
            }
        }
    }

    private fun formatUser(user: KnownUser, format: UserDisplayFormat): String {
        val display = when (format) {
            UserDisplayFormat.UUID -> null
            UserDisplayFormat.FIRSTNAME_LASTNAME_EMAIL -> join(join(user.firstName, user.lastName, " "), user.email, ", ")
            UserDisplayFormat.LASTNAME_FIRSTNAME_EMAIL -> join(join(user.lastName, user.firstName, ", "), user.email, ", ")
            UserDisplayFormat.EMAIL -> user.email
        }
        return display?.takeIf { it.isNotBlank() } ?: user.id.toString()
    }

    private fun join(a: String?, b: String?, separator: String): String? {
        val parts = listOf(a, b).filter { !it.isNullOrBlank() }
        return parts.joinToString(separator).ifBlank { null }
    }
}
