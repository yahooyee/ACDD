package org.acdd.eventmanager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.acdd.eventmanager.core.*
import org.acdd.eventmanager.logger.DefaultEventLogger
import org.acdd.eventmanager.logger.EventLogger
import org.acdd.eventmanager.logger.LogLevel
import org.acdd.eventmanager.queue.EventQueueManager
import org.acdd.eventmanager.queue.QueueConfig
import org.acdd.eventmanager.queue.QueueState
import org.acdd.eventmanager.strategy.ExecutionStrategy
import org.acdd.eventmanager.strategy.ImmediateStrategy

/**
 * EventManager配置
 */
data class EventManagerConfig(
    val queueConfig: QueueConfig = QueueConfig(),
    val logger: EventLogger = DefaultEventLogger(LogLevel.DEBUG),
    val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
)

/**
 * 事件管理器 - 主入口类
 * 
 * 使用示例：
 * ```
 * // 初始化
 * val eventManager = EventManager.getInstance()
 * 
 * // 发送事件
 * eventManager.dispatch(UIClickEvent("button_1"))
 * 
 * // 注册处理器
 * eventManager.registerHandler("ui_click") { event ->
 *     // 处理事件
 *     EventResult.Success()
 * }
 * ```
 */
class EventManager private constructor(
    private val config: EventManagerConfig
) {
    private val queueManager = EventQueueManager(
        config = config.queueConfig,
        logger = config.logger,
        scope = config.scope
    )
    
    /**
     * 事件流 - 可用于观察所有事件
     */
    val eventFlow: SharedFlow<Event>
        get() = queueManager.eventFlow
    
    /**
     * 队列状态流
     */
    val queueState: StateFlow<QueueState>
        get() = queueManager.queueStateFlow
    
    /**
     * 发送事件
     * @param event 要发送的事件
     * @param strategy 执行策略，默认立即执行
     * @param dependsOn 依赖的事件ID列表
     */
    fun dispatch(
        event: Event,
        strategy: ExecutionStrategy? = null,
        dependsOn: List<String> = emptyList()
    ) {
        queueManager.submitEvent(
            event = event,
            strategy = strategy ?: ImmediateStrategy,
            dependsOn = dependsOn
        )
    }
    
    /**
     * 注册事件处理器
     * @param eventTypeName 事件类型名称
     * @param handler 事件处理器
     */
    fun <T : Event> registerHandler(
        eventTypeName: String,
        handler: EventHandler<T>
    ) {
        queueManager.registerHandler(eventTypeName, handler)
    }
    
    /**
     * 注册简单的事件监听器
     * @param eventTypeName 事件类型名称
     * @param listener 事件监听器
     */
    fun <T : Event> registerListener(
        eventTypeName: String,
        listener: EventListener<T>
    ) {
        val handler = EventHandler<T> { event ->
            listener.onEvent(event)
            EventResult.Success()
        }
        queueManager.registerHandler(eventTypeName, handler)
    }
    
    /**
     * 取消注册事件处理器
     * @param eventTypeName 事件类型名称
     * @param handler 要取消注册的处理器
     */
    fun unregisterHandler(eventTypeName: String, handler: EventHandler<*>) {
        queueManager.unregisterHandler(eventTypeName, handler)
    }
    
    /**
     * 清空队列
     */
    fun clearQueue() {
        queueManager.clear()
    }
    
    /**
     * 设置日志级别
     */
    fun setLogLevel(level: LogLevel) {
        config.logger.setLogLevel(level)
    }
    
    /**
     * 关闭事件管理器
     */
    fun shutdown() {
        queueManager.shutdown()
        config.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
    
    companion object {
        @Volatile
        private var instance: EventManager? = null
        
        /**
         * 获取单例实例
         */
        fun getInstance(config: EventManagerConfig = EventManagerConfig()): EventManager {
            return instance ?: synchronized(this) {
                instance ?: EventManager(config).also { instance = it }
            }
        }
        
        /**
         * 创建新实例
         */
        fun create(config: EventManagerConfig = EventManagerConfig()): EventManager {
            return EventManager(config)
        }
        
        /**
         * 重置单例（主要用于测试）
         */
        fun reset() {
            instance?.shutdown()
            instance = null
        }
    }
}

/**
 * DSL构建器
 */
class EventManagerBuilder {
    private var queueConfig: QueueConfig = QueueConfig()
    private var logger: EventLogger = DefaultEventLogger(LogLevel.DEBUG)
    private var scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    fun queueConfig(block: QueueConfigBuilder.() -> Unit) {
        queueConfig = QueueConfigBuilder().apply(block).build()
    }
    
    fun logger(logger: EventLogger) {
        this.logger = logger
    }
    
    fun scope(scope: CoroutineScope) {
        this.scope = scope
    }
    
    fun build(): EventManager {
        return EventManager.create(
            EventManagerConfig(
                queueConfig = queueConfig,
                logger = logger,
                scope = scope
            )
        )
    }
}

class QueueConfigBuilder {
    private var maxSize: Int = 1000
    private var enableDeduplication: Boolean = true
    private var enableRetry: Boolean = true
    private var maxRetryCount: Int = 3
    private var defaultStrategy: ExecutionStrategy = org.acdd.eventmanager.strategy.SequentialStrategy
    private var timeoutMs: Long = 30000
    
    fun maxSize(size: Int) {
        maxSize = size
    }
    
    fun enableDeduplication(enable: Boolean) {
        enableDeduplication = enable
    }
    
    fun enableRetry(enable: Boolean) {
        enableRetry = enable
    }
    
    fun maxRetryCount(count: Int) {
        maxRetryCount = count
    }
    
    fun defaultStrategy(strategy: ExecutionStrategy) {
        defaultStrategy = strategy
    }
    
    fun timeoutMs(timeout: Long) {
        timeoutMs = timeout
    }
    
    fun build(): QueueConfig {
        return QueueConfig(
            maxSize = maxSize,
            enableDeduplication = enableDeduplication,
            enableRetry = enableRetry,
            maxRetryCount = maxRetryCount,
            defaultStrategy = defaultStrategy,
            timeoutMs = timeoutMs
        )
    }
}

/**
 * DSL函数 - 创建EventManager
 */
fun eventManager(block: EventManagerBuilder.() -> Unit): EventManager {
    return EventManagerBuilder().apply(block).build()
}
