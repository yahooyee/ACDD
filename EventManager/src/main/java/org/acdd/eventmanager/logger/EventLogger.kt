package org.acdd.eventmanager.logger

import android.util.Log

/**
 * 日志级别
 */
enum class LogLevel(val value: Int) {
    VERBOSE(0),
    DEBUG(1),
    INFO(2),
    WARN(3),
    ERROR(4),
    NONE(5)
}

/**
 * 事件日志接口
 */
interface EventLogger {
    fun verbose(message: String, tag: String = "EventManager")
    fun debug(message: String, tag: String = "EventManager")
    fun info(message: String, tag: String = "EventManager")
    fun warn(message: String, tag: String = "EventManager")
    fun error(message: String, tag: String = "EventManager", throwable: Throwable? = null)
    
    fun setLogLevel(level: LogLevel)
    fun getLogLevel(): LogLevel
}

/**
 * 默认日志实现 - 使用Android Log
 */
class DefaultEventLogger(
    private var logLevel: LogLevel = LogLevel.DEBUG
) : EventLogger {
    
    override fun verbose(message: String, tag: String) {
        if (logLevel.value <= LogLevel.VERBOSE.value) {
            Log.v(tag, message)
        }
    }
    
    override fun debug(message: String, tag: String) {
        if (logLevel.value <= LogLevel.DEBUG.value) {
            Log.d(tag, message)
        }
    }
    
    override fun info(message: String, tag: String) {
        if (logLevel.value <= LogLevel.INFO.value) {
            Log.i(tag, message)
        }
    }
    
    override fun warn(message: String, tag: String) {
        if (logLevel.value <= LogLevel.WARN.value) {
            Log.w(tag, message)
        }
    }
    
    override fun error(message: String, tag: String, throwable: Throwable?) {
        if (logLevel.value <= LogLevel.ERROR.value) {
            if (throwable != null) {
                Log.e(tag, message, throwable)
            } else {
                Log.e(tag, message)
            }
        }
    }
    
    override fun setLogLevel(level: LogLevel) {
        logLevel = level
    }
    
    override fun getLogLevel(): LogLevel = logLevel
}

/**
 * 组合日志器 - 支持多个日志输出
 */
class CompositeEventLogger(
    private val loggers: List<EventLogger>
) : EventLogger {
    
    override fun verbose(message: String, tag: String) {
        loggers.forEach { it.verbose(message, tag) }
    }
    
    override fun debug(message: String, tag: String) {
        loggers.forEach { it.debug(message, tag) }
    }
    
    override fun info(message: String, tag: String) {
        loggers.forEach { it.info(message, tag) }
    }
    
    override fun warn(message: String, tag: String) {
        loggers.forEach { it.warn(message, tag) }
    }
    
    override fun error(message: String, tag: String, throwable: Throwable?) {
        loggers.forEach { it.error(message, tag, throwable) }
    }
    
    override fun setLogLevel(level: LogLevel) {
        loggers.forEach { it.setLogLevel(level) }
    }
    
    override fun getLogLevel(): LogLevel {
        return loggers.firstOrNull()?.getLogLevel() ?: LogLevel.DEBUG
    }
}

/**
 * 空日志器 - 不输出任何日志
 */
object NoOpEventLogger : EventLogger {
    override fun verbose(message: String, tag: String) {}
    override fun debug(message: String, tag: String) {}
    override fun info(message: String, tag: String) {}
    override fun warn(message: String, tag: String) {}
    override fun error(message: String, tag: String, throwable: Throwable?) {}
    override fun setLogLevel(level: LogLevel) {}
    override fun getLogLevel(): LogLevel = LogLevel.NONE
}
