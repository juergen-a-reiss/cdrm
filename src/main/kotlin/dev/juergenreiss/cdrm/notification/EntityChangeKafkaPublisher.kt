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

// Forwards Cluster/Stage/Product/Workload/Config change events to Kafka as a
// CloudEvents-shaped notification — the EntityChangedEvent counterpart of
// ReleaseNotificationPublisher (see its docs for the shared resilience shape and for why
// the topic is generic, not release-specific). Kept as its own class for the same reason
// as EntityChangeNotifier: one event type per @TransactionalEventListener method.
@Component
@ConditionalOnProperty(prefix = "cdrm.notifications.kafka", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class EntityChangeKafkaPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    @Value("\${cdrm.notifications.kafka.topic}") private val topic: String,
    @Value("\${cdrm.notifications.source}") private val source: String,
) {

    private val log = LoggerFactory.getLogger(EntityChangeKafkaPublisher::class.java)
    private val objectMapper = jacksonObjectMapper()

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onEntityChanged(event: EntityChangedEvent) {
        try {
            publish(event)
        } catch (e: Exception) {
            log.warn("Failed to publish {} notification for {}: {}", event.entityType, event.subject, e.message)
        }
    }

    private fun publish(event: EntityChangedEvent) {
        val cloudEvent = EntityChangeCloudEvent(
            id = UUID.randomUUID().toString(),
            source = source,
            type = event.cloudEventType(),
            time = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            subject = event.subject,
            data = event.data,
        )
        val json = objectMapper.writeValueAsString(cloudEvent)
        val record = ProducerRecord(topic, event.subject, json)
        // Per the CloudEvents Kafka Protocol Binding's structured content mode: the
        // whole event is the record value, flagged by this content-type header.
        record.headers().add("content-type", "application/cloudevents+json; charset=UTF-8".toByteArray(StandardCharsets.UTF_8))
        kafkaTemplate.send(record).whenComplete { result, ex ->
            if (ex != null) {
                log.warn("Kafka send failed for {} {} (topic '{}'): {}", event.entityType, event.subject, topic, ex.message)
            } else {
                log.debug("Published {} notification for {} to {}", event.entityType, event.subject, result.recordMetadata)
            }
        }
    }
}
