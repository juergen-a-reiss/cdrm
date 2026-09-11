// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.SecurityFilterChain

@Configuration
class SecurityConfig(
    @Value("\${cdrm.security.client-id}") private val clientId: String,
) {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                authorize("/v3/api-docs/**", permitAll)
                authorize("/swagger-ui.html", permitAll)
                authorize("/swagger-ui/**", permitAll)
                authorize("/actuator/health/**", permitAll)
                authorize("/actuator/metrics/**", permitAll)
                authorize("/actuator/prometheus", permitAll)
                // The initial HTTP handshake for the push-notification WebSocket is left
                // unauthenticated here on purpose — a browser's WebSocket API can't set an
                // Authorization header on it. WebSocketConfig's STOMP CONNECT interceptor
                // is the actual gate: it validates the JWT (sent as a STOMP frame header,
                // not an HTTP one) before accepting the connection.
                authorize("/ws/**", permitAll)
                authorize(HttpMethod.POST, "/clusters/**", hasRole("cdrm-devops"))
                authorize(HttpMethod.PUT, "/clusters/**", hasRole("cdrm-devops"))
                authorize(HttpMethod.DELETE, "/clusters/**", hasRole("cdrm-devops"))
                authorize("/clusters/**", authenticated)
                // Just the configured repo URLs (no credentials) for the cluster-editing
                // screen's dropdown — same access level as writing a cluster itself.
                authorize("/gitops/**", hasRole("cdrm-devops"))
                authorize(HttpMethod.POST, "/stages/**", hasRole("cdrm-devops"))
                authorize(HttpMethod.PUT, "/stages/**", hasRole("cdrm-devops"))
                authorize(HttpMethod.DELETE, "/stages/**", hasRole("cdrm-devops"))
                authorize("/stages/**", authenticated)
                authorize(HttpMethod.POST, "/products/**", hasAnyRole("cdrm-devops", "cdrm-productowner"))
                authorize(HttpMethod.PUT, "/products/**", hasAnyRole("cdrm-devops", "cdrm-productowner"))
                authorize(HttpMethod.DELETE, "/products/**", hasAnyRole("cdrm-devops", "cdrm-productowner"))
                authorize("/products/**", authenticated)
                authorize(HttpMethod.POST, "/workloads/**", hasAnyRole("cdrm-devops", "cdrm-productowner", "cdrm-developer"))
                authorize(HttpMethod.PUT, "/workloads/**", hasAnyRole("cdrm-devops", "cdrm-productowner", "cdrm-developer"))
                authorize(HttpMethod.DELETE, "/workloads/**", hasAnyRole("cdrm-devops", "cdrm-productowner", "cdrm-developer"))
                authorize("/workloads/**", authenticated)
                // Write access to releases is decided in ReleaseService, not here: the
                // cdrm-release-actions ReBAC attribute (see README), when a user has it,
                // is authoritative over the role-based default below — it can grant
                // access a role wouldn't otherwise have (e.g. cdrm-manager, who has no
                // baseline release permission) as well as narrow one that would. This
                // gate only needs to require a logged-in cdrm user.
                authorize("/releases/**", authenticated)
                // Any authenticated user needs their own effective menu; only cdrm-devops
                // may read/edit the underlying config entries (see the Configuration view).
                authorize("/menu-visibility", authenticated)
                authorize("/config/**", hasRole("cdrm-devops"))
                // Read-only, no role restriction here — AuditService itself applies
                // ReBAC (cdrm-products/cdrm-workloads) to PRODUCT/WORKLOAD rows; every
                // other entity type's audit trail is visible the same way that entity
                // itself already generally is.
                authorize("/audit/**", authenticated)
                // Resolves any actor id to a display string per the current
                // user.id.storage setting — a company-directory-style lookup, not a
                // per-entity permission (see UserDisplayController).
                authorize("/users/**", authenticated)
                authorize(anyRequest, authenticated)
            }
            oauth2ResourceServer {
                jwt {
                    jwtAuthenticationConverter = keycloakJwtAuthenticationConverter()
                }
            }
        }
        return http.build()
    }

    private fun keycloakJwtAuthenticationConverter(): JwtAuthenticationConverter {
        val converter = JwtAuthenticationConverter()
        converter.setJwtGrantedAuthoritiesConverter { jwt ->
            val resourceAccess = jwt.getClaimAsMap("resource_access") ?: emptyMap()
            val clientAccess = resourceAccess[clientId] as? Map<*, *>
            val roles = clientAccess?.get("roles") as? Collection<*> ?: emptyList<Any>()
            roles.map { SimpleGrantedAuthority("ROLE_$it") }
        }
        return converter
    }
}
