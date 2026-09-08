// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.notification

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

// Pushes a live-refresh signal to this instance's own connected browsers for every
// release-history occurrence — unconditionally, unlike ReleaseNotificationPublisher:
// a single instance (or one with Kafka notifications disabled entirely) must still get
// live push for the actions it itself handles. Cross-instance delivery (another
// instance's browsers learning about this occurrence) is KafkaChangeRelay's job, which
// does depend on Kafka being enabled — this bean is what makes that an enhancement
// rather than a hard requirement for the feature to work at all.
//
// Same resilience shape as ReleaseNotificationPublisher: after-commit, off the request
// thread, every exception caught and logged rather than left to propagate.
@Component
class WebSocketChangeNotifier(private val broadcaster: WebSocketChangeBroadcaster) {

    private val log = LoggerFactory.getLogger(WebSocketChangeNotifier::class.java)

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onReleaseHistoryRecorded(event: ReleaseHistoryRecordedEvent) {
        try {
            broadcaster.broadcast(event.cloudEventType(), event.entry.releaseId.toString())
        } catch (e: Exception) {
            log.warn("Failed to push local WebSocket notification for entry {}: {}", event.entry.id, e.message)
        }
    }
}
