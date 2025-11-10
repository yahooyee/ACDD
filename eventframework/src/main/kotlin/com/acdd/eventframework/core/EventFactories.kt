package com.acdd.eventframework.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

/**
 * 简化事件构建的 DSL。
 */
object EventDSL {

    fun ui(
        context: EventContext,
        interaction: UiInteraction,
        payload: Any = Unit,
        priority: EventPriority = EventPriority.NORMAL,
        strategy: DispatchStrategy = DispatchStrategy.SEQUENTIAL,
        dedup: DedupConfig = DedupConfig(),
        delay: Duration = ZERO,
        batchKey: String? = null,
        tags: Set<String> = emptySet()
    ): AppEvent.Ui<Any> = AppEvent.Ui(
        metadata = EventMetadata(
            category = EventCategory.UI,
            priority = priority,
            strategy = strategy,
            dedup = dedup,
            delayBy = delay,
            batchKey = batchKey,
            tags = tags
        ),
        payload = payload,
        interaction = interaction,
        context = context
    )

    fun navigation(
        context: EventContext,
        destination: String,
        payload: Any = Unit,
        priority: EventPriority = EventPriority.NORMAL,
        strategy: DispatchStrategy = DispatchStrategy.SEQUENTIAL,
        dedup: DedupConfig = DedupConfig(),
        delay: Duration = ZERO,
        batchKey: String? = null,
        tags: Set<String> = emptySet()
    ): AppEvent.Navigation<Any> = AppEvent.Navigation(
        metadata = EventMetadata(
            category = EventCategory.NAVIGATION,
            priority = priority,
            strategy = strategy,
            dedup = dedup,
            delayBy = delay,
            batchKey = batchKey,
            tags = tags
        ),
        payload = payload,
        destination = destination,
        context = context
    )

    fun tracking(
        context: EventContext,
        name: String,
        properties: Map<String, Any?> = emptyMap(),
        priority: EventPriority = EventPriority.NORMAL,
        strategy: DispatchStrategy = DispatchStrategy.SEQUENTIAL,
        retry: RetryConfig = RetryConfig(),
        dedup: DedupConfig = DedupConfig(),
        tags: Set<String> = emptySet()
    ): AppEvent.Tracking = AppEvent.Tracking(
        metadata = EventMetadata(
            category = EventCategory.TRACKING,
            priority = priority,
            strategy = strategy,
            dedup = dedup,
            retry = retry,
            tags = tags
        ),
        payload = TrackingPayload(name, properties),
        context = context
    )

    fun <T : Any> custom(
        context: EventContext? = null,
        payload: T,
        metadata: EventMetadata
    ): AppEvent.Custom<T> = AppEvent.Custom(
        metadata = metadata,
        payload = payload,
        context = context
    )
}
