package com.acdd.eventframework

import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 事件所属的大类，用于统一管理各种类型的业务事件。
 */
enum class EventDomain {
    UI,
    NAVIGATION,
    ANALYTICS,
    SYSTEM,
    BACKGROUND,
    CUSTOM
}

/**
 * UI 交互事件的标准化分类。
 */
enum class UiInteractionKind {
    Click,
    LongPress,
    Scroll,
    Swipe,
    Submit,
    Drag,
    TextInput,
    Focus,
    Custom
}

/**
 * 事件分发时的优先级。
 */
enum class EventPriority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL
}

/**
 * 事件类型定义，预置常见业务域可直接扩展。
 */
sealed class EventType(open val name: String) {
    data class Dialog(override val name: String = "dialog") : EventType(name)
    data class Navigation(override val name: String = "navigation") : EventType(name)
    data class Analytics(override val name: String = "analytics") : EventType(name)
    data class UiInteraction(val interaction: UiInteractionKind, override val name: String = interaction.name) :
        EventType(name)

    data class Background(override val name: String = "background") : EventType(name)
    data class Custom(override val name: String) : EventType(name)
}

/**
 * 事件描述信息，包含来源、标签、扩展参数等。
 */
data class EventDescriptor(
    val id: String,
    val name: String,
    val domain: EventDomain,
    val type: EventType = EventType.Custom(name),
    val source: String? = null,
    val tags: Set<String> = emptySet(),
    val attributes: Map<String, Any?> = emptyMap(),
    val createdAtMillis: Long = System.currentTimeMillis()
)

/**
 * 去重配置。
 */
data class Deduplication(
    val key: String,
    val expiresIn: Duration = 0.5.seconds,
    val mode: Mode = Mode.SKIP
) {
    enum class Mode {
        SKIP, // 直接丢弃重复事件
        REPLACE // 保留最新的一条
    }
}

/**
 * 重试策略。
 */
sealed class RetryPolicy {
    data object None : RetryPolicy()

    data class Fixed(val maxAttempts: Int = 3, val delay: Duration = 2.seconds) : RetryPolicy()

    data class Exponential(
        val maxAttempts: Int = 3,
        val initialDelay: Duration = 1.seconds,
        val factor: Double = 2.0
    ) : RetryPolicy()

    fun shouldRetry(attempt: Int): Boolean = when (this) {
        None -> false
        is Fixed -> attempt < maxAttempts
        is Exponential -> attempt < maxAttempts
    }

    fun nextDelay(attempt: Int): Duration = when (this) {
        None -> ZERO
        is Fixed -> delay
        is Exponential -> initialDelay * factor.pow(attempt.toDouble())
    }

    private operator fun Duration.times(multiplier: Double): Duration =
        (inWholeMilliseconds * multiplier).toLong().milliseconds
}

/**
 * 事件分发策略。
 */
sealed class DispatchStrategy {
    data object Sequential : DispatchStrategy()
    data object Immediate : DispatchStrategy()
    data class Priority(val channel: String = "default") : DispatchStrategy()
    data class Batch(
        val key: String,
        val maxItems: Int = 5,
        val maxWait: Duration = 500.milliseconds
    ) : DispatchStrategy()

    data class Delayed(val delay: Duration) : DispatchStrategy()
}

/**
 * 事件执行策略配置。
 */
data class EventPolicy(
    val strategy: DispatchStrategy = DispatchStrategy.Sequential,
    val priority: EventPriority = EventPriority.NORMAL,
    val deduplication: Deduplication? = null,
    val debounceKey: String? = null,
    val debounceWindow: Duration = ZERO,
    val dependencies: Set<String> = emptySet(),
    val timeout: Duration = 30.seconds,
    val retryPolicy: RetryPolicy = RetryPolicy.None,
    val metadata: Map<String, Any?> = emptyMap()
) {
    fun mergeWith(override: EventPolicy?): EventPolicy {
        if (override == null) return this
        return copy(
            strategy = override.strategy,
            priority = override.priority,
            deduplication = override.deduplication ?: deduplication,
            debounceKey = override.debounceKey ?: debounceKey,
            debounceWindow = if (override.debounceWindow != ZERO) override.debounceWindow else debounceWindow,
            dependencies = if (override.dependencies.isNotEmpty()) override.dependencies else dependencies,
            timeout = override.timeout,
            retryPolicy = override.retryPolicy,
            metadata = metadata + override.metadata
        )
    }
}

/**
 * 事件封装体，结合描述、载荷与策略。
 */
data class EventEnvelope(
    val descriptor: EventDescriptor,
    val payload: Any,
    val policy: EventPolicy = EventPolicy(),
    val attempt: Int = 0
) {
    val id: String get() = descriptor.id
}

/**
 * 事件分发结果。
 */
sealed class EventDispatchResult {
    data object Consumed : EventDispatchResult()
    data object Ignored : EventDispatchResult()
    data class Retry(val reason: Throwable? = null) : EventDispatchResult()
    data class Failed(val error: Throwable? = null, val recoverable: Boolean = false) : EventDispatchResult()
}

typealias EventFilter = (EventEnvelope) -> Boolean
