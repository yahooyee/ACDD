package org.acdd.eventmanager.queue

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.acdd.eventmanager.core.*
import org.acdd.eventmanager.logger.EventLogger
import org.acdd.eventmanager.strategy.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue

/**
 * 事件队列配置
 */
data class QueueConfig(
    val maxSize: Int = 1000,
    val enableDeduplication: Boolean = true,
    val enableRetry: Boolean = true,
    val maxRetryCount: Int = 3,
    val defaultStrategy: ExecutionStrategy = SequentialStrategy,
    val timeoutMs: Long = 30000
)

/**
 * 智能事件队列管理器
 */
class EventQueueManager(
    private val config: QueueConfig = QueueConfig(),
    private val logger: EventLogger,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    // 事件队列 - 按优先级排序
    private val priorityQueue = PriorityBlockingQueue<EventWrapper>(
        config.maxSize,
        compareByDescending<EventWrapper> { it.event.priority.value }
            .thenBy { it.timestamp }
    )
    
    // 事件处理器映射
    private val handlers = ConcurrentHashMap<String, MutableList<EventHandler<Event>>>()
    
    // 事件去重集合
    private val processedEvents = Collections.synchronizedSet(HashSet<String>())
    
    // 防抖定时器映射
    private val debounceJobs = ConcurrentHashMap<String, Job>()
    
    // 节流状态映射
    private val throttleState = ConcurrentHashMap<String, Long>()
    
    // 依赖关系映射 eventId -> 依赖的eventId列表
    private val dependencies = ConcurrentHashMap<String, List<String>>()
    
    // 已完成事件集合
    private val completedEvents = Collections.synchronizedSet(HashSet<String>())
    
    // 批量处理缓存
    private val batchCache = ConcurrentHashMap<String, MutableList<Event>>()
    
    // 事件流
    private val _eventFlow = MutableSharedFlow<Event>(replay = 0)
    val eventFlow: SharedFlow<Event> = _eventFlow.asSharedFlow()
    
    // 队列状态流
    private val _queueStateFlow = MutableStateFlow(QueueState())
    val queueStateFlow: StateFlow<QueueState> = _queueStateFlow.asStateFlow()
    
    init {
        startProcessing()
    }
    
    /**
     * 提交事件
     */
    fun submitEvent(
        event: Event,
        strategy: ExecutionStrategy = config.defaultStrategy,
        dependsOn: List<String> = emptyList()
    ) {
        scope.launch {
            // 检查队列是否已满
            if (priorityQueue.size >= config.maxSize) {
                logger.warn("Queue is full, dropping event: ${event.id}")
                return@launch
            }
            
            // 事件去重
            if (config.enableDeduplication && !shouldProcessEvent(event)) {
                logger.debug("Event deduplicated: ${event.id}")
                return@launch
            }
            
            // 设置依赖关系
            if (dependsOn.isNotEmpty()) {
                dependencies[event.id] = dependsOn
            }
            
            logger.info("Event submitted: ${event.type.name} (${event.id})")
            
            // 根据策略处理事件
            when (strategy) {
                is ImmediateStrategy -> {
                    executeEvent(EventWrapper(event, strategy))
                }
                is DebounceStrategy -> {
                    handleDebounce(event, strategy)
                }
                is ThrottleStrategy -> {
                    handleThrottle(event, strategy)
                }
                is BatchStrategy -> {
                    handleBatch(event, strategy)
                }
                is DelayStrategy -> {
                    scope.launch {
                        delay(strategy.getDelay(event))
                        priorityQueue.offer(EventWrapper(event, strategy))
                        updateQueueState()
                    }
                }
                else -> {
                    priorityQueue.offer(EventWrapper(event, strategy))
                    updateQueueState()
                }
            }
            
            _eventFlow.emit(event)
        }
    }
    
    /**
     * 注册事件处理器
     */
    fun <T : Event> registerHandler(
        eventTypeName: String,
        handler: EventHandler<T>
    ) {
        val handlerList = handlers.getOrPut(eventTypeName) { mutableListOf() }
        @Suppress("UNCHECKED_CAST")
        handlerList.add(handler as EventHandler<Event>)
        logger.debug("Handler registered for: $eventTypeName")
    }
    
    /**
     * 取消注册事件处理器
     */
    fun unregisterHandler(eventTypeName: String, handler: EventHandler<*>) {
        handlers[eventTypeName]?.remove(handler)
        logger.debug("Handler unregistered for: $eventTypeName")
    }
    
    /**
     * 清空队列
     */
    fun clear() {
        priorityQueue.clear()
        processedEvents.clear()
        debounceJobs.values.forEach { it.cancel() }
        debounceJobs.clear()
        throttleState.clear()
        batchCache.clear()
        updateQueueState()
        logger.info("Queue cleared")
    }
    
    /**
     * 启动事件处理
     */
    private fun startProcessing() {
        scope.launch {
            while (isActive) {
                try {
                    val wrapper = withTimeoutOrNull(100) {
                        priorityQueue.poll()
                    }
                    
                    if (wrapper != null) {
                        executeEvent(wrapper)
                    } else {
                        delay(50) // 避免CPU空转
                    }
                } catch (e: Exception) {
                    logger.error("Error processing queue: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 执行事件
     */
    private suspend fun executeEvent(wrapper: EventWrapper, retryCount: Int = 0) {
        val event = wrapper.event
        
        // 检查依赖
        if (!checkDependencies(event)) {
            logger.debug("Event dependencies not met, re-queuing: ${event.id}")
            priorityQueue.offer(wrapper)
            delay(100)
            return
        }
        
        logger.debug("Executing event: ${event.type.name} (${event.id})")
        
        val eventHandlers = handlers[event.type.name] ?: emptyList()
        
        if (eventHandlers.isEmpty()) {
            logger.warn("No handler found for: ${event.type.name}")
            markEventCompleted(event.id)
            return
        }
        
        // 执行所有处理器
        eventHandlers.forEach { handler ->
            try {
                withTimeout(config.timeoutMs) {
                    val result = handler.handle(event)
                    
                    when (result) {
                        is EventResult.Success -> {
                            logger.debug("Event handled successfully: ${event.id}")
                            markEventCompleted(event.id)
                        }
                        is EventResult.Failure -> {
                            logger.error("Event handling failed: ${event.id}, ${result.message}")
                            handleRetry(wrapper, retryCount, result.error)
                        }
                        is EventResult.Skipped -> {
                            logger.debug("Event skipped: ${event.id}")
                        }
                        is EventResult.Retry -> {
                            delay(result.delayMs)
                            handleRetry(wrapper, retryCount)
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                logger.error("Event execution timeout: ${event.id}")
                handleRetry(wrapper, retryCount, e)
            } catch (e: Exception) {
                logger.error("Event execution error: ${event.id}, ${e.message}")
                handleRetry(wrapper, retryCount, e)
            }
        }
        
        updateQueueState()
    }
    
    /**
     * 处理重试
     */
    private suspend fun handleRetry(wrapper: EventWrapper, retryCount: Int, error: Throwable? = null) {
        if (config.enableRetry && retryCount < config.maxRetryCount) {
            logger.info("Retrying event: ${wrapper.event.id}, attempt: ${retryCount + 1}")
            delay(1000L * (retryCount + 1)) // 指数退避
            executeEvent(wrapper, retryCount + 1)
        } else {
            logger.error("Event failed after ${config.maxRetryCount} retries: ${wrapper.event.id}")
            markEventCompleted(wrapper.event.id)
        }
    }
    
    /**
     * 防抖处理
     */
    private fun handleDebounce(event: Event, strategy: DebounceStrategy) {
        val key = event.type.name
        
        // 取消之前的任务
        debounceJobs[key]?.cancel()
        
        // 创建新的延迟任务
        val job = scope.launch {
            delay(strategy.getDelay(event))
            priorityQueue.offer(EventWrapper(event, strategy))
            updateQueueState()
        }
        
        debounceJobs[key] = job
    }
    
    /**
     * 节流处理
     */
    private fun handleThrottle(event: Event, strategy: ThrottleStrategy) {
        if (strategy.shouldExecuteImmediately(event)) {
            scope.launch {
                executeEvent(EventWrapper(event, strategy))
            }
        } else {
            logger.debug("Event throttled: ${event.id}")
        }
    }
    
    /**
     * 批量处理
     */
    private fun handleBatch(event: Event, strategy: BatchStrategy) {
        val key = event.type.name
        val batch = batchCache.getOrPut(key) { mutableListOf() }
        
        synchronized(batch) {
            batch.add(event)
            
            if (batch.size >= strategy.getBatchSize()) {
                executeBatch(key, batch.toList(), strategy)
                batch.clear()
            }
        }
        
        // 启动定时器，确保小批量也能被处理
        scope.launch {
            delay(strategy.getTimeWindow())
            synchronized(batch) {
                if (batch.isNotEmpty()) {
                    executeBatch(key, batch.toList(), strategy)
                    batch.clear()
                }
            }
        }
    }
    
    /**
     * 执行批量事件
     */
    private fun executeBatch(key: String, events: List<Event>, strategy: BatchStrategy) {
        logger.info("Executing batch: $key, size: ${events.size}")
        events.forEach { event ->
            priorityQueue.offer(EventWrapper(event, strategy))
        }
        updateQueueState()
    }
    
    /**
     * 检查是否应该处理事件（去重）
     */
    private fun shouldProcessEvent(event: Event): Boolean {
        val key = "${event.type.name}_${event.hashCode()}"
        return processedEvents.add(key)
    }
    
    /**
     * 检查依赖关系
     */
    private fun checkDependencies(event: Event): Boolean {
        val deps = dependencies[event.id] ?: return true
        return deps.all { it in completedEvents }
    }
    
    /**
     * 标记事件完成
     */
    private fun markEventCompleted(eventId: String) {
        completedEvents.add(eventId)
        dependencies.remove(eventId)
    }
    
    /**
     * 更新队列状态
     */
    private fun updateQueueState() {
        _queueStateFlow.value = QueueState(
            size = priorityQueue.size,
            processedCount = processedEvents.size,
            pendingDependencies = dependencies.size
        )
    }
    
    /**
     * 关闭队列
     */
    fun shutdown() {
        scope.cancel()
        clear()
        logger.info("Queue manager shutdown")
    }
}

/**
 * 事件包装类
 */
private data class EventWrapper(
    val event: Event,
    val strategy: ExecutionStrategy,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 队列状态
 */
data class QueueState(
    val size: Int = 0,
    val processedCount: Int = 0,
    val pendingDependencies: Int = 0
)
