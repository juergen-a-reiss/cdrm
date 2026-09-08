// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.notification

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

// Pushes a live-refresh signal to this instance's own connected browsers for every
// Cluster/Stage/Product/Workload/Config change — the EntityChangedEvent counterpart of
// WebSocketChangeNotifier (see its docs for the shared resilience shape: after-commit,
// off the request thread, every exception caught and logged rather than left to
// propagate). Kept as its own class, rather than a second method on
// WebSocketChangeNotifier, only to keep each listener's @TransactionalEventListener
// method bound to a single event type.
@Component
class EntityChangeNotifier(private val broadcaster: WebSocketChangeBroadcaster) {

    private val log = LoggerFactory.getLogger(EntityChangeNotifier::class.java)

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onEntityChanged(event: EntityChangedEvent) {
        try {
            broadcaster.broadcast(event.cloudEventType(), event.subject)
        } catch (e: Exception) {
            log.warn("Failed to push local WebSocket notification for {} {}: {}", event.entityType, event.subject, e.message)
        }
    }
}
