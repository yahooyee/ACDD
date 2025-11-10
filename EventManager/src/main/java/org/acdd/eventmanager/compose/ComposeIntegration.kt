package org.acdd.eventmanager.compose

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.acdd.eventmanager.core.*
import org.acdd.eventmanager.EventManager

/**
 * 生命周期感知的事件监听
 * @param eventType 要监听的事件类型名称
 * @param minActiveState 最小活跃状态，默认为STARTED
 * @param onEvent 事件回调
 */
@Composable
inline fun <reified T : Event> ObserveEvent(
    eventType: String,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    crossinline onEvent: (T) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val eventManager = LocalEventManager.current
    
    DisposableEffect(eventType, lifecycleOwner) {
        val scope = lifecycleOwner.lifecycleScope
        val job = scope.launch {
            eventManager.eventFlow
                .filter { it.type.name == eventType && it is T }
                .collect { event ->
                    if (lifecycleOwner.lifecycle.currentState.isAtLeast(minActiveState)) {
                        @Suppress("UNCHECKED_CAST")
                        onEvent(event as T)
                    }
                }
        }
        
        onDispose {
            job.cancel()
        }
    }
}

/**
 * 收集事件为State
 */
@Composable
inline fun <reified T : Event> collectEventAsState(
    eventType: String,
    initial: T? = null
): State<T?> {
    val eventManager = LocalEventManager.current
    
    return produceState<T?>(initialValue = initial, eventType) {
        eventManager.eventFlow
            .filter { it.type.name == eventType && it is T }
            .collect { event ->
                @Suppress("UNCHECKED_CAST")
                value = event as T
            }
    }
}

/**
 * 事件发送函数
 */
@Composable
fun rememberEventDispatcher(): EventDispatcher {
    val eventManager = LocalEventManager.current
    return remember { EventDispatcher(eventManager) }
}

/**
 * 事件分发器
 */
class EventDispatcher(private val eventManager: EventManager) {
    fun dispatch(event: Event) {
        eventManager.dispatch(event)
    }
    
    fun <T : Event> dispatch(
        event: T,
        strategy: org.acdd.eventmanager.strategy.ExecutionStrategy? = null
    ) {
        eventManager.dispatch(event, strategy)
    }
}

/**
 * 生命周期扩展属性
 */
val LifecycleOwner.lifecycleScope: kotlinx.coroutines.CoroutineScope
    @Composable
    get() = rememberCoroutineScope()

/**
 * 提供EventManager的CompositionLocal
 */
val LocalEventManager = compositionLocalOf<EventManager> {
    error("No EventManager provided")
}

/**
 * 生命周期感知的事件处理器注册
 */
@Composable
inline fun <reified T : Event> RegisterEventHandler(
    eventType: String,
    crossinline handler: suspend (T) -> EventResult
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val eventManager = LocalEventManager.current
    
    DisposableEffect(eventType, lifecycleOwner) {
        val eventHandler = EventHandler<T> { event ->
            handler(event)
        }
        
        @Suppress("UNCHECKED_CAST")
        eventManager.registerHandler(eventType, eventHandler as EventHandler<Event>)
        
        // 添加生命周期观察者
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                eventManager.unregisterHandler(eventType, eventHandler)
            }
        }
        
        lifecycleOwner.lifecycle.addObserver(observer)
        
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            eventManager.unregisterHandler(eventType, eventHandler)
        }
    }
}

/**
 * 队列状态观察
 */
@Composable
fun rememberQueueState(): State<org.acdd.eventmanager.queue.QueueState> {
    val eventManager = LocalEventManager.current
    return eventManager.queueState.collectAsState()
}
