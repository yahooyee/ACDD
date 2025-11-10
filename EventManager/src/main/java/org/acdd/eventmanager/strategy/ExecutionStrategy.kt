package org.acdd.eventmanager.strategy

import org.acdd.eventmanager.core.Event

/**
 * 执行策略接口
 */
interface ExecutionStrategy {
    val name: String
    
    /**
     * 判断是否应该立即执行
     */
    fun shouldExecuteImmediately(event: Event): Boolean
    
    /**
     * 获取延迟时间（毫秒）
     */
    fun getDelay(event: Event): Long = 0
}

/**
 * 立即执行策略
 */
object ImmediateStrategy : ExecutionStrategy {
    override val name = "Immediate"
    override fun shouldExecuteImmediately(event: Event) = true
}

/**
 * 顺序执行策略
 */
object SequentialStrategy : ExecutionStrategy {
    override val name = "Sequential"
    override fun shouldExecuteImmediately(event: Event) = false
}

/**
 * 优先级策略 - 高优先级优先执行
 */
object PriorityStrategy : ExecutionStrategy {
    override val name = "Priority"
    override fun shouldExecuteImmediately(event: Event) = false
}

/**
 * 批量执行策略
 */
class BatchStrategy(
    private val batchSize: Int = 10,
    private val timeWindowMs: Long = 1000
) : ExecutionStrategy {
    override val name = "Batch"
    override fun shouldExecuteImmediately(event: Event) = false
    
    fun getBatchSize() = batchSize
    fun getTimeWindow() = timeWindowMs
}

/**
 * 延迟执行策略
 */
class DelayStrategy(
    private val delayMs: Long
) : ExecutionStrategy {
    override val name = "Delay"
    override fun shouldExecuteImmediately(event: Event) = false
    override fun getDelay(event: Event) = delayMs
}

/**
 * 防抖策略 - 在指定时间内只执行最后一个事件
 */
class DebounceStrategy(
    private val delayMs: Long = 300
) : ExecutionStrategy {
    override val name = "Debounce"
    override fun shouldExecuteImmediately(event: Event) = false
    override fun getDelay(event: Event) = delayMs
}

/**
 * 节流策略 - 在指定时间内只执行第一个事件
 */
class ThrottleStrategy(
    private val intervalMs: Long = 300
) : ExecutionStrategy {
    override val name = "Throttle"
    private var lastExecuteTime = 0L
    
    override fun shouldExecuteImmediately(event: Event): Boolean {
        val now = System.currentTimeMillis()
        return if (now - lastExecuteTime >= intervalMs) {
            lastExecuteTime = now
            true
        } else {
            false
        }
    }
}
