package com.acdd.eventframework.core

import com.acdd.eventframework.logging.EventLogger
import com.acdd.eventframework.logging.SimpleEventLogger
import com.acdd.eventframework.queue.EventQueueManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

/**
 * 事件调度中心，协调注册中心与队列管理。
 */
class EventDispatcher(
    private val scope: CoroutineScope,
    val registry: EventRegistry = EventRegistry(),
    val logger: EventLogger = SimpleEventLogger()
) : EventEmitter {

    private val queueManager = EventQueueManager(scope, registry, logger).apply {
        attachEmitter(EventEmitter { emit(it) })
    }

    val statusFlow: SharedFlow<com.acdd.eventframework.queue.EventStatusUpdate> = queueManager.statusFlow
    val resultFlow: SharedFlow<EventResult> = queueManager.resultFlow
    val activeEvents: StateFlow<Set<EventKey>> = queueManager.activeEvents

    suspend fun dispatch(event: AppEvent<*>) {
        val accepted = queueManager.submit(event)
        if (!accepted) {
            logger.logStatus(event, EventStatus.SKIPPED, "dispatch rejected by queue manager")
        }
    }

    suspend fun dispatch(events: Iterable<AppEvent<*>>) {
        events.forEach { dispatch(it) }
    }

    suspend fun dispatchAndAwait(event: AppEvent<*>): EventResult {
        val awaiter = CompletableDeferred<EventResult>()
        val watcher = scope.launch {
            val result = resultFlow.filter { it.key == event.key }.first()
            awaiter.complete(result)
        }
        dispatch(event)
        val finalResult = awaiter.await()
        watcher.cancel()
        return finalResult
    }

    suspend fun cancel(key: EventKey) {
        queueManager.cancel(key)
    }

    fun <T : Any> register(
        eventType: KClass<out AppEvent<T>>,
        handler: EventHandler<T>,
        metadataPredicate: (EventMetadata) -> Boolean = { true },
        contextPredicate: (EventContext?) -> Boolean = { true }
    ): EventBinding<T> {
        val binding = EventBinding(
            eventType = eventType,
            handler = handler,
            metadataPredicate = metadataPredicate,
            contextPredicate = contextPredicate
        )
        registry.register(binding)
        return binding
    }

    fun <T : Any> unregister(
        eventType: KClass<out AppEvent<T>>,
        handler: EventHandler<T>
    ) {
        registry.unregister(eventType, handler)
    }

    fun clearHandlers() {
        registry.clear()
    }

    override suspend fun emit(event: AppEvent<*>) {
        dispatch(event)
    }

    suspend fun snapshot(): EventQueueManager.Snapshot = queueManager.snapshot()
}

inline fun <reified T : AppEvent<Payload>, Payload : Any> EventDispatcher.register(
    noinline handler: EventHandler<Payload>,
    noinline metadataPredicate: (EventMetadata) -> Boolean = { true },
    noinline contextPredicate: (EventContext?) -> Boolean = { true }
): EventBinding<Payload> = register(T::class, handler, metadataPredicate, contextPredicate)
