package com.acdd.eventframework.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * 事件注册中心：集中管理所有事件类型及其处理器。
 */
class EventRegistry {

    private val bindings: MutableMap<KClass<*>, MutableSet<EventBinding<*>>> =
        ConcurrentHashMap()

    private val _bindingStream = MutableStateFlow<Map<KClass<*>, Set<EventBinding<*>>>>(emptyMap())
    val bindingStream: StateFlow<Map<KClass<*>, Set<EventBinding<*>>>>
        get() = _bindingStream.asStateFlow()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> register(binding: EventBinding<T>) {
        val slot = bindings.getOrPut(binding.eventType) { mutableSetOf() }
        slot += binding
        publish()
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> unregister(
        eventType: KClass<out AppEvent<T>>,
        handler: EventHandler<T>
    ) {
        val slot = bindings[eventType] ?: return
        slot.removeIf { (it as EventBinding<T>).handler == handler }
        if (slot.isEmpty()) {
            bindings.remove(eventType)
        }
        publish()
    }

    fun clear() {
        bindings.clear()
        publish()
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> findHandlers(
        event: AppEvent<T>
    ): List<EventHandler<T>> {
        val registered = bindings[event::class].orEmpty()
        return registered
            .filter { binding ->
                binding as EventBinding<T>
                binding.metadataPredicate(event.metadata) &&
                        binding.contextPredicate(event.context)
            }
            .map { (it as EventBinding<T>).handler }
    }

    private fun publish() {
        _bindingStream.value = bindings.mapValues { it.value.toSet() }
    }
}
