package com.acdd.eventframework.logging

import com.acdd.eventframework.core.AppEvent
import com.acdd.eventframework.core.EventResult
import com.acdd.eventframework.core.EventStatus

class CompositeEventLogger(
    private val delegates: List<EventLogger>
) : EventLogger {

    constructor(vararg delegates: EventLogger) : this(delegates.toList())

    override fun logEvent(event: AppEvent<*>, message: String, extras: Map<String, Any?>) {
        delegates.forEach { it.logEvent(event, message, extras) }
    }

    override fun logStatus(event: AppEvent<*>, status: EventStatus, message: String?) {
        delegates.forEach { it.logStatus(event, status, message) }
    }

    override fun logResult(result: EventResult) {
        delegates.forEach { it.logResult(result) }
    }

    override fun logError(event: AppEvent<*>, throwable: Throwable, message: String?) {
        delegates.forEach { it.logError(event, throwable, message) }
    }
}
