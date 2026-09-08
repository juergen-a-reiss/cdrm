// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.notification

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.jacksonObjectMapper

// The cross-instance half of the live-push feature: every instance consumes the same
// Kafka topic ReleaseNotificationPublisher (and, over time, other entities'
// equivalents) publishes to, and relays each message to its own locally connected
// browsers via WebSocketChangeBroadcaster — so a change handled by instance A reaches a
// browser connected to instance B.
//
// groupId is a fresh random UUID per listener (i.e. per instance, per restart) rather
// than a fixed name — a shared group id would give Kafka's normal "exactly one member
// of the group gets each message" behavior, but every instance needs its own full copy
// of every message (broadcast, not load-balanced), since each only knows about the
// browsers connected to itself. A fresh group also has no committed offset to resume
// from, hence auto-offset-reset=latest (see application.yaml) — this is a live signal,
// not a durable log an instance should replay from the beginning on every restart.
//
// Only the `type`/`subject` CloudEvents attributes are read — the `data` payload is
// never parsed here, so this stays correct as more entities' events (with their own,
// different `data` shapes) start flowing through the same topic.
@Component
@ConditionalOnProperty(prefix = "cdrm.notifications.kafka", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class KafkaChangeRelay(private val broadcaster: WebSocketChangeBroadcaster) {

    private val log = LoggerFactory.getLogger(KafkaChangeRelay::class.java)
    private val objectMapper = jacksonObjectMapper()

    @KafkaListener(topics = ["\${cdrm.notifications.kafka.topic}"], groupId = "#{T(java.util.UUID).randomUUID().toString()}")
    fun onMessage(payload: String) {
        try {
            val node = objectMapper.readTree(payload)
            val type = node.get("type")?.asText()
            val subject = node.get("subject")?.asText()
            if (type != null && subject != null) {
                broadcaster.broadcast(type, subject)
            } else {
                log.warn("Ignoring change message with no type/subject: {}", payload)
            }
        } catch (e: Exception) {
            log.warn("Failed to relay Kafka change message to WebSocket clients: {}", e.message)
        }
    }
}
