// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.notification

import dev.juergenreiss.cdrm.release.ReleaseHistory
import dev.juergenreiss.cdrm.release.ReleaseHistoryAction
import java.time.Instant
import java.util.UUID

// CloudEvents 1.0 structured-mode envelope (https://cloudevents.io) — the JSON shape
// ReleaseNotificationPublisher sends as a Kafka record's value, per the CloudEvents
// Kafka Protocol Binding's structured content mode. Field names match the spec's own
// attributes (specversion, id, source, ...) rather than this codebase's usual
// camelCase-everywhere style, since external consumers rely on that exact vocabulary.
data class ReleaseHistoryCloudEvent(
    val specversion: String = "1.0",
    val id: String,
    val source: String,
    val type: String,
    val time: String,
    val datacontenttype: String = "application/json",
    val subject: String,
    val data: ReleaseHistoryEventData,
)

// The CloudEvents "data" payload — a snapshot of the release_history row the event
// describes, not a live lookup, so a consumer sees exactly what cdrm recorded even if
// the release/workload/product is later changed or deleted.
data class ReleaseHistoryEventData(
    val historyEntryId: UUID,
    val releaseId: UUID,
    val action: ReleaseHistoryAction,
    val image: String,
    val productId: UUID,
    val productName: String,
    val workloadId: UUID?,
    val workloadName: String,
    val stageId: UUID,
    val stageName: String,
    val pipeline: String?,
    val deployedAt: Instant?,
    val deployError: String?,
    val gitopsError: String?,
    val createdBy: UUID,
    val createdAt: Instant,
)

fun ReleaseHistory.toEventData() = ReleaseHistoryEventData(
    historyEntryId = id!!,
    releaseId = releaseId,
    action = action,
    image = image,
    productId = productId,
    productName = productName,
    workloadId = workloadId,
    workloadName = workloadName,
    stageId = stageId,
    stageName = stageName,
    pipeline = pipeline,
    deployedAt = deployedAt,
    deployError = deployError,
    gitopsError = gitopsError,
    createdBy = createdBy,
    createdAt = createdAt!!,
)
