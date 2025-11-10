package com.acdd.eventframework

/**
 * 用于描述事件在框架内部的生命周期变化。
 */
data class EventLifecycle(
    val event: EventEnvelope,
    val state: State,
    val timestampMillis: Long,
    val reason: String? = null,
    val error: Throwable? = null
) {
    enum class State {
        ENQUEUED,
        SCHEDULED,
        DISPATCHING,
        COMPLETED,
        FAILED,
        SKIPPED,
        RETRYING
    }
}
