// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.notification

import dev.juergenreiss.cdrm.audit.AuditAction
import dev.juergenreiss.cdrm.audit.AuditEntityType

// Published by AuditRecorder for every create/update/delete of a Cluster/Stage/Product/
// Workload/Config entry — the very same occurrence audit_entry already records, reused
// here to drive live UI updates. Two listeners consume it, both only after the enclosing
// transaction commits, mirroring ReleaseHistoryRecordedEvent's shape exactly:
// - EntityChangeNotifier: always active, pushes a live-refresh signal to this instance's
//   own connected browsers.
// - EntityChangeKafkaPublisher: active only when Kafka notifications are enabled,
//   forwards the same occurrence to Kafka as a CloudEvents message — which, via
//   KafkaChangeRelay, is also how *other* instances' browsers learn about it.
//
// data is the full entity snapshot (the same Response DTO already computed for the audit
// row: newState for CREATED/UPDATED, the prior state for DELETED — there's nothing newer
// to show) — never null, unlike AuditEntry.newState which is null for a delete.
data class EntityChangedEvent(
    val entityType: AuditEntityType,
    val action: AuditAction,
    val subject: String,
    val data: Any,
)

// The CloudEvents "type" this occurrence is reported under — shared by both listeners
// above so the Kafka message and the same-instance WebSocket push always agree, and by
// KafkaChangeRelay's frontend-facing counterpart (the type prefix a browser matches on).
fun EntityChangedEvent.cloudEventType(): String = "dev.juergenreiss.cdrm.${entityType.eventTypeSegment()}.${action.eventTypeSuffix()}"

private fun AuditEntityType.eventTypeSegment(): String = when (this) {
    AuditEntityType.CLUSTER -> "cluster"
    AuditEntityType.STAGE -> "stage"
    AuditEntityType.PRODUCT -> "product"
    AuditEntityType.WORKLOAD -> "workload"
    AuditEntityType.CONFIG -> "config"
}

private fun AuditAction.eventTypeSuffix(): String = when (this) {
    AuditAction.CREATED -> "created"
    AuditAction.UPDATED -> "updated"
    AuditAction.DELETED -> "deleted"
}
