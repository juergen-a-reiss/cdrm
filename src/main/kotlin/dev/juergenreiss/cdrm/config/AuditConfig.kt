// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.domain.AuditorAware
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import java.util.*

@Configuration
@EnableJpaAuditing
class AuditConfig {

    // Every entity sets createdBy/modifiedBy manually via its *Service (which throws its
    // own IllegalStateException if unauthenticated) rather than relying on @CreatedBy/
    // @LastModifiedBy — none of those annotations are used anywhere. This bean therefore
    // exists only for entities' @CreatedDate/@LastModifiedDate handling, but Spring Data's
    // auditing machinery unconditionally resolves the current auditor on every
    // @PrePersist/@PreUpdate regardless of whether an entity has an audited "by" field.
    // Background work (DeploymentSchedulerJob) has no SecurityContext, so this must return
    // empty rather than throw, or every scheduled tick would fail before it could run.
    @Bean
    fun auditorProvider(): AuditorAware<UUID> = AuditorAware {
        val jwt = SecurityContextHolder.getContext().authentication?.principal as? Jwt
        jwt?.let { Optional.of(UUID.fromString(it.subject)) } ?: Optional.empty()
    }
}
