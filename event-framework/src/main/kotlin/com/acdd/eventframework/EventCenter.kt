package com.acdd.eventframework

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * 事件中心，统一对外暴露事件管理能力。
 */
class EventCenter private constructor(
    private val config: EventConfig
) {

    private val scope = config.scope

    private val _events = MutableSharedFlow<EventEnvelope>(
        replay = 0,
        extraBufferCapacity = config.bufferSize,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<EventEnvelope> = _events

    private val _lifecycle = MutableSharedFlow<EventLifecycle>(
        replay = 0,
        extraBufferCapacity = config.bufferSize,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val lifecycle: SharedFlow<EventLifecycle> = _lifecycle

    private val handlerState = AtomicReference<List<HandlerRegistration>>(emptyList())

    private val queueManager = EventQueueManager(
        config = config,
        dispatcher = ::dispatchToHandlers,
        lifecycleEmitter = ::emitLifecycle
    )

    suspend fun emit(event: EventEnvelope) {
        _events.emit(event)
        queueManager.enqueue(event)
    }

    fun emitAsync(event: EventEnvelope): Job = scope.launch { emit(event) }

    fun emit(
        name: String,
        domain: EventDomain,
        type: EventType = EventType.Custom(name),
        payload: Any,
        policy: EventPolicy? = null,
        buildDescriptor: EventDescriptorBuilder.() -> Unit = {}
    ): Job {
        val descriptor = EventDescriptorBuilder(name, domain, type).apply(buildDescriptor).build()
        val mergedPolicy = config.defaultPolicy.mergeWith(policy)
        val envelope = EventEnvelope(descriptor, payload, mergedPolicy)
        return emitAsync(envelope)
    }

    fun observe(filter: EventFilter = { true }): Flow<EventEnvelope> = events.filter(filter)

    fun registerHandler(
        name: String,
        priority: Int = 0,
        filter: EventFilter = { true },
        handler: EventHandler
    ): EventSubscription {
        val registration = HandlerRegistration(name, priority, filter, handler)
        handlerState.updateAndGet { current ->
            (current + registration).sortedByDescending { it.priority }
        }
        return EventSubscription { unregister(registration) }
    }

    fun unregister(subscription: EventSubscription) {
        subscription.cancel()
    }

    private fun unregister(registration: HandlerRegistration) {
        handlerState.updateAndGet { current -> current.filterNot { it === registration } }
    }

    private suspend fun dispatchToHandlers(event: EventEnvelope): EventDispatchResult {
        val handlers = handlerState.get()
        var consumed = false
        handlers.forEach { registration ->
            if (!registration.filter(event)) return@forEach
            try {
                when (val result = registration.handler.handle(event)) {
                    EventDispatchResult.Consumed -> consumed = true
                    EventDispatchResult.Ignored -> Unit
                    is EventDispatchResult.Retry -> return result
                    is EventDispatchResult.Failed -> return result
                }
            } catch (throwable: Throwable) {
                config.logger.error(
                    "Handler ${registration.name} crashed",
                    throwable,
                    metadata = mapOf("event" to event.descriptor.name)
                )
                return EventDispatchResult.Failed(throwable, recoverable = false)
            }
        }
        return if (consumed) EventDispatchResult.Consumed else EventDispatchResult.Ignored
    }

    private suspend fun emitLifecycle(lifecycle: EventLifecycle) {
        _lifecycle.emit(lifecycle)
    }

    companion object {
        @JvmStatic
        fun create(config: EventConfig = EventConfig()): EventCenter = EventCenter(config)
    }
}

fun interface EventHandler {
    suspend fun handle(event: EventEnvelope): EventDispatchResult
}

class EventSubscription internal constructor(
    private val onCancel: (() -> Unit)?
) {
    fun cancel() {
        onCancel?.invoke()
    }
}

private data class HandlerRegistration(
    val name: String,
    val priority: Int,
    val filter: EventFilter,
    val handler: EventHandler
)

class EventDescriptorBuilder internal constructor(
    private val name: String,
    private val domain: EventDomain,
    private val type: EventType
) {
    private var id: String = UUID.randomUUID().toString()
    private var source: String? = null
    private val tags: MutableSet<String> = mutableSetOf()
    private val attributes: MutableMap<String, Any?> = mutableMapOf()

    fun id(value: String): EventDescriptorBuilder {
        id = value
        return this
    }

    fun source(value: String?): EventDescriptorBuilder {
        source = value
        return this
    }

    fun tag(vararg values: String): EventDescriptorBuilder {
        tags += values
        return this
    }

    fun attribute(key: String, value: Any?): EventDescriptorBuilder {
        attributes[key] = value
        return this
    }

    internal fun build(): EventDescriptor =
        EventDescriptor(
            id = id,
            name = name,
            domain = domain,
            type = type,
            source = source,
            tags = tags,
            attributes = attributes
        )
}
