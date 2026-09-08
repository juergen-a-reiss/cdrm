// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.security

import dev.juergenreiss.cdrm.config.UserDisplayFormat
import dev.juergenreiss.cdrm.config.UserIdStorageConfig
import dev.juergenreiss.cdrm.config.UserIdStorageMode
import dev.juergenreiss.cdrm.config.UserIdStorageSettings
import dev.juergenreiss.cdrm.user.KnownUser
import dev.juergenreiss.cdrm.user.KnownUserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.domain.AuditorAware
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Instant
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class CurrentActorResolverTest {

    @Mock
    private lateinit var auditorAware: AuditorAware<UUID>

    @Mock
    private lateinit var settings: UserIdStorageSettings

    @Mock
    private lateinit var knownUserRepository: KnownUserRepository

    private val resolver by lazy { CurrentActorResolver(auditorAware, settings, knownUserRepository) }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    private fun authenticateAs(firstName: String, lastName: String, email: String) {
        val jwt = Jwt.withTokenValue("token")
            .header("alg", "none")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .claim("given_name", firstName)
            .claim("family_name", lastName)
            .claim("email", email)
            .build()
        SecurityContextHolder.getContext().authentication =
            JwtAuthenticationToken(jwt, listOf(SimpleGrantedAuthority("ROLE_cdrm-developer")))
    }

    @Test
    fun `resolve returns the real subject and never touches KnownUserRepository when displayFormat is UUID`() {
        val actual = UUID.randomUUID()
        given(auditorAware.currentAuditor).willReturn(Optional.of(actual))
        given(settings.current()).willReturn(UserIdStorageConfig(UserIdStorageMode.USER_UUID, UserDisplayFormat.UUID))

        val result = resolver.resolve()

        assertEquals(actual, result)
        verifyNoInteractions(knownUserRepository)
    }

    @Test
    fun `resolve returns the anonymous placeholder when mode is NONE, without touching KnownUserRepository`() {
        val actual = UUID.randomUUID()
        given(auditorAware.currentAuditor).willReturn(Optional.of(actual))
        given(settings.current()).willReturn(UserIdStorageConfig(UserIdStorageMode.NONE, UserDisplayFormat.FIRSTNAME_LASTNAME_EMAIL))

        val result = resolver.resolve()

        assertEquals(UserIdStorageSettings.ANONYMOUS_USER_ID, result)
        verifyNoInteractions(knownUserRepository)
    }

    @Test
    fun `resolve throws when the current user cannot be determined, regardless of mode`() {
        given(auditorAware.currentAuditor).willReturn(Optional.empty())

        assertThrows(IllegalStateException::class.java) { resolver.resolve() }
    }

    @Test
    fun `resolve captures the acting user's claims into KnownUser when the display format needs them`() {
        val actual = UUID.randomUUID()
        given(auditorAware.currentAuditor).willReturn(Optional.of(actual))
        given(settings.current()).willReturn(UserIdStorageConfig(UserIdStorageMode.USER_UUID, UserDisplayFormat.FIRSTNAME_LASTNAME_EMAIL))
        given(knownUserRepository.findById(actual)).willReturn(Optional.empty())
        authenticateAs("Jane", "Doe", "jane.doe@example.com")

        val result = resolver.resolve()

        assertEquals(actual, result)
        val captor = ArgumentCaptor.forClass(KnownUser::class.java)
        verify(knownUserRepository).save(captor.capture())
        assertEquals(actual, captor.value.id)
        assertEquals("Jane", captor.value.firstName)
        assertEquals("Doe", captor.value.lastName)
        assertEquals("jane.doe@example.com", captor.value.email)
    }

    @Test
    fun `resolve skips the KnownUser write when the captured claims are unchanged`() {
        val actual = UUID.randomUUID()
        given(auditorAware.currentAuditor).willReturn(Optional.of(actual))
        given(settings.current()).willReturn(UserIdStorageConfig(UserIdStorageMode.USER_UUID, UserDisplayFormat.EMAIL))
        given(knownUserRepository.findById(actual))
            .willReturn(Optional.of(KnownUser(id = actual, firstName = "Jane", lastName = "Doe", email = "jane.doe@example.com")))
        authenticateAs("Jane", "Doe", "jane.doe@example.com")

        resolver.resolve()

        verify(knownUserRepository, never()).save(any())
    }

    @Test
    fun `resolve updates the existing KnownUser row when the captured claims changed`() {
        val actual = UUID.randomUUID()
        given(auditorAware.currentAuditor).willReturn(Optional.of(actual))
        given(settings.current()).willReturn(UserIdStorageConfig(UserIdStorageMode.USER_UUID, UserDisplayFormat.EMAIL))
        given(knownUserRepository.findById(actual))
            .willReturn(Optional.of(KnownUser(id = actual, firstName = "Jane", lastName = "Doe", email = "old@example.com")))
        authenticateAs("Jane", "Doe", "new@example.com")

        resolver.resolve()

        val captor = ArgumentCaptor.forClass(KnownUser::class.java)
        verify(knownUserRepository).save(captor.capture())
        assertEquals("new@example.com", captor.value.email)
    }
}
