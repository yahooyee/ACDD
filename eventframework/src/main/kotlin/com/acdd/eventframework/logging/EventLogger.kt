package com.acdd.eventframework.logging

import com.acdd.eventframework.core.AppEvent
import com.acdd.eventframework.core.EventResult
import com.acdd.eventframework.core.EventStatus
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * 简单的日志接口，允许替换为业务日志实现。
 */
interface EventLogger {
    fun logEvent(event: AppEvent<*>, message: String, extras: Map<String, Any?> = emptyMap())
    fun logStatus(event: AppEvent<*>, status: EventStatus, message: String? = null)
    fun logResult(result: EventResult)
    fun logError(event: AppEvent<*>, throwable: Throwable, message: String? = throwable.message)

    object None : EventLogger {
        override fun logEvent(event: AppEvent<*>, message: String, extras: Map<String, Any?>) = Unit
        override fun logStatus(event: AppEvent<*>, status: EventStatus, message: String?) = Unit
        override fun logResult(result: EventResult) = Unit
        override fun logError(event: AppEvent<*>, throwable: Throwable, message: String?) = Unit
    }
}

/**
 * 默认实现：基于标准输出，携带时间戳与关键字段。
 */
class SimpleEventLogger(
    private val tag: String = "EventFramework"
) : EventLogger {

    override fun logEvent(event: AppEvent<*>, message: String, extras: Map<String, Any?>) {
        println(formatLine("EVENT", event, message, extras))
    }

    override fun logStatus(event: AppEvent<*>, status: EventStatus, message: String?) {
        println(formatLine("STATUS", event, "$status ${message.orEmpty()}"))
    }

    override fun logResult(result: EventResult) {
        val body = when (result) {
            is EventResult.Success -> "Success attempts=${result.attempts} message=${result.message.orEmpty()}"
            is EventResult.Failure -> "Failure attempts=${result.attempts} error=${result.error}"
            is EventResult.Timeout -> "Timeout attempts=${result.attempts}"
            is EventResult.Cancelled -> "Cancelled attempts=${result.attempts}"
        }
        println("[${timestamp()}][$tag][RESULT][${result.key.value}] $body")
    }

    override fun logError(event: AppEvent<*>, throwable: Throwable, message: String?) {
        println(formatLine("ERROR", event, "${throwable::class.simpleName}: ${message.orEmpty()}"))
        throwable.printStackTrace()
    }

    private fun formatLine(
        type: String,
        event: AppEvent<*>,
        message: String,
        extras: Map<String, Any?> = emptyMap()
    ): String {
        val extraSegment = if (extras.isEmpty()) "" else extras.entries.joinToString(
            prefix = " extras={",
            postfix = "}"
        ) { "${it.key}=${it.value}" }
        return "[${timestamp()}][$tag][$type][${event.metadata.category}][${event.key.value}] $message$extraSegment"
    }

    private fun timestamp(): Instant = Clock.System.now()
}
