// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class UserIdStorageSettingsTest {

    @Mock
    private lateinit var repository: ConfigEntryRepository

    private val settings by lazy { UserIdStorageSettings(repository) }

    @Test
    fun `current defaults to USER_UUID-UUID when no config entry exists`() {
        given(repository.findById("user-id-storage")).willReturn(Optional.empty())

        val result = settings.current()

        assertEquals(UserIdStorageConfig(UserIdStorageMode.USER_UUID, UserDisplayFormat.UUID), result)
    }

    @Test
    fun `current parses a stored config entry`() {
        val actor = UUID.randomUUID()
        val entry = ConfigEntry(
            key = "user-id-storage",
            value = """{"mode":"NONE","displayFormat":"EMAIL"}""",
            createdBy = actor,
            modifiedBy = actor,
        )
        given(repository.findById("user-id-storage")).willReturn(Optional.of(entry))

        val result = settings.current()

        assertEquals(UserIdStorageConfig(UserIdStorageMode.NONE, UserDisplayFormat.EMAIL), result)
    }

    @Test
    fun `current falls back to the default when the stored value is malformed`() {
        val actor = UUID.randomUUID()
        val entry = ConfigEntry(key = "user-id-storage", value = "not json", createdBy = actor, modifiedBy = actor)
        given(repository.findById("user-id-storage")).willReturn(Optional.of(entry))

        val result = settings.current()

        assertEquals(UserIdStorageConfig(UserIdStorageMode.USER_UUID, UserDisplayFormat.UUID), result)
    }
}
