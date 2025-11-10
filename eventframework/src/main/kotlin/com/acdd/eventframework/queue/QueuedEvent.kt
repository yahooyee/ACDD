package com.acdd.eventframework.queue

import com.acdd.eventframework.core.AppEvent
import com.acdd.eventframework.core.DispatchStrategy
import com.acdd.eventframework.core.EventMetadata
import kotlinx.datetime.Instant

internal data class QueuedEvent(
    val event: AppEvent<*>,
    val metadata: EventMetadata = event.metadata,
    val strategy: DispatchStrategy = metadata.strategy,
    val enqueuedAt: Instant,
    val attempt: Int = 0
)
