package com.acdd.eventframework.queue

import com.acdd.eventframework.core.AppEvent
import com.acdd.eventframework.core.BackoffStrategy
import com.acdd.eventframework.core.DispatchStrategy
import com.acdd.eventframework.core.EventEmitter
import com.acdd.eventframework.core.EventHandler
import com.acdd.eventframework.core.EventKey
import com.acdd.eventframework.core.EventMetadata
import com.acdd.eventframework.core.EventPriority
import com.acdd.eventframework.core.EventResult
import com.acdd.eventframework.core.EventScope
import com.acdd.eventframework.core.EventStatus
import com.acdd.eventframework.core.EventRegistry
import com.acdd.eventframework.core.RetryConfig
import com.acdd.eventframework.logging.EventLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap

private const val DEFAULT_BATCH_DELAY_MS = 200L

/**
 * 智能队列管理器，负责事件调度、去重、防抖、依赖、重试等。
 */
class EventQueueManager(
    parentScope: CoroutineScope,
    private val registry: EventRegistry,
    private val logger: EventLogger = EventLogger.None
) {

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val mutex = Mutex()

    private val sequentialQueue = ArrayDeque<QueuedEvent>()
    private val priorityQueue = PriorityQueue<QueuedEvent>(compareByDescending<QueuedEvent> { priorityWeight(it.metadata.priority) }
        .thenBy { it.enqueuedAt })
    private val immediateQueue = ArrayDeque<QueuedEvent>()
    private val delayedQueue = PriorityQueue<DelayedQueuedEvent> { a, b -> a.scheduledAt.compareTo(b.scheduledAt) }
    private val batchBuckets = mutableMapOf<String, MutableList<QueuedEvent>>()
    private val batchJobs = mutableMapOf<String, Job>()

    private val dedupRecords = ConcurrentHashMap<String, Instant>()
    private val debounceJobs = mutableMapOf<String, Job>()

    private val runningEvents = ConcurrentHashMap<EventKey, Unit>()
    private val results = ConcurrentHashMap<EventKey, EventResult>()

    private val _statusFlow = MutableSharedFlow<EventStatusUpdate>(extraBufferCapacity = 128)
    val statusFlow: SharedFlow<EventStatusUpdate> = _statusFlow.asSharedFlow()

    private val _resultFlow = MutableSharedFlow<EventResult>(extraBufferCapacity = 128)
    val resultFlow: SharedFlow<EventResult> = _resultFlow.asSharedFlow()

    private val _activeEvents = MutableStateFlow<Set<EventKey>>(emptySet())
    val activeEvents: StateFlow<Set<EventKey>> = _activeEvents.asStateFlow()

    private val signal = Channel<Unit>(capacity = Channel.CONFLATED)

    @Volatile
    private var emitter: EventEmitter? = null

    init {
        scope.launch {
            dispatcherLoop()
        }
    }

    fun attachEmitter(emitter: EventEmitter) {
        this.emitter = emitter
    }

    suspend fun submit(event: AppEvent<*>): Boolean {
        val metadata = event.metadata
        val now = Clock.System.now()

        metadata.expiresAt?.let {
            if (it < now) {
                logger.logStatus(event, EventStatus.SKIPPED, "event expired before enqueue")
                _statusFlow.emit(
                    EventStatusUpdate(
                        event.key,
                        EventStatus.SKIPPED,
                        event,
                        attempt = 0,
                        message = "Expired"
                    )
                )
                return false
            }
        }

        val dedupKey = dedupKey(event)

        val accepted = mutex.withLock {
            val dedupWithin = metadata.dedup.dedupWithin
            val lastTime = dedupRecords[dedupKey]
            if (lastTime != null && dedupWithin > ZERO && now - lastTime < dedupWithin) {
                logger.logStatus(event, EventStatus.SKIPPED, "deduplicated within $dedupWithin")
                _statusFlow.emit(
                    EventStatusUpdate(
                        event.key,
                        EventStatus.SKIPPED,
                        event,
                        attempt = 0,
                        message = "Deduplicated"
                    )
                )
                return@withLock false
            }

            val debounce = metadata.dedup.debounce
            dedupRecords[dedupKey] = now
            if (debounce > ZERO) {
                scheduleDebounceLocked(dedupKey, event, debounce)
            } else {
                enqueueLocked(QueuedEvent(event, enqueuedAt = now))
            }
            true
        }

        if (accepted) {
            signal.trySend(Unit)
        }
        return accepted
    }

    suspend fun cancel(key: EventKey) {
        mutex.withLock {
            sequentialQueue.removeIf { it.event.key == key }
            priorityQueue.removeIf { it.event.key == key }
            immediateQueue.removeIf { it.event.key == key }
            delayedQueue.removeIf { it.queued.event.key == key }
            batchBuckets.values.forEach { bucket -> bucket.removeIf { it.event.key == key } }
            debounceJobs.remove(key.value)?.cancel()
            runningEvents.remove(key)
            publishActive()
        }
        signal.trySend(Unit)
    }

    suspend fun flushBatch(batchKey: String) {
        mutex.withLock {
            val bucket = batchBuckets.remove(batchKey) ?: return
            bucket.forEach { sequentialQueue.addLast(it) }
        }
        batchJobs.remove(batchKey)?.cancel()
        signal.trySend(Unit)
    }

    private fun scheduleDebounceLocked(dedupKey: String, event: AppEvent<*>, debounce: Duration) {
        debounceJobs[dedupKey]?.cancel()
        debounceJobs[dedupKey] = scope.launch {
            delay(debounce)
            enqueueNow(QueuedEvent(event, enqueuedAt = Clock.System.now()))
        }
    }

    private suspend fun enqueueLocked(queuedEvent: QueuedEvent) {
        when (queuedEvent.strategy) {
            DispatchStrategy.SEQUENTIAL -> sequentialQueue.addLast(queuedEvent)
            DispatchStrategy.PRIORITY -> priorityQueue.add(queuedEvent)
            DispatchStrategy.IMMEDIATE -> immediateQueue.addLast(queuedEvent)
            DispatchStrategy.BATCH -> handleBatchEnqueue(queuedEvent)
            DispatchStrategy.DELAYED -> handleDelayEnqueue(queuedEvent)
        }
        _statusFlow.emit(
            EventStatusUpdate(
                queuedEvent.event.key,
                EventStatus.QUEUED,
                queuedEvent.event,
                queuedEvent.attempt
            )
        )
    }

    private suspend fun enqueueNow(queuedEvent: QueuedEvent) {
        mutex.withLock {
            enqueueLocked(queuedEvent)
        }
    }

    private fun handleBatchEnqueue(queued: QueuedEvent) {
        val batchKey = queued.metadata.batchKey ?: queued.event.key.value
        val bucket = batchBuckets.getOrPut(batchKey) { mutableListOf() }
        bucket += queued

        val delay = if (queued.metadata.delayBy > ZERO) queued.metadata.delayBy else DEFAULT_BATCH_DELAY_MS.milliseconds
        batchJobs[batchKey]?.cancel()
        batchJobs[batchKey] = scope.launch {
            delay(delay)
            flushBatch(batchKey)
        }
    }

    private fun handleDelayEnqueue(queued: QueuedEvent) {
        val delayBy = if (queued.metadata.delayBy > ZERO) queued.metadata.delayBy else 500.milliseconds
        val scheduled = DelayedQueuedEvent(
            scheduledAt = Clock.System.now() + delayBy,
            queued = queued
        )
        delayedQueue.add(scheduled)
    }

    private suspend fun dispatcherLoop() {
        while (scope.isActive) {
            val next = takeNext()
            if (next == null) {
                waitForSignal()
                continue
            }

            process(next)
        }
    }

    private suspend fun waitForSignal() {
        val peekDelay = mutex.withLock { delayedQueue.peek()?.scheduledAt }
        if (peekDelay != null) {
            val now = Clock.System.now()
            val waitDuration = (peekDelay - now).coerceAtLeast(ZERO)
            if (waitDuration == ZERO) {
                signal.trySend(Unit)
                return
            }
            val receive = scope.launch {
                signal.receive()
            }
            delay(waitDuration)
            if (!receive.isCompleted) receive.cancel()
        } else {
            signal.receive()
        }
    }

    private suspend fun takeNext(): QueuedEvent? {
        return mutex.withLock {
            immediateQueue.removeFirstOrNull()
                ?: priorityQueue.poll()
                ?: sequentialQueue.removeFirstOrNull()
                ?: extractDelayed()
        }
    }

    private fun extractDelayed(): QueuedEvent? {
        val head = delayedQueue.peek() ?: return null
        val now = Clock.System.now()
        return if (head.scheduledAt <= now) {
            delayedQueue.poll().queued
        } else {
            null
        }
    }

    private suspend fun process(queued: QueuedEvent) {
        val event = queued.event
        val metadata = queued.metadata
        val attempt = queued.attempt + 1

        when (val dependency = evaluateDependencies(event, metadata, queued.enqueuedAt, attempt)) {
            DependencyStatus.Ready -> Unit
            DependencyStatus.Waiting -> {
                mutex.withLock { sequentialQueue.addLast(queued.copy(enqueuedAt = Clock.System.now())) }
                signal.trySend(Unit)
                return
            }

            is DependencyStatus.Blocked -> {
                val blockedResult = dependency.result
                results[event.key] = blockedResult
                _resultFlow.emit(blockedResult)
                _statusFlow.emit(
                    EventStatusUpdate(
                        event.key,
                        blockedResult.status,
                        event,
                        attempt,
                        blockedResult.message
                    )
                )
                logger.logResult(blockedResult)
                return
            }
        }

        val handlers = findHandlers(event)
        if (handlers.isEmpty()) {
            logger.logStatus(event, EventStatus.SKIPPED, "no handlers registered")
            _statusFlow.emit(EventStatusUpdate(event.key, EventStatus.SKIPPED, event, attempt, "No handler"))
            results[event.key] = EventResult.Success(
                key = event.key,
                startedAt = queued.enqueuedAt,
                finishedAt = Clock.System.now(),
                message = "No handler registered"
            )
            _resultFlow.emit(results.getValue(event.key))
            return
        }

        val job = scope.launch {
            runningEvents[event.key] = Unit
            publishActive()
            _statusFlow.emit(EventStatusUpdate(event.key, EventStatus.RUNNING, event, attempt))
            logger.logStatus(event, EventStatus.RUNNING, "attempt $attempt")

            val startedAt = Clock.System.now()
            val result = runCatching {
                executeHandlers(event, handlers, metadata, attempt, startedAt)
            }.getOrElse { error ->
                logger.logError(event, error, error.message)
                EventResult.Failure(
                    key = event.key,
                    startedAt = startedAt,
                    finishedAt = Clock.System.now(),
                    attempts = attempt,
                    error = error
                )
            }

            runningEvents.remove(event.key)
            publishActive()
            results[event.key] = result
            _resultFlow.emit(result)
            _statusFlow.emit(EventStatusUpdate(event.key, result.status, event, attempt, (result as? EventResult.Failure)?.message))
            logger.logResult(result)

            if (shouldRetry(result, metadata.retry, attempt)) {
                val nextAttempt = queued.copy(
                    enqueuedAt = Clock.System.now(),
                    attempt = attempt
                )
                val delay = calculateBackoff(metadata.retry.backoffStrategy, attempt)
                scope.launch {
                    if (delay > ZERO) delay(delay)
                    enqueueNow(nextAttempt)
                }
            }
        }

        job.invokeOnCompletion {
            if (it is CancellationException) {
                scope.launch {
                    _statusFlow.emit(EventStatusUpdate(event.key, EventStatus.CANCELLED, event, attempt, it.message))
                    logger.logStatus(event, EventStatus.CANCELLED, it.message)
                }
            }
        }
    }

    private fun calculateBackoff(strategy: BackoffStrategy, attempt: Int): Duration = when (strategy) {
        BackoffStrategy.None -> ZERO
        is BackoffStrategy.Linear -> strategy.step * attempt
        is BackoffStrategy.Exponential -> strategy.base * strategy.multiplier.pow(attempt - 1)
    }

    private suspend fun executeHandlers(
        event: AppEvent<*>,
        handlers: List<EventHandler<Any>>,
        metadata: EventMetadata,
        attempt: Int,
        startedAt: Instant
    ): EventResult {
        var currentResult: EventResult = EventResult.Success(
            key = event.key,
            startedAt = startedAt,
            finishedAt = startedAt,
            attempts = attempt,
            message = "No handler executed"
        )

        val timeout = metadata.retry.timeout
        val emitter = emitter ?: EventEmitter { }

        for (handler in handlers) {
            val handlerResult = try {
                withTimeout(timeout) {
                    val scopeJob = Job()
                    val scope = EventScope(
                        emitter = emitter,
                        job = scopeJob,
                        context = event.context,
                        metadata = metadata
                    )
                    @Suppress("UNCHECKED_CAST")
                    handler.handle(event as AppEvent<Any>, scope)
                }
            } catch (timeoutCancellation: TimeoutCancellationException) {
                EventResult.Timeout(
                    key = event.key,
                    startedAt = startedAt,
                    finishedAt = Clock.System.now(),
                    attempts = attempt,
                    message = "Handler timeout after $timeout"
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                EventResult.Failure(
                    key = event.key,
                    startedAt = startedAt,
                    finishedAt = Clock.System.now(),
                    attempts = attempt,
                    error = throwable
                )
            }

            currentResult = handlerResult

            if (handlerResult.status != EventStatus.SUCCEEDED) {
                break
            }
        }

        return currentResult
    }

    private fun shouldRetry(result: EventResult, retryConfig: RetryConfig, attempt: Int): Boolean {
        if (retryConfig.maxRetries <= 0) return false
        if (attempt >= retryConfig.maxRetries) return false
        return when (result.status) {
            EventStatus.FAILED,
            EventStatus.TIMEOUT -> true
            else -> false
        }
    }

    private fun priorityWeight(priority: EventPriority): Int = when (priority) {
        EventPriority.CRITICAL -> 4
        EventPriority.HIGH -> 3
        EventPriority.NORMAL -> 2
        EventPriority.LOW -> 1
    }

    private fun dedupKey(event: AppEvent<*>): String {
        val scope = event.metadata.dedup.dedupScope
        return when (scope) {
            com.acdd.eventframework.core.DedupScope.GLOBAL -> event.key.value
            com.acdd.eventframework.core.DedupScope.MODULE -> "${event.context?.module}:${event.key.value}"
            com.acdd.eventframework.core.DedupScope.CONTEXT -> "${event.context?.module}:${event.context?.screen}:${event.key.value}"
        }
    }

    private fun findHandlers(event: AppEvent<*>): List<EventHandler<Any>> {
        @Suppress("UNCHECKED_CAST")
        return registry.findHandlers(event as AppEvent<Any>) as List<EventHandler<Any>>
    }

    private fun publishActive() {
        _activeEvents.value = runningEvents.keys.toSet()
    }

    private fun evaluateDependencies(
        event: AppEvent<*>,
        metadata: EventMetadata,
        enqueuedAt: Instant,
        attempt: Int
    ): DependencyStatus {
        val deps = metadata.dependencies
        if (deps.isEmpty()) return DependencyStatus.Ready

        val now = Clock.System.now()
        deps.forEach { dependency ->
            val result = results[dependency.key] ?: return DependencyStatus.Waiting
            if (dependency.mustSucceed && result.status != EventStatus.SUCCEEDED) {
                logger.logStatus(event, EventStatus.SKIPPED, "dependency ${dependency.key.value} failed")
                return DependencyStatus.Blocked(
                    EventResult.Cancelled(
                        key = event.key,
                        startedAt = enqueuedAt,
                        finishedAt = now,
                        attempts = attempt,
                        message = "Dependency ${dependency.key.value} failed"
                    )
                )
            }
            val waited = now - enqueuedAt
            if (waited > dependency.timeout) {
                return DependencyStatus.Blocked(
                    EventResult.Timeout(
                        key = event.key,
                        startedAt = enqueuedAt,
                        finishedAt = now,
                        attempts = attempt,
                        message = "Dependency timeout on ${dependency.key.value}"
                    )
                )
            }
        }
        return DependencyStatus.Ready
    }

    data class Snapshot(
        val sequential: Int,
        val priority: Int,
        val immediate: Int,
        val delayed: Int,
        val batching: Map<String, Int>,
        val running: Int
    )

    suspend fun snapshot(): Snapshot = mutex.withLock {
        Snapshot(
            sequential = sequentialQueue.size,
            priority = priorityQueue.size,
            immediate = immediateQueue.size,
            delayed = delayedQueue.size,
            batching = batchBuckets.mapValues { it.value.size },
            running = runningEvents.size
        )
    }
}

private data class DelayedQueuedEvent(
    val scheduledAt: Instant,
    val queued: QueuedEvent
)

private sealed interface DependencyStatus {
    data object Ready : DependencyStatus
    data object Waiting : DependencyStatus
    data class Blocked(val result: EventResult) : DependencyStatus
}
