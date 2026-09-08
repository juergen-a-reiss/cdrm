// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.notification

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class KafkaChangeRelayTest {

    @Mock
    private lateinit var broadcaster: WebSocketChangeBroadcaster

    @Test
    fun `relays type and subject from a CloudEvents message, ignoring the data payload entirely`() {
        val payload = """
            {"specversion":"1.0","id":"abc","source":"urn:cdrm:release-service",
             "type":"dev.juergenreiss.cdrm.release-history.promoted","time":"2026-01-01T00:00:00Z",
             "subject":"79fefcfd-b8c9-4f2c-b478-b8ec60e022b8",
             "data":{"anything":"whatever shape a future entity's payload happens to be"}}
        """.trimIndent()

        KafkaChangeRelay(broadcaster).onMessage(payload)

        verify(broadcaster).broadcast("dev.juergenreiss.cdrm.release-history.promoted", "79fefcfd-b8c9-4f2c-b478-b8ec60e022b8")
    }

    @Test
    fun `never throws on malformed JSON`() {
        assertDoesNotThrow { KafkaChangeRelay(broadcaster).onMessage("not json at all") }
        verify(broadcaster, never()).broadcast(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    fun `ignores a message with no type or subject rather than forwarding a broken signal`() {
        assertDoesNotThrow { KafkaChangeRelay(broadcaster).onMessage("""{"specversion":"1.0"}""") }
        verify(broadcaster, never()).broadcast(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())
    }
}
