// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.notification

import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import java.time.Instant

// A push message telling connected frontends that something changed — deliberately
// carrying no entity data, only the same `type`/`subject` a consumer of the Kafka
// CloudEvents feed would see (see ReleaseHistoryCloudEvent). The frontend decides what
// to refetch based on `type`'s prefix and refetches it through its own already-
// authorized REST call, which re-applies real ReBAC filtering — so this broadcast
// itself needs no per-user authorization, only "is this someone logged in" (see
// WebSocketConfig).
data class ChangeMessage(
    val type: String,
    val subject: String,
    val time: String,
)

// Fans a change out to every browser connected to *this* instance. Two producers feed
// it: ReleaseNotificationPublisher, for changes this instance itself just handled
// (works even with Kafka disabled), and KafkaChangeRelay, for changes any instance
// published to Kafka (the cross-instance path — see its class docs for why messages
// this instance produced arrive here a second time, harmlessly, once Kafka is enabled).
@Component
class WebSocketChangeBroadcaster(private val messagingTemplate: SimpMessagingTemplate) {

    private val log = LoggerFactory.getLogger(WebSocketChangeBroadcaster::class.java)

    fun broadcast(type: String, subject: String) {
        try {
            messagingTemplate.convertAndSend("/topic/changes", ChangeMessage(type, subject, Instant.now().toString()))
        } catch (e: Exception) {
            log.warn("Failed to broadcast change ({}, {}) to WebSocket clients: {}", type, subject, e.message)
        }
    }
}
