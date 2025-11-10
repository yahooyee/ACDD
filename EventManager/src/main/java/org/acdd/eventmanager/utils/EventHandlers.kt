package org.acdd.eventmanager.utils

import kotlinx.coroutines.delay
import org.acdd.eventmanager.core.Event
import org.acdd.eventmanager.core.EventHandler
import org.acdd.eventmanager.core.EventResult

/**
 * 常用事件处理器
 */

/**
 * 日志处理器 - 记录所有事件
 */
class LoggingEventHandler(
    private val logger: (String) -> Unit
) : EventHandler<Event> {
    override suspend fun handle(event: Event): EventResult {
        logger("Event: ${event.type.name} (${event.id}) at ${event.timestamp}")
        return EventResult.Success()
    }
}

/**
 * 过滤处理器 - 根据条件过滤事件
 */
class FilterEventHandler<T : Event>(
    private val predicate: (T) -> Boolean,
    private val delegate: EventHandler<T>
) : EventHandler<T> {
    override suspend fun handle(event: T): EventResult {
        return if (predicate(event)) {
            delegate.handle(event)
        } else {
            EventResult.Skipped
        }
    }
}

/**
 * 重试处理器 - 自动重试失败的事件
 */
class RetryEventHandler<T : Event>(
    private val maxRetries: Int = 3,
    private val delayMs: Long = 1000,
    private val delegate: EventHandler<T>
) : EventHandler<T> {
    override suspend fun handle(event: T): EventResult {
        var lastResult: EventResult = EventResult.Skipped
        
        repeat(maxRetries) { attempt ->
            lastResult = delegate.handle(event)
            
            when (lastResult) {
                is EventResult.Success -> return lastResult
                is EventResult.Failure -> {
                    if (attempt < maxRetries - 1) {
                        delay(delayMs * (attempt + 1))
                    }
                }
                else -> return lastResult
            }
        }
        
        return lastResult
    }
}

/**
 * 组合处理器 - 按顺序执行多个处理器
 */
class CompositeEventHandler<T : Event>(
    private val handlers: List<EventHandler<T>>
) : EventHandler<T> {
    override suspend fun handle(event: T): EventResult {
        var lastSuccess: EventResult.Success? = null
        
        for (handler in handlers) {
            when (val result = handler.handle(event)) {
                is EventResult.Success -> lastSuccess = result
                is EventResult.Failure -> return result
                is EventResult.Retry -> return result
                EventResult.Skipped -> continue
            }
        }
        
        return lastSuccess ?: EventResult.Skipped
    }
}

/**
 * 超时处理器 - 限制处理时间
 */
class TimeoutEventHandler<T : Event>(
    private val timeoutMs: Long,
    private val delegate: EventHandler<T>
) : EventHandler<T> {
    override suspend fun handle(event: T): EventResult {
        return try {
            kotlinx.coroutines.withTimeout(timeoutMs) {
                delegate.handle(event)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            EventResult.Failure(e, "Handler timeout after ${timeoutMs}ms")
        }
    }
}

/**
 * DSL构建器扩展
 */

/**
 * 创建过滤处理器
 */
fun <T : Event> EventHandler<T>.filter(predicate: (T) -> Boolean): EventHandler<T> {
    return FilterEventHandler(predicate, this)
}

/**
 * 添加重试逻辑
 */
fun <T : Event> EventHandler<T>.retry(maxRetries: Int = 3, delayMs: Long = 1000): EventHandler<T> {
    return RetryEventHandler(maxRetries, delayMs, this)
}

/**
 * 添加超时限制
 */
fun <T : Event> EventHandler<T>.timeout(timeoutMs: Long): EventHandler<T> {
    return TimeoutEventHandler(timeoutMs, this)
}

/**
 * 组合多个处理器
 */
fun <T : Event> List<EventHandler<T>>.combine(): EventHandler<T> {
    return CompositeEventHandler(this)
}
