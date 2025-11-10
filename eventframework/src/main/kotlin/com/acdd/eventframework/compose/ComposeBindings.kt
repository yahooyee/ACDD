package com.acdd.eventframework.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.acdd.eventframework.core.AppEvent
import com.acdd.eventframework.core.EventContext
import com.acdd.eventframework.core.EventDispatcher
import com.acdd.eventframework.core.EventKey
import com.acdd.eventframework.core.EventResult
import com.acdd.eventframework.core.EventStatus
import com.acdd.eventframework.core.EventSystem
import com.acdd.eventframework.logging.EventLogger
import com.acdd.eventframework.logging.SimpleEventLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 在 Compose 环境中记忆化 EventDispatcher，可选生命周期自动清理。
 */
@Composable
fun rememberEventDispatcher(
    scope: CoroutineScope = rememberCoroutineScope(),
    logger: EventLogger = remember { SimpleEventLogger() },
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    autoClearHandlers: Boolean = true
): EventDispatcher {
    val dispatcher = remember(scope, logger) {
        EventDispatcher(scope, logger = logger)
    }

    DisposableEffect(lifecycleOwner, dispatcher, autoClearHandlers) {
        if (autoClearHandlers) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    dispatcher.clearHandlers()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        } else {
            onDispose { }
        }
    }

    return dispatcher
}

/**
 * 声明式事件绑定：在 key 或 event 变化时触发派发。
 */
@Composable
fun EventEffect(
    dispatcher: EventDispatcher,
    key: Any?,
    eventProvider: () -> AppEvent<*>,
    enabled: Boolean = true,
    awaitResult: Boolean = false,
    onResult: (EventResult) -> Unit = {}
) {
    val currentOnResult by rememberUpdatedState(onResult)
    LaunchedEffect(key1 = key, key2 = eventProvider, key3 = enabled) {
        if (!enabled) return@LaunchedEffect
        if (awaitResult) {
            val result = dispatcher.dispatchAndAwait(eventProvider())
            currentOnResult(result)
        } else {
            dispatcher.dispatch(eventProvider())
        }
    }
}

/**
 * 根据事件结果更新 Compose State。
 */
@Composable
fun <T> rememberEventState(
    dispatcher: EventDispatcher,
    eventKey: EventKey,
    initial: T,
    transform: (EventResult) -> T?
): State<T> {
    val state = remember { mutableStateOf(initial) }
    LaunchedEffect(dispatcher, eventKey) {
        dispatcher.resultFlow.collect { result ->
            if (result.key == eventKey) {
                transform(result)?.let { state.value = it }
            }
        }
    }
    return state
}

/**
 * 监听事件状态变化，自动感知生命周期。
 */
@Composable
fun observeEventStatus(
    dispatcher: EventDispatcher,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    onStatusChanged: (EventKey, EventStatus) -> Unit
) {
    val currentCallback by rememberUpdatedState(onStatusChanged)
    LaunchedEffect(dispatcher, lifecycleOwner) {
        dispatcher.statusFlow.collect { update ->
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                currentCallback(update.key, update.status)
            }
        }
    }
}

/**
 * 将业务状态与事件系统绑定，自动派发 UI 操作事件。
 */
@Composable
fun rememberEventBinding(
    dispatcher: EventDispatcher,
    context: EventContext,
    eventFactory: (EventContext) -> AppEvent<*>,
    trigger: Any?
) {
    LaunchedEffect(dispatcher, context, trigger) {
        dispatcher.dispatch(eventFactory(context))
    }
}

/**
 * 同时记忆 EventSystem，提供内存日志等能力。
 */
@Composable
fun rememberEventSystem(
    baseLogger: EventLogger = EventLogger.None,
    enableInMemoryLog: Boolean = true
): EventSystem {
    val scope = rememberCoroutineScope()
    return remember(scope, baseLogger, enableInMemoryLog) {
        EventSystem.create(scope, baseLogger, enableInMemoryLog)
    }
}
