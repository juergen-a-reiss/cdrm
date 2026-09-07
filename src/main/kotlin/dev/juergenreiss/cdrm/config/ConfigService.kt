// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.config

import org.slf4j.LoggerFactory
import org.springframework.data.domain.AuditorAware
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.*

@Service
@Transactional(readOnly = true)
class ConfigService(
    private val repository: ConfigEntryRepository,
    private val currentUser: AuditorAware<UUID>,
) {

    private val log = LoggerFactory.getLogger(ConfigService::class.java)
    private val objectMapper = jacksonObjectMapper()

    fun findAll(): List<ConfigResponse> = repository.findAll().map { it.toResponse() }

    fun findByKey(key: String): ConfigResponse =
        repository.findById(key).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }.toResponse()

    @Transactional
    fun upsert(key: String, request: ConfigUpdateRequest): ConfigResponse {
        val userId = currentUserId()
        val serialized = objectMapper.writeValueAsString(request.value)
        val existing = repository.findById(key).orElse(null)
        val saved = if (existing != null) {
            existing.value = serialized
            existing.modifiedBy = userId
            repository.save(existing)
        } else {
            repository.save(ConfigEntry(key = key, value = serialized, createdBy = userId, modifiedBy = userId))
        }
        log.info("Updated config entry '{}' by user {}", key, userId)
        return saved.toResponse()
    }

    private fun currentUserId(): UUID =
        currentUser.currentAuditor.orElseThrow { IllegalStateException("Current user could not be determined") }

    private fun ConfigEntry.toResponse() = ConfigResponse(
        key = key,
        value = objectMapper.readValue(value, Any::class.java),
        createdAt = createdAt!!,
        modifiedAt = modifiedAt!!,
        createdBy = createdBy,
        modifiedBy = modifiedBy,
    )
}
