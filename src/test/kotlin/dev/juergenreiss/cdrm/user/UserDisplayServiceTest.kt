// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.user

import dev.juergenreiss.cdrm.config.UserDisplayFormat
import dev.juergenreiss.cdrm.config.UserIdStorageConfig
import dev.juergenreiss.cdrm.config.UserIdStorageMode
import dev.juergenreiss.cdrm.config.UserIdStorageSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class UserDisplayServiceTest {

    @Mock
    private lateinit var repository: KnownUserRepository

    @Mock
    private lateinit var settings: UserIdStorageSettings

    private val service by lazy { UserDisplayService(repository, settings) }

    @Test
    fun `resolve returns an empty map for an empty id set without touching either dependency`() {
        val result = service.resolve(emptySet())

        assertEquals(emptyMap<UUID, String>(), result)
    }

    @Test
    fun `resolve returns the raw id string when displayFormat is UUID`() {
        val id = UUID.randomUUID()
        given(settings.current()).willReturn(UserIdStorageConfig(UserIdStorageMode.USER_UUID, UserDisplayFormat.UUID))

        val result = service.resolve(setOf(id))

        assertEquals(id.toString(), result[id])
    }

    @Test
    fun `resolve always shows the anonymous placeholder as 'Anonymous', regardless of format`() {
        given(settings.current()).willReturn(UserIdStorageConfig(UserIdStorageMode.NONE, UserDisplayFormat.FIRSTNAME_LASTNAME_EMAIL))

        val result = service.resolve(setOf(UserIdStorageSettings.ANONYMOUS_USER_ID))

        assertEquals("Anonymous", result[UserIdStorageSettings.ANONYMOUS_USER_ID])
    }

    @Test
    fun `resolve falls back to the raw id string when the actor was never captured`() {
        val id = UUID.randomUUID()
        given(settings.current()).willReturn(UserIdStorageConfig(UserIdStorageMode.USER_UUID, UserDisplayFormat.EMAIL))
        given(repository.findAllById(setOf(id))).willReturn(emptyList())

        val result = service.resolve(setOf(id))

        assertEquals(id.toString(), result[id])
    }

    @Test
    fun `resolve formats FIRSTNAME_LASTNAME_EMAIL as 'First Last, email'`() {
        val id = UUID.randomUUID()
        given(settings.current()).willReturn(UserIdStorageConfig(UserIdStorageMode.USER_UUID, UserDisplayFormat.FIRSTNAME_LASTNAME_EMAIL))
        given(repository.findAllById(setOf(id))).willReturn(listOf(KnownUser(id, "Jane", "Doe", "jane.doe@example.com")))

        val result = service.resolve(setOf(id))

        assertEquals("Jane Doe, jane.doe@example.com", result[id])
    }

    @Test
    fun `resolve formats LASTNAME_FIRSTNAME_EMAIL as 'Last, First, email'`() {
        val id = UUID.randomUUID()
        given(settings.current()).willReturn(UserIdStorageConfig(UserIdStorageMode.USER_UUID, UserDisplayFormat.LASTNAME_FIRSTNAME_EMAIL))
        given(repository.findAllById(setOf(id))).willReturn(listOf(KnownUser(id, "Jane", "Doe", "jane.doe@example.com")))

        val result = service.resolve(setOf(id))

        assertEquals("Doe, Jane, jane.doe@example.com", result[id])
    }

    @Test
    fun `resolve formats EMAIL as just the email`() {
        val id = UUID.randomUUID()
        given(settings.current()).willReturn(UserIdStorageConfig(UserIdStorageMode.USER_UUID, UserDisplayFormat.EMAIL))
        given(repository.findAllById(setOf(id))).willReturn(listOf(KnownUser(id, "Jane", "Doe", "jane.doe@example.com")))

        val result = service.resolve(setOf(id))

        assertEquals("jane.doe@example.com", result[id])
    }

    @Test
    fun `resolve falls back to the raw id string when a known user has no email captured`() {
        val id = UUID.randomUUID()
        given(settings.current()).willReturn(UserIdStorageConfig(UserIdStorageMode.USER_UUID, UserDisplayFormat.EMAIL))
        given(repository.findAllById(setOf(id))).willReturn(listOf(KnownUser(id, "Jane", "Doe", null)))

        val result = service.resolve(setOf(id))

        assertEquals(id.toString(), result[id])
    }
}
