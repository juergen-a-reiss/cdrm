// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.notification

import dev.juergenreiss.cdrm.release.ReleaseHistory
import dev.juergenreiss.cdrm.release.ReleaseHistoryAction
import org.apache.kafka.clients.producer.ProducerRecord
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

// Plain ArgumentMatchers.any(Class) returns a captured/raw type Kotlin's overload
// resolution rejects against KafkaTemplate's overloaded send() (ProducerRecord vs.
// Message); this is the standard non-reified shim for that (in the absence of the
// mockito-kotlin artifact, not a dependency here). Deliberately NOT `reified`/`inline`:
// reifying T would bake a concrete ProducerRecord<String, String> checkcast into
// `null as T` and throw immediately, the same as casting that type directly — leaving T
// an ordinary erased type parameter means the cast compiles away to nothing at
// runtime, while the explicit type argument at each call site (anyRecord<...>()) still
// gives Kotlin everything it needs to resolve send()'s overload at compile time.
@Suppress("UNCHECKED_CAST")
private fun <T> anyRecord(): T {
    any<Any>()
    return null as T
}

// Kafka delivery itself (real broker, real serialization on the wire) is deliberately
// not exercised here — see the class docs on ReleaseNotificationPublisher for why this
// stays a plain Mockito unit test (no embedded/real broker) rather than an integration
// test: the whole point of the design is that KafkaTemplate's behavior (up to and
// including it being completely unreachable) can never surface past this class.
@ExtendWith(MockitoExtension::class)
class ReleaseNotificationPublisherTest {

    @Mock
    private lateinit var kafkaTemplate: KafkaTemplate<String, String>

    private fun publisher() = ReleaseNotificationPublisher(kafkaTemplate, "cdrm-release", "urn:cdrm:release-service")

    private fun historyEntry(action: ReleaseHistoryAction = ReleaseHistoryAction.PROMOTED): ReleaseHistory {
        val entry = ReleaseHistory(
            id = UUID.randomUUID(),
            releaseId = UUID.randomUUID(),
            workloadId = UUID.randomUUID(),
            productId = UUID.randomUUID(),
            productName = "Platform",
            workloadName = "api",
            image = "registry.example.com/app:1.0.0",
            stageId = UUID.randomUUID(),
            stageName = "Prod",
            pipeline = "default",
            action = action,
            gitOpsManaged = false,
            kubernetesManaged = true,
            createdBy = UUID.randomUUID(),
        )
        entry.createdAt = Instant.now()
        return entry
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `publishes a CloudEvents-shaped record keyed by releaseId to the configured topic`() {
        doReturn(CompletableFuture.completedFuture(null)).`when`(kafkaTemplate).send(anyRecord<ProducerRecord<String, String>>())

        val entry = historyEntry()
        publisher().onReleaseHistoryRecorded(ReleaseHistoryRecordedEvent(entry))

        val captor = ArgumentCaptor.forClass(ProducerRecord::class.java) as ArgumentCaptor<ProducerRecord<String, String>>
        verify(kafkaTemplate).send(captor.capture())
        val record = captor.value

        assertEquals("cdrm-release", record.topic())
        assertEquals(entry.releaseId.toString(), record.key())
        assertEquals(
            "application/cloudevents+json; charset=UTF-8",
            String(record.headers().lastHeader("content-type")!!.value()),
        )
        assertTrue(record.value().contains("\"specversion\":\"1.0\""))
        assertTrue(record.value().contains("\"source\":\"urn:cdrm:release-service\""))
        assertTrue(record.value().contains("\"type\":\"dev.juergenreiss.cdrm.release-history.promoted\""))
        assertTrue(record.value().contains("\"subject\":\"${entry.releaseId}\""))
        assertTrue(record.value().contains(entry.workloadName))
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `uses the deployed-outcome type (not the recording action) for a DEPLOYED notification`() {
        doReturn(CompletableFuture.completedFuture(null)).`when`(kafkaTemplate).send(anyRecord<ProducerRecord<String, String>>())

        val entry = historyEntry(action = ReleaseHistoryAction.PROMOTED)
        publisher().onReleaseHistoryRecorded(ReleaseHistoryRecordedEvent(entry, ReleaseHistoryNotificationKind.DEPLOYED))

        val captor = ArgumentCaptor.forClass(ProducerRecord::class.java) as ArgumentCaptor<ProducerRecord<String, String>>
        verify(kafkaTemplate).send(captor.capture())
        assertTrue(captor.value.value().contains("\"type\":\"dev.juergenreiss.cdrm.release-history.deployed\""))
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `uses the deploy-failed type for a DEPLOY_FAILED notification`() {
        doReturn(CompletableFuture.completedFuture(null)).`when`(kafkaTemplate).send(anyRecord<ProducerRecord<String, String>>())

        val entry = historyEntry(action = ReleaseHistoryAction.CREATED)
        publisher().onReleaseHistoryRecorded(ReleaseHistoryRecordedEvent(entry, ReleaseHistoryNotificationKind.DEPLOY_FAILED))

        val captor = ArgumentCaptor.forClass(ProducerRecord::class.java) as ArgumentCaptor<ProducerRecord<String, String>>
        verify(kafkaTemplate).send(captor.capture())
        assertTrue(captor.value.value().contains("\"type\":\"dev.juergenreiss.cdrm.release-history.deploy-failed\""))
    }

    @Test
    fun `never throws when Kafka send itself throws synchronously, for example a broker being unreachable`() {
        doThrow(RuntimeException("boom")).`when`(kafkaTemplate).send(anyRecord<ProducerRecord<String, String>>())

        assertDoesNotThrow {
            publisher().onReleaseHistoryRecorded(ReleaseHistoryRecordedEvent(historyEntry()))
        }
    }

    @Test
    fun `never throws when the returned future completes exceptionally`() {
        val failed = CompletableFuture<SendResult<String, String>>()
        failed.completeExceptionally(RuntimeException("connection refused"))
        doReturn(failed).`when`(kafkaTemplate).send(anyRecord<ProducerRecord<String, String>>())

        assertDoesNotThrow {
            publisher().onReleaseHistoryRecorded(ReleaseHistoryRecordedEvent(historyEntry()))
        }
    }
}
