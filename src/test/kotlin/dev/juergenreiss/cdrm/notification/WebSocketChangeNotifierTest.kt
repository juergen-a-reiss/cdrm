// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.notification

import dev.juergenreiss.cdrm.release.ReleaseHistory
import dev.juergenreiss.cdrm.release.ReleaseHistoryAction
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Instant
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class WebSocketChangeNotifierTest {

    @Mock
    private lateinit var broadcaster: WebSocketChangeBroadcaster

    private fun historyEntry(): ReleaseHistory {
        val entry = ReleaseHistory(
            id = UUID.randomUUID(),
            releaseId = UUID.randomUUID(),
            productId = UUID.randomUUID(),
            productName = "Platform",
            workloadName = "api",
            image = "registry.example.com/app:1.0.0",
            stageId = UUID.randomUUID(),
            stageName = "Prod",
            action = ReleaseHistoryAction.PROMOTED,
            gitOpsManaged = false,
            kubernetesManaged = true,
            createdBy = UUID.randomUUID(),
        )
        entry.createdAt = Instant.now()
        return entry
    }

    @Test
    fun `broadcasts the same type releaseId to this instance's own clients, unconditionally`() {
        val entry = historyEntry()
        WebSocketChangeNotifier(broadcaster).onReleaseHistoryRecorded(ReleaseHistoryRecordedEvent(entry))

        verify(broadcaster).broadcast("dev.juergenreiss.cdrm.release-history.promoted", entry.releaseId.toString())
    }

    @Test
    fun `never throws when the broadcaster itself throws`() {
        doThrow(RuntimeException("boom")).`when`(broadcaster).broadcast(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())

        assertDoesNotThrow {
            WebSocketChangeNotifier(broadcaster).onReleaseHistoryRecorded(ReleaseHistoryRecordedEvent(historyEntry()))
        }
    }
}
