package com.acdd.eventframework

/**
 * 日志等级。
 */
enum class LogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR
}

/**
 * 事件框架统一日志接口。
 */
fun interface EventLogger {
    fun log(level: LogLevel, message: String, throwable: Throwable? = null, metadata: Map<String, Any?> = emptyMap())
}

/**
 * 简单的控制台输出实现。
 */
class ConsoleEventLogger(
    private val tag: String = "EventFramework",
    private val printer: (String) -> Unit = ::println
) : EventLogger {
    override fun log(level: LogLevel, message: String, throwable: Throwable?, metadata: Map<String, Any?>) {
        val metaString = if (metadata.isEmpty()) "" else metadata.entries.joinToString(
            prefix = " [",
            postfix = "]"
        ) { "${it.key}=${it.value}" }
        val throwableMessage = throwable?.let { "\n${it.stackTraceToString()}" } ?: ""
        printer("$tag ${level.name}: $message$metaString$throwableMessage")
    }

    companion object {
        val Default by lazy { ConsoleEventLogger() }
    }
}

fun EventLogger.debug(message: String, metadata: Map<String, Any?> = emptyMap()) =
    log(LogLevel.DEBUG, message, null, metadata)

fun EventLogger.info(message: String, metadata: Map<String, Any?> = emptyMap()) =
    log(LogLevel.INFO, message, null, metadata)

fun EventLogger.warn(message: String, metadata: Map<String, Any?> = emptyMap(), throwable: Throwable? = null) =
    log(LogLevel.WARN, message, throwable, metadata)

fun EventLogger.error(message: String, throwable: Throwable? = null, metadata: Map<String, Any?> = emptyMap()) =
    log(LogLevel.ERROR, message, throwable, metadata)
