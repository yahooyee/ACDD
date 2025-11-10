package com.acdd.eventframework

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 框架运行配置。
 */
data class EventConfig(
    val defaultPolicy: EventPolicy = EventPolicy(),
    val logger: EventLogger = ConsoleEventLogger.Default,
    val bufferSize: Int = 128,
    val deduplicationCleanupInterval: Duration = 5.seconds,
    val timeProvider: () -> Long = { System.currentTimeMillis() },
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    companion object {
        fun create(
            defaultPolicy: EventPolicy = EventPolicy(),
            logger: EventLogger = ConsoleEventLogger.Default,
            bufferSize: Int = 128,
            deduplicationCleanupInterval: Duration = 5.seconds,
            timeProvider: () -> Long = { System.currentTimeMillis() },
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ) = EventConfig(
            defaultPolicy = defaultPolicy,
            logger = logger,
            bufferSize = bufferSize,
            deduplicationCleanupInterval = deduplicationCleanupInterval,
            timeProvider = timeProvider,
            scope = scope
        )
    }
}
