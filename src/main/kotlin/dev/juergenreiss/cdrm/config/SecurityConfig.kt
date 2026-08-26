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
                authorize(HttpMethod.POST, "/clusters/**", hasRole("cdrm-devops"))
                authorize(HttpMethod.PUT, "/clusters/**", hasRole("cdrm-devops"))
                authorize(HttpMethod.DELETE, "/clusters/**", hasRole("cdrm-devops"))
                authorize("/clusters/**", authenticated)
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
                authorize(HttpMethod.POST, "/releases/*/promote", hasRole("cdrm-productowner"))
                authorize(HttpMethod.POST, "/releases/**", hasAnyRole("cdrm-devops", "cdrm-productowner", "cdrm-developer"))
                authorize(HttpMethod.PUT, "/releases/**", hasAnyRole("cdrm-devops", "cdrm-productowner", "cdrm-developer"))
                authorize(HttpMethod.DELETE, "/releases/**", hasAnyRole("cdrm-devops", "cdrm-productowner", "cdrm-developer"))
                authorize("/releases/**", authenticated)
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
