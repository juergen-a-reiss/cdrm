// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.notification

import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

// Forwards release-history events — the four release-API actions themselves
// (create/promote/rollback/redeploy, see ReleaseService.recordHistory()) and the
// terminal deployed/deploy-failed outcome the background jobs later conclude (see
// DeploymentSchedulerJob, DeploymentVerificationJob) — to Kafka as a CloudEvents-shaped
// notification. This is the external/durable feed (enterprise integration, and via
// KafkaChangeRelay, other instances' live-push) — WebSocketChangeNotifier handles
// pushing the same occurrence to *this* instance's own connected browsers, unconditionally
// and independently of whether this bean even exists.
//
// Entirely best-effort by construction:
// - Only exists at all when cdrm.notifications.kafka.enabled is true (see
//   @ConditionalOnProperty below) — when false, this bean, and with it any Kafka
//   activity whatsoever, simply isn't created.
// - Runs after the triggering transaction has already committed
//   (@TransactionalEventListener(AFTER_COMMIT)), so a slow or unreachable broker can
//   never roll back or delay the release action that triggered it.
// - Runs off the request thread entirely (@Async — see AsyncConfig's dedicated,
//   bounded executor).
// - Never lets an exception escape onReleaseHistoryRecorded — Kafka being down,
//   unresolvable, or misconfigured is caught and logged at WARN, not left to surface as
//   an alarming stack trace for what is, by design, an optional side channel.
@Component
@ConditionalOnProperty(prefix = "cdrm.notifications.kafka", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ReleaseNotificationPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    @Value("\${cdrm.notifications.kafka.topic}") private val topic: String,
    @Value("\${cdrm.notifications.source}") private val source: String,
) {

    private val log = LoggerFactory.getLogger(ReleaseNotificationPublisher::class.java)
    private val objectMapper = jacksonObjectMapper()

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onReleaseHistoryRecorded(event: ReleaseHistoryRecordedEvent) {
        try {
            publish(event)
        } catch (e: Exception) {
            log.warn("Failed to publish release-history notification for entry {}: {}", event.entry.id, e.message)
        }
    }

    private fun publish(event: ReleaseHistoryRecordedEvent) {
        val entry = event.entry
        val type = event.cloudEventType()
        // RECORDED's occurrence time is when the row was created; DEPLOYED/DEPLOY_FAILED/
        // GITOPS_PUSHED instead reflect when the background job actually concluded that
        // outcome — createdAt there could be minutes (SCHEDULED policy: much longer) in
        // the past.
        val time = when (event.kind) {
            ReleaseHistoryNotificationKind.RECORDED -> entry.createdAt
            ReleaseHistoryNotificationKind.DEPLOYED, ReleaseHistoryNotificationKind.DEPLOY_FAILED ->
                entry.deploymentFinished ?: entry.deployedAt ?: entry.createdAt
            ReleaseHistoryNotificationKind.GITOPS_PUSHED -> entry.deployedAt ?: entry.createdAt
        } ?: Instant.now()
        val cloudEvent = ReleaseHistoryCloudEvent(
            id = UUID.randomUUID().toString(),
            source = source,
            type = type,
            time = DateTimeFormatter.ISO_INSTANT.format(time),
            subject = entry.releaseId.toString(),
            data = entry.toEventData(),
        )
        val json = objectMapper.writeValueAsString(cloudEvent)
        val record = ProducerRecord(topic, entry.releaseId.toString(), json)
        // Per the CloudEvents Kafka Protocol Binding's structured content mode: the
        // whole event is the record value, flagged by this content-type header.
        record.headers().add("content-type", "application/cloudevents+json; charset=UTF-8".toByteArray(StandardCharsets.UTF_8))
        kafkaTemplate.send(record).whenComplete { result, ex ->
            if (ex != null) {
                log.warn("Kafka send failed for release-history entry {} (topic '{}'): {}", entry.id, topic, ex.message)
            } else {
                log.debug("Published release-history notification for entry {} to {}", entry.id, result.recordMetadata)
            }
        }
    }
}
