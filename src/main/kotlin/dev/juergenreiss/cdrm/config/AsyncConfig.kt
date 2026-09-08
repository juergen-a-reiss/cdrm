// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

@Configuration
@EnableAsync
class AsyncConfig {

    // Small, dedicated pool for best-effort background work (ReleaseNotificationPublisher,
    // WebSocketChangeNotifier, EntityChangeNotifier, EntityChangeKafkaPublisher) —
    // deliberately bounded, unlike @Async's default executor (SimpleAsyncTaskExecutor,
    // which starts a new unbounded thread per task), so a stuck Kafka broker can only
    // ever tie up a handful of threads rather than one per action under load.
    @Bean("notificationExecutor")
    fun notificationExecutor(): Executor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 1
        executor.maxPoolSize = 4
        executor.setQueueCapacity(200)
        executor.setThreadNamePrefix("cdrm-notify-")
        executor.initialize()
        return executor
    }
}
