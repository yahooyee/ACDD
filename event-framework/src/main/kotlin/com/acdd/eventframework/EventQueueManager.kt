package com.acdd.eventframework

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.PriorityQueue
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

internal class EventQueueManager(
    private val config: EventConfig,
    private val dispatcher: suspend (EventEnvelope) -> EventDispatchResult,
    private val lifecycleEmitter: suspend (EventLifecycle) -> Unit
) {

    private val scope: CoroutineScope = config.scope

    private val sequentialChannel = Channel<EventEnvelope>(Channel.UNLIMITED)
    private val priorityQueue = PriorityQueue<QueuedEvent>(compareByDescending<QueuedEvent> {
        it.event.policy.priority.ordinal
    }.thenBy { it.enqueuedAt })
    private val priorityMutex = Mutex()
    private val prioritySignal = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val batchBuffers = mutableMapOf<String, BatchBuffer>()
    private val batchMutex = Mutex()

    private val dedupEntries = mutableMapOf<String, DedupEntry>()
    private val debounceEntries = mutableMapOf<String, Long>()
    private val pendingEvents = mutableMapOf<String, PendingEvent>()
    private val dependencyIndex = mutableMapOf<String, MutableSet<String>>()
    private val cancelledEventIds = mutableSetOf<String>()
    private val completedEvents = mutableSetOf<String>()
    private val stateMutex = Mutex()

    init {
        scope.launch { processSequentialQueue() }
        scope.launch { processPriorityQueue() }
        scope.launch { cleanupDedupLoop() }
    }

    suspend fun enqueue(event: EventEnvelope) {
        val now = config.timeProvider()
        lifecycleEmitter(EventLifecycle(event, EventLifecycle.State.ENQUEUED, now))

        if (handleDedup(event, now)) return
        if (handleDebounce(event, now)) {
            lifecycleEmitter(
                EventLifecycle(
                    event = event,
                    state = EventLifecycle.State.SKIPPED,
                    timestampMillis = now,
                    reason = "debounced"
                )
            )
            return
        }

        if (!resolveDependenciesOrQueue(event)) {
            lifecycleEmitter(
                EventLifecycle(
                    event = event,
                    state = EventLifecycle.State.SCHEDULED,
                    timestampMillis = now,
                    reason = "waiting_dependencies"
                )
            )
            return
        }

        schedule(event, now)
    }

    private suspend fun handleDedup(event: EventEnvelope, now: Long): Boolean {
        val dedup = event.policy.deduplication ?: return false
        return stateMutex.withLock {
            val entry = dedupEntries[dedup.key]
            if (entry != null && entry.expiryMillis > now) {
                when (dedup.mode) {
                    Deduplication.Mode.SKIP -> {
                        lifecycleEmitter(
                            EventLifecycle(
                                event,
                                EventLifecycle.State.SKIPPED,
                                now,
                                reason = "deduplicated"
                            )
                        )
                        true
                    }

                    Deduplication.Mode.REPLACE -> {
                        cancelledEventIds += entry.eventId
                        dedupEntries[dedup.key] = DedupEntry(event.id, computeExpiry(now, dedup.expiresIn))
                        false
                    }
                }
            } else {
                dedupEntries[dedup.key] = DedupEntry(event.id, computeExpiry(now, dedup.expiresIn))
                false
            }
        }
    }

    private suspend fun handleDebounce(event: EventEnvelope, now: Long): Boolean {
        val window = event.policy.debounceWindow
        if (window <= ZERO) return false
        val key = event.policy.debounceKey ?: event.descriptor.name
        return stateMutex.withLock {
            val last = debounceEntries[key]
            if (last != null && now - last < window.inWholeMilliseconds) {
                true
            } else {
                debounceEntries[key] = now
                false
            }
        }
    }

    private suspend fun resolveDependenciesOrQueue(event: EventEnvelope): Boolean =
        stateMutex.withLock {
            val dependencies = event.policy.dependencies
            if (dependencies.isEmpty()) return true

            val remaining = dependencies.filterNot { completedEvents.contains(it) }.toMutableSet()
            if (remaining.isEmpty()) return true
            pendingEvents[event.id] = PendingEvent(event, remaining)
            remaining.forEach { dependency ->
                dependencyIndex.getOrPut(dependency) { mutableSetOf() }.add(event.id)
            }
            false
        }

    private suspend fun schedule(event: EventEnvelope, now: Long) {
        lifecycleEmitter(
            EventLifecycle(
                event = event,
                state = EventLifecycle.State.SCHEDULED,
                timestampMillis = now
            )
        )
        when (val strategy = event.policy.strategy) {
            DispatchStrategy.Immediate -> scope.launch { handleEvent(event) }
            DispatchStrategy.Sequential -> sequentialChannel.send(event)
            is DispatchStrategy.Priority -> enqueuePriority(event, now)
            is DispatchStrategy.Batch -> enqueueBatch(event, strategy, now)
            is DispatchStrategy.Delayed -> scheduleDelay(event, strategy.delay)
        }
    }

    private fun enqueuePriority(event: EventEnvelope, now: Long) {
        scope.launch {
            priorityMutex.withLock {
                priorityQueue.add(QueuedEvent(event, now))
            }
            prioritySignal.tryEmit(Unit)
        }
    }

    private suspend fun enqueueBatch(event: EventEnvelope, strategy: DispatchStrategy.Batch, now: Long) {
        batchMutex.withLock {
            val buffer = batchBuffers.getOrPut(strategy.key) {
                BatchBuffer(mutableListOf(), now + strategy.maxWait.inWholeMilliseconds, strategy.maxItems)
                    .also { scheduleBatchFlush(strategy.key, strategy.maxWait) }
            }
            buffer.events.add(event)
            buffer.deadlineMillis = max(buffer.deadlineMillis, now + strategy.maxWait.inWholeMilliseconds)
            if (buffer.events.size >= strategy.maxItems) {
                flushBatch(strategy.key)
            }
        }
    }

    private fun scheduleBatchFlush(key: String, maxWait: Duration) {
        scope.launch {
            delay(maxWait.inWholeMilliseconds)
            batchMutex.withLock {
                flushBatch(key)
            }
        }
    }

    private fun flushBatch(key: String) {
        val buffer = batchBuffers[key] ?: return
        if (buffer.events.isEmpty()) return
        buffer.events.forEach { scope.launch { handleEvent(it) } }
        buffer.events.clear()
        batchBuffers.remove(key)
    }

    private fun scheduleDelay(event: EventEnvelope, delayDuration: Duration) {
        scope.launch {
            delay(delayDuration.inWholeMilliseconds)
            val adjustedEvent = event.copy(
                policy = event.policy.copy(strategy = DispatchStrategy.Sequential)
            )
            sequentialChannel.send(adjustedEvent)
        }
    }

    private suspend fun processSequentialQueue() {
        for (event in sequentialChannel) {
            handleEvent(event)
        }
    }

    private suspend fun processPriorityQueue() {
        prioritySignal.collect {
            while (true) {
                val queued = priorityMutex.withLock { priorityQueue.poll() } ?: break
                handleEvent(queued.event)
            }
        }
    }

    private suspend fun handleEvent(event: EventEnvelope) {
        val now = config.timeProvider()
        if (isCancelled(event.id)) {
            lifecycleEmitter(
                EventLifecycle(
                    event,
                    EventLifecycle.State.SKIPPED,
                    now,
                    reason = "replaced"
                )
            )
            return
        }
        lifecycleEmitter(EventLifecycle(event, EventLifecycle.State.DISPATCHING, now))

        val result = try {
            withTimeout(event.policy.timeout) {
                dispatcher(event)
            }
        } catch (timeout: Exception) {
            EventDispatchResult.Retry(timeout)
        }

        when (result) {
            EventDispatchResult.Consumed, EventDispatchResult.Ignored -> {
                markCompleted(event)
                lifecycleEmitter(
                    EventLifecycle(
                        event,
                        EventLifecycle.State.COMPLETED,
                        config.timeProvider()
                    )
                )
            }

            is EventDispatchResult.Retry -> {
                lifecycleEmitter(
                    EventLifecycle(
                        event,
                        EventLifecycle.State.RETRYING,
                        config.timeProvider(),
                        reason = result.reason?.message ?: "retry"
                    )
                )
                handleRetry(event, result.reason)
            }

            is EventDispatchResult.Failed -> {
                lifecycleEmitter(
                    EventLifecycle(
                        event,
                        EventLifecycle.State.FAILED,
                        config.timeProvider(),
                        error = result.error,
                        reason = if (result.recoverable) "recoverable_failure" else "failed"
                    )
                )
                if (result.recoverable) {
                    handleRetry(event, result.error)
                } else {
                    markCompleted(event)
                }
            }
        }
    }

    private suspend fun handleRetry(event: EventEnvelope, cause: Throwable?) {
        val policy = event.policy.retryPolicy
        val nextAttempt = event.attempt + 1
        if (!policy.shouldRetry(nextAttempt)) {
            config.logger.warn(
                "Retry policy reached max attempts for event ${event.id}",
                metadata = mapOf("attempt" to nextAttempt, "event" to event.descriptor.name),
                throwable = cause
            )
            markCompleted(event)
            return
        }

        val delayDuration = policy.nextDelay(nextAttempt)
        scope.launch {
            if (delayDuration > ZERO) {
                delay(delayDuration.inWholeMilliseconds)
            }
            val retryEvent = event.copy(attempt = nextAttempt)
            sequentialChannel.send(retryEvent)
        }
    }

    private suspend fun markCompleted(event: EventEnvelope) {
        val dependents = mutableSetOf<EventEnvelope>()
        stateMutex.withLock {
            completedEvents.add(event.id)
            val waitingIds = dependencyIndex.remove(event.id).orEmpty()
            waitingIds.forEach { eventId ->
                val pending = pendingEvents[eventId] ?: return@forEach
                pending.remaining.remove(event.id)
                if (pending.remaining.isEmpty()) {
                    pendingEvents.remove(eventId)
                    dependents += pending.event
                }
            }
        }
        dependents.forEach { dependent ->
            schedule(dependent, config.timeProvider())
        }
    }

    private suspend fun cleanupDedupLoop() {
        while (true) {
            delay(config.deduplicationCleanupInterval.inWholeMilliseconds)
            val now = config.timeProvider()
            stateMutex.withLock {
                dedupEntries.entries.removeIf { it.value.expiryMillis < now }
                debounceEntries.entries.removeIf { now - it.value > config.deduplicationCleanupInterval.inWholeMilliseconds }
                cancelledEventIds.removeIf { completedEvents.contains(it) }
            }
        }
    }

    private suspend fun isCancelled(eventId: String): Boolean =
        stateMutex.withLock { cancelledEventIds.contains(eventId) }

    private fun computeExpiry(now: Long, duration: Duration): Long =
        now + duration.inWholeMilliseconds

    private data class DedupEntry(val eventId: String, val expiryMillis: Long)
    private data class QueuedEvent(val event: EventEnvelope, val enqueuedAt: Long)

    private data class BatchBuffer(
        val events: MutableList<EventEnvelope>,
        var deadlineMillis: Long,
        val maxItems: Int
    )

    private data class PendingEvent(
        val event: EventEnvelope,
        val remaining: MutableSet<String>
    )
}
