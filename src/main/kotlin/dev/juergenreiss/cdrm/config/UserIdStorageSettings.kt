// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.config

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

private const val USER_ID_STORAGE_KEY = "user-id-storage"

// NONE: no user identifier is persisted at all — every created_by/modified_by (and
// AuditRecorder's actor) becomes UserIdStorageSettings.ANONYMOUS_USER_ID instead of the
// real JWT subject, for a company that doesn't want to store even a pseudonymous
// identifier. USER_UUID is today's existing, unchanged behavior.
enum class UserIdStorageMode { NONE, USER_UUID }

// Only meaningful when mode is USER_UUID — how a resolved user id is rendered back to
// callers (see UserDisplayService). UUID needs no extra data; the other three require
// KnownUser rows (see CurrentActorResolver, which is what populates them).
enum class UserDisplayFormat { UUID, FIRSTNAME_LASTNAME_EMAIL, LASTNAME_FIRSTNAME_EMAIL, EMAIL }

data class UserIdStorageConfig(
    val mode: UserIdStorageMode = UserIdStorageMode.USER_UUID,
    val displayFormat: UserDisplayFormat = UserDisplayFormat.UUID,
)

// Reads the "user-id-storage" config entry (see ConfigService/ConfigController — same
// generic key/value mechanism as menu-visibility) directly via ConfigEntryRepository,
// not ConfigService: ConfigService itself resolves the current actor through
// CurrentActorResolver, which in turn depends on this class — going through ConfigService
// here would be a circular bean dependency. Absent or malformed config falls back to
// USER_UUID/UUID, i.e. today's existing behavior, so this feature is fully opt-in.
@Component
class UserIdStorageSettings(private val repository: ConfigEntryRepository) {

    private val log = LoggerFactory.getLogger(UserIdStorageSettings::class.java)
    private val objectMapper = jacksonObjectMapper()

    fun current(): UserIdStorageConfig {
        val entry = repository.findById(USER_ID_STORAGE_KEY).orElse(null) ?: return UserIdStorageConfig()
        return try {
            objectMapper.readValue(entry.value, UserIdStorageConfig::class.java)
        } catch (e: Exception) {
            log.warn("Malformed '{}' config entry, falling back to USER_UUID/UUID: {}", USER_ID_STORAGE_KEY, e.message)
            UserIdStorageConfig()
        }
    }

    companion object {
        // Reuses generate-release-history.py's SYNTHETIC_CREATED_BY marker (development/
        // generate-release-history.py) — one recognizable, obviously-not-a-real-user id
        // shared by both that seed script's rows and "none" mode's real runtime rows.
        val ANONYMOUS_USER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00005eed0000")
    }
}
