package org.acdd.eventmanager.core

/**
 * 事件处理器接口
 */
fun interface EventHandler<T : Event> {
    /**
     * 处理事件
     * @param event 要处理的事件
     * @return 处理结果
     */
    suspend fun handle(event: T): EventResult
}

/**
 * 事件处理结果
 */
sealed class EventResult {
    /** 处理成功 */
    data class Success(val data: Any? = null) : EventResult()
    
    /** 处理失败 */
    data class Failure(val error: Throwable, val message: String? = null) : EventResult()
    
    /** 跳过处理 */
    object Skipped : EventResult()
    
    /** 需要重试 */
    data class Retry(val delayMs: Long = 1000) : EventResult()
}

/**
 * 事件监听器 - 用于简单的事件监听场景
 */
fun interface EventListener<T : Event> {
    fun onEvent(event: T)
}
