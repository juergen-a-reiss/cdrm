// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.security

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Instant

class RebacContextTest {

    private val rebac = RebacContext()

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    private fun authenticateAs(roles: List<String>, claims: Map<String, Any> = emptyMap()) {
        val jwt = Jwt.withTokenValue("token")
            .header("alg", "none")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .claims { it.putAll(claims) }
            .build()
        val authorities = roles.map { SimpleGrantedAuthority("ROLE_$it") }
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt, authorities)
    }

    @Test
    fun `allowsReleaseAction returns null when cdrm-release-actions is unset, so the caller falls back to its role default`() {
        authenticateAs(roles = listOf("cdrm-productowner"))

        assertNull(rebac.allowsReleaseAction(ReleaseActionClaim.PROMOTE, "qa"))
    }

    @Test
    fun `allowsReleaseAction returns null for an action the claim never mentions, even though the claim is set`() {
        authenticateAs(
            roles = listOf("cdrm-manager"),
            claims = mapOf("cdrm-release-actions" to listOf("delete: qa")),
        )

        assertNull(rebac.allowsReleaseAction(ReleaseActionClaim.PROMOTE, "qa"))
    }

    @Test
    fun `allowsReleaseAction returns true for a stage listed under a configured action`() {
        authenticateAs(
            roles = listOf("cdrm-manager"),
            claims = mapOf("cdrm-release-actions" to listOf("promote: dev, qa")),
        )

        assertEquals(true, rebac.allowsReleaseAction(ReleaseActionClaim.PROMOTE, "qa"))
    }

    @Test
    fun `allowsReleaseAction returns false for a stage not listed under a configured action`() {
        authenticateAs(
            roles = listOf("cdrm-productowner"),
            claims = mapOf("cdrm-release-actions" to listOf("promote: dev, qa")),
        )

        assertEquals(false, rebac.allowsReleaseAction(ReleaseActionClaim.PROMOTE, "prod"))
    }

    @Test
    fun `allowsReleaseAction returns true unconditionally for cdrm-devops, regardless of the claim`() {
        authenticateAs(
            roles = listOf("cdrm-devops"),
            claims = mapOf("cdrm-release-actions" to listOf("promote: dev")),
        )

        assertEquals(true, rebac.allowsReleaseAction(ReleaseActionClaim.DELETE, "prod"))
    }

    @Test
    fun `allowsReleaseAction returns null when there is no authentication at all`() {
        assertNull(rebac.allowsReleaseAction(ReleaseActionClaim.PROMOTE, "qa"))
    }

    @Test
    fun `hasRole is false for a non-JWT authentication with no matching authority`() {
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken("user", "n/a")

        assertEquals(false, rebac.hasRole("cdrm-devops"))
    }
}
