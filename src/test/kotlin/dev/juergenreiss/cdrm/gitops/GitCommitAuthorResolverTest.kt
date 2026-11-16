// Copyright 2026 Dr. Juergen A. Reiss
// SPDX-License-Identifier: Apache-2.0

package dev.juergenreiss.cdrm.gitops

import dev.juergenreiss.cdrm.config.UserDisplayFormat
import dev.juergenreiss.cdrm.config.UserIdStorageConfig
import dev.juergenreiss.cdrm.config.UserIdStorageMode
import dev.juergenreiss.cdrm.config.UserIdStorageSettings
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class GitCommitAuthorResolverTest {

    @Mock
    private lateinit var settings: UserIdStorageSettings

    private val resolver by lazy { GitCommitAuthorResolver(settings) }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    private fun authenticateAs(claims: Map<String, String>) {
        val jwt = Jwt.withTokenValue("token")
            .header("alg", "none")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .claim("sub", "subject")
            .claims { it.putAll(claims) }
            .build()
        SecurityContextHolder.getContext().authentication =
            JwtAuthenticationToken(jwt, listOf(SimpleGrantedAuthority("ROLE_cdrm-developer")))
    }

    private fun storage(mode: UserIdStorageMode, format: UserDisplayFormat) {
        given(settings.current()).willReturn(UserIdStorageConfig(mode, format))
    }

    private val jane = mapOf("given_name" to "Jane", "family_name" to "Doe", "email" to "jane@example.com")

    @Test
    fun `uses the JWT's name and email when the display format includes them`() {
        storage(UserIdStorageMode.USER_UUID, UserDisplayFormat.FIRSTNAME_LASTNAME_EMAIL)
        authenticateAs(jane)

        assertEquals(GitCommitAuthor("Jane Doe", "jane@example.com"), resolver.currentUser())
    }

    @Test
    fun `also for the EMAIL display format`() {
        storage(UserIdStorageMode.USER_UUID, UserDisplayFormat.EMAIL)
        authenticateAs(jane)

        assertEquals(GitCommitAuthor("Jane Doe", "jane@example.com"), resolver.currentUser())
    }

    @Test
    fun `falls back to cdrm when storage mode is NONE`() {
        storage(UserIdStorageMode.NONE, UserDisplayFormat.FIRSTNAME_LASTNAME_EMAIL)
        authenticateAs(jane)

        assertNull(resolver.currentUser())
    }

    @Test
    fun `falls back to cdrm when only the pseudonymous UUID may be stored`() {
        storage(UserIdStorageMode.USER_UUID, UserDisplayFormat.UUID)
        authenticateAs(jane)

        assertNull(resolver.currentUser())
    }

    @Test
    fun `falls back to cdrm without an authenticated user, e g in the scheduler thread`() {
        assertNull(resolver.currentUser())
        verifyNoInteractions(settings)
    }

    @Test
    fun `falls back to cdrm when the token has no email`() {
        storage(UserIdStorageMode.USER_UUID, UserDisplayFormat.FIRSTNAME_LASTNAME_EMAIL)
        authenticateAs(mapOf("given_name" to "Jane", "family_name" to "Doe"))

        assertNull(resolver.currentUser())
    }

    @Test
    fun `uses the email as name when the token has no name claims`() {
        storage(UserIdStorageMode.USER_UUID, UserDisplayFormat.FIRSTNAME_LASTNAME_EMAIL)
        authenticateAs(mapOf("email" to "jane@example.com"))

        assertEquals(GitCommitAuthor("jane@example.com", "jane@example.com"), resolver.currentUser())
    }
}
