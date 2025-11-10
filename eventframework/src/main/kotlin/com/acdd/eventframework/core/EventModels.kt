package com.acdd.eventframework.core

import kotlinx.coroutines.Job
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds
import java.util.UUID

/**
 * 唯一事件标识，用于去重、追踪。
 */
@JvmInline
value class EventKey(val value: String) {
    companion object {
        fun random(): EventKey = EventKey(UUID.randomUUID().toString())
    }
}

/**
 * 预置的事件类别，可扩展。
 */
enum class EventCategory {
    UI,
    NAVIGATION,
    TRACKING,
    SYSTEM,
    CUSTOM
}

/**
 * 事件优先级，驱动队列选择与调度。
 */
enum class EventPriority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL
}

/**
 * 事件状态跟踪。
 */
enum class EventStatus {
    PENDING,
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    SKIPPED,
    TIMEOUT
}

/**
 * 事件执行策略选项。
 */
enum class DispatchStrategy {
    SEQUENTIAL,
    PRIORITY,
    IMMEDIATE,
    BATCH,
    DELAYED
}

/**
 * 扩展标签，用来标注常见 UI 操作便于统计。
 */
enum class UiInteraction {
    Click,
    LongPress,
    Swipe,
    Scroll,
    TextInput
}

/**
 * 事件上下文，携带业务模块、页面等信息。
 */
data class EventContext(
    val module: String,
    val screen: String? = null,
    val userId: String? = null,
    val sessionId: String? = null,
    val attributes: Map<String, Any?> = emptyMap()
)

/**
 * 依赖声明：当前事件依赖某些事件成功或状态。
 */
data class EventDependency(
    val key: EventKey,
    val mustSucceed: Boolean = true,
    val timeout: Duration = 5.seconds
)

/**
 * 去重与防抖配置。
 */
data class DedupConfig(
    val dedupWithin: Duration = 2.seconds,
    val debounce: Duration = ZERO,
    val dedupScope: DedupScope = DedupScope.GLOBAL
)

enum class DedupScope {
    GLOBAL,
    MODULE,
    CONTEXT
}

/**
 * 超时与重试配置。
 */
data class RetryConfig(
    val timeout: Duration = 5.seconds,
    val maxRetries: Int = 0,
    val backoffStrategy: BackoffStrategy = BackoffStrategy.None
)

sealed interface BackoffStrategy {
    data object None : BackoffStrategy
    data class Linear(val step: Duration) : BackoffStrategy
    data class Exponential(val base: Duration, val multiplier: Double = 2.0) : BackoffStrategy
}

/**
 * 事件元信息。
 */
data class EventMetadata(
    val key: EventKey = EventKey.random(),
    val category: EventCategory = EventCategory.CUSTOM,
    val priority: EventPriority = EventPriority.NORMAL,
    val strategy: DispatchStrategy = DispatchStrategy.SEQUENTIAL,
    val dedup: DedupConfig = DedupConfig(),
    val retry: RetryConfig = RetryConfig(),
    val dependencies: List<EventDependency> = emptyList(),
    val delayBy: Duration = ZERO,
    val batchKey: String? = null,
    val createdAt: Instant = Clock.System.now(),
    val expiresAt: Instant? = null,
    val tags: Set<String> = emptySet()
)

/**
 * 通用业务事件定义。
 */
sealed class AppEvent<TPayload : Any>(
    open val metadata: EventMetadata,
    open val payload: TPayload,
    open val context: EventContext? = null
) {
    open val key: EventKey get() = metadata.key

    data class Ui<TPayload : Any>(
        override val metadata: EventMetadata,
        override val payload: TPayload,
        val interaction: UiInteraction? = null,
        override val context: EventContext? = null
    ) : AppEvent<TPayload>(metadata, payload, context)

    data class Navigation<TPayload : Any>(
        override val metadata: EventMetadata,
        override val payload: TPayload,
        val destination: String,
        override val context: EventContext? = null
    ) : AppEvent<TPayload>(metadata, payload, context)

    data class Tracking(
        override val metadata: EventMetadata,
        override val payload: TrackingPayload,
        override val context: EventContext? = null
    ) : AppEvent<TrackingPayload>(metadata, payload, context)

    data class Custom<TPayload : Any>(
        override val metadata: EventMetadata,
        override val payload: TPayload,
        override val context: EventContext? = null
    ) : AppEvent<TPayload>(metadata, payload, context)
}

/**
 * 通用埋点结构。
 */
data class TrackingPayload(
    val name: String,
    val properties: Map<String, Any?> = emptyMap()
)

/**
 * 事件执行结果。
 */
sealed interface EventResult {
    val status: EventStatus
    val key: EventKey
    val startedAt: Instant
    val finishedAt: Instant
    val attempts: Int
    val message: String?

    data class Success(
        override val key: EventKey,
        override val startedAt: Instant,
        override val finishedAt: Instant,
        override val attempts: Int = 1,
        val data: Any? = null,
        override val message: String? = null
    ) : EventResult {
        override val status: EventStatus = EventStatus.SUCCEEDED
    }

    data class Failure(
        override val key: EventKey,
        override val startedAt: Instant,
        override val finishedAt: Instant,
        override val attempts: Int = 1,
        val error: Throwable,
        override val message: String? = error.message
    ) : EventResult {
        override val status: EventStatus = EventStatus.FAILED
    }

    data class Timeout(
        override val key: EventKey,
        override val startedAt: Instant,
        override val finishedAt: Instant,
        override val attempts: Int,
        override val message: String? = null
    ) : EventResult {
        override val status: EventStatus = EventStatus.TIMEOUT
    }

    data class Cancelled(
        override val key: EventKey,
        override val startedAt: Instant,
        override val finishedAt: Instant,
        override val attempts: Int,
        override val message: String? = null
    ) : EventResult {
        override val status: EventStatus = EventStatus.CANCELLED
    }
}

/**
 * 事件处理器签名。
 */
suspend fun interface EventEmitter {
    suspend fun emit(event: AppEvent<*>)
}

fun interface EventHandler<T : Any> {
    suspend fun handle(event: AppEvent<T>, scope: EventScope): EventResult
}

/**
 * 事件管道上下文。
 */
data class EventScope internal constructor(
    val emitter: EventEmitter,
    val job: Job,
    val context: EventContext?,
    val metadata: EventMetadata
) {
    fun ensureActive() = job.ensureActive()
}

/**
 * 事件绑定描述，给外部注册处理器。
 */
data class EventBinding<T : Any>(
    val eventType: KClass<out AppEvent<T>>,
    val handler: EventHandler<T>,
    val metadataPredicate: (EventMetadata) -> Boolean = { true },
    val contextPredicate: (EventContext?) -> Boolean = { true }
)
