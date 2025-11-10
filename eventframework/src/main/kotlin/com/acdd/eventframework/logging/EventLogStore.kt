package com.acdd.eventframework.logging

import com.acdd.eventframework.core.AppEvent
import com.acdd.eventframework.core.EventResult
import com.acdd.eventframework.core.EventStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

data class EventLogEntry(
    val timestamp: Instant,
    val type: LogType,
    val eventKey: String,
    val category: String,
    val message: String,
    val payloadPreview: String? = null,
    val status: EventStatus? = null
) {
    enum class LogType { EVENT, STATUS, RESULT, ERROR }
}

/**
 * 线程安全的内存日志仓库，可供调试面板展示。
 */
class InMemoryEventLogStore(
    maxSize: Int = 200
) : EventLogger {

    private val maxEntries = maxSize.coerceAtLeast(10)
    private val _logs = MutableStateFlow<List<EventLogEntry>>(emptyList())
    val logs: StateFlow<List<EventLogEntry>> = _logs.asStateFlow()

    override fun logEvent(event: AppEvent<*>, message: String, extras: Map<String, Any?>) {
        append(
            EventLogEntry(
                timestamp = Clock.System.now(),
                type = EventLogEntry.LogType.EVENT,
                eventKey = event.key.value,
                category = event.metadata.category.name,
                message = message,
                payloadPreview = extras["payload"]?.toString()
            )
        )
    }

    override fun logStatus(event: AppEvent<*>, status: EventStatus, message: String?) {
        append(
            EventLogEntry(
                timestamp = Clock.System.now(),
                type = EventLogEntry.LogType.STATUS,
                eventKey = event.key.value,
                category = event.metadata.category.name,
                message = message ?: "",
                status = status
            )
        )
    }

    override fun logResult(result: EventResult) {
        append(
            EventLogEntry(
                timestamp = Clock.System.now(),
                type = EventLogEntry.LogType.RESULT,
                eventKey = result.key.value,
                category = "RESULT",
                message = result.message.orEmpty(),
                status = result.status
            )
        )
    }

    override fun logError(event: AppEvent<*>, throwable: Throwable, message: String?) {
        append(
            EventLogEntry(
                timestamp = Clock.System.now(),
                type = EventLogEntry.LogType.ERROR,
                eventKey = event.key.value,
                category = event.metadata.category.name,
                message = message ?: throwable.message.orEmpty()
            )
        )
    }

    fun clear() {
        _logs.value = emptyList()
    }

    private fun append(entry: EventLogEntry) {
        _logs.value = (_logs.value + entry).takeLast(maxEntries)
    }
}
