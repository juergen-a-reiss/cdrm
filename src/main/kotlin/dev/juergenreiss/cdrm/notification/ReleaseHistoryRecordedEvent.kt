// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.notification

import dev.juergenreiss.cdrm.release.ReleaseHistory
import dev.juergenreiss.cdrm.release.ReleaseHistoryAction

// RECORDED: one of the four release-API actions itself (create/promote/rollback/
// redeploy — see ReleaseService.recordHistory()) — the CloudEvents "type" is derived
// from the entry's action (see cloudEventType() below).
//
// DEPLOYED / DEPLOY_FAILED: a background job (DeploymentSchedulerJob,
// DeploymentVerificationJob) concluding — successfully or not — whether the binary
// this row describes is now actually running at its stage. This is deliberately only
// fired on a *terminal* outcome (entry.deploymentFinished newly set), not on every
// intermediate retry — an indefinitely-retrying failed deploy would otherwise notify
// once per scheduler tick forever.
enum class ReleaseHistoryNotificationKind { RECORDED, DEPLOYED, DEPLOY_FAILED }

// Published right after a release_history row is created or reaches a reportable
// state change. Two independent listeners consume it, both only after the enclosing
// transaction commits, so neither's failure (nor Kafka being unreachable) can affect
// whether the action that produced it succeeds:
// - WebSocketChangeNotifier: always active, pushes a live-refresh signal to this
//   instance's own connected browsers.
// - ReleaseNotificationPublisher: active only when Kafka notifications are enabled,
//   forwards the same occurrence to Kafka as a CloudEvents message — which, via
//   KafkaChangeRelay, is also how *other* instances' browsers learn about it.
class ReleaseHistoryRecordedEvent(
    val entry: ReleaseHistory,
    val kind: ReleaseHistoryNotificationKind = ReleaseHistoryNotificationKind.RECORDED,
)

// The CloudEvents "type" this occurrence is reported under — shared by both listeners
// above so the Kafka message and the same-instance WebSocket push always agree, and by
// KafkaChangeRelay's frontend-facing counterpart (the type prefix a browser matches on).
fun ReleaseHistoryRecordedEvent.cloudEventType(): String = when (kind) {
    ReleaseHistoryNotificationKind.RECORDED -> "dev.juergenreiss.cdrm.release-history.${entry.action.eventTypeSuffix()}"
    ReleaseHistoryNotificationKind.DEPLOYED -> "dev.juergenreiss.cdrm.release-history.deployed"
    ReleaseHistoryNotificationKind.DEPLOY_FAILED -> "dev.juergenreiss.cdrm.release-history.deploy-failed"
}

private fun ReleaseHistoryAction.eventTypeSuffix(): String = when (this) {
    ReleaseHistoryAction.CREATED -> "created"
    ReleaseHistoryAction.PROMOTED -> "promoted"
    ReleaseHistoryAction.ROLLED_BACK -> "rolled-back"
    ReleaseHistoryAction.REDEPLOYED -> "redeployed"
}
