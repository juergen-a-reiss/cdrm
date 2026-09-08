// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.audit

import dev.juergenreiss.cdrm.notification.EntityChangedEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.*

// Called by ClusterService/StageService/ProductService/WorkloadService/ConfigService
// after each create/update/delete — synchronously, within the caller's own transaction
// (no @Async, no try/catch here): unlike the Kafka release notifications, an audit
// write is not best-effort — if it fails, the whole operation must roll back with it.
//
// Also publishes EntityChangedEvent (see that class) for the same occurrence — this is
// the one chokepoint all five entity types' create/update/delete already pass through
// with everything a live-UI-update notification needs (entityType, action, id, the
// Response DTO), so it doubles as the trigger for that instead of each service publishing
// it separately. Publishing itself is cheap and synchronous (registering a
// transaction-commit callback), but the event's own listeners
// (EntityChangeNotifier/EntityChangeKafkaPublisher) only run after commit, off the
// request thread, and swallow their own failures — so this can never affect whether the
// audit write (or the caller's action) succeeds.
@Component
class AuditRecorder(
    private val repository: AuditEntryRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {

    private val objectMapper = jacksonObjectMapper()

    fun recordCreate(entityType: AuditEntityType, entityId: String, entityName: String, productName: String?, newState: Any, actor: UUID) {
        record(entityType, entityId, entityName, productName, AuditAction.CREATED, before = null, after = newState, actor = actor)
    }

    fun recordUpdate(
        entityType: AuditEntityType, entityId: String, entityName: String, productName: String?,
        oldState: Any, newState: Any, actor: UUID,
    ) {
        record(entityType, entityId, entityName, productName, AuditAction.UPDATED, before = oldState, after = newState, actor = actor)
    }

    fun recordDelete(entityType: AuditEntityType, entityId: String, entityName: String, productName: String?, oldState: Any, actor: UUID) {
        record(entityType, entityId, entityName, productName, AuditAction.DELETED, before = oldState, after = null, actor = actor)
    }

    private fun record(
        entityType: AuditEntityType, entityId: String, entityName: String, productName: String?,
        action: AuditAction, before: Any?, after: Any?, actor: UUID,
    ) {
        val changes = AuditDiff.compute(before?.let(::toMap), after?.let(::toMap))
        repository.save(
            AuditEntry(
                entityType = entityType,
                entityId = entityId,
                entityName = entityName,
                productName = productName,
                action = action,
                newState = after?.let { objectMapper.writeValueAsString(it) },
                changes = objectMapper.writeValueAsString(changes),
                createdBy = actor,
            )
        )
        // after ?: before: DELETED has no "after", but a live-UI-update listener still
        // needs a snapshot to report (there's nothing newer to show than what existed
        // right before deletion) — unlike AuditEntry.newState, which is null for DELETED.
        eventPublisher.publishEvent(EntityChangedEvent(entityType, action, entityId, (after ?: before)!!))
    }

    @Suppress("UNCHECKED_CAST")
    private fun toMap(value: Any): Map<String, Any?> = objectMapper.convertValue(value, Map::class.java) as Map<String, Any?>
}
