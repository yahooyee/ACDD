package com.acdd.eventframework

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.flowWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 在 Compose 中记住一个事件中心实例。
 */
@Composable
fun rememberEventCenter(config: EventConfig = EventConfig()): EventCenter {
    val coroutineScope = rememberCoroutineScope()
    val effectiveConfig = remember(config, coroutineScope) {
        config.copy(scope = coroutineScope)
    }
    return remember(effectiveConfig) { EventCenter.create(effectiveConfig) }
}

/**
 * 在 Compose 范式中直接收集事件。
 */
@Composable
fun EventCollector(
    eventCenter: EventCenter,
    filter: EventFilter = { true },
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    onEvent: suspend (EventEnvelope) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnEvent by rememberUpdatedState(onEvent)
    LaunchedEffect(eventCenter, lifecycleOwner, filter, minActiveState) {
        eventCenter.observe(filter)
            .flowWithLifecycle(lifecycleOwner.lifecycle, minActiveState)
            .collect { currentOnEvent(it) }
    }
}

/**
 * 收集生命周期事件（如完成、失败等），用于驱动 UI 状态。
 */
@Composable
fun EventLifecycleCollector(
    eventCenter: EventCenter,
    filter: (EventLifecycle) -> Boolean = { true },
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    onLifecycle: suspend (EventLifecycle) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnLifecycle by rememberUpdatedState(onLifecycle)
    LaunchedEffect(eventCenter, lifecycleOwner, filter, minActiveState) {
        eventCenter.lifecycle
            .filter(filter)
            .flowWithLifecycle(lifecycleOwner.lifecycle, minActiveState)
            .collect { currentOnLifecycle(it) }
    }
}

/**
 * 将事件绑定到点击交互。
 */
fun Modifier.emitOnClick(
    eventCenter: EventCenter,
    name: String,
    domain: EventDomain = EventDomain.UI,
    payload: Any = Unit,
    policy: EventPolicy? = null,
    descriptor: EventDescriptorBuilder.() -> Unit = {}
): Modifier {
    val interactionType = EventType.UiInteraction(UiInteractionKind.Click)
    return this.then(
        Modifier.clickable {
            eventCenter.emit(
                name = name,
                domain = domain,
                type = interactionType,
                payload = payload,
                policy = policy,
                buildDescriptor = descriptor
            )
        }
    )
}

/**
 * 将 Flow<EventEnvelope> 与 Compose 状态联动。
 */
@Composable
fun rememberEventState(
    eventCenter: EventCenter,
    filter: EventFilter = { true }
): State<List<EventEnvelope>> {
    val scope = rememberCoroutineScope()
    val stateFlow = remember(eventCenter, filter) {
        MutableEventState(scope, eventCenter.observe(filter))
    }
    return stateFlow.state.collectAsState()
}

private class MutableEventState(
    private val scope: CoroutineScope,
    events: Flow<EventEnvelope>
) {
    private val _state = MutableStateFlow<List<EventEnvelope>>(emptyList())
    val state: Flow<List<EventEnvelope>> = _state

    init {
        scope.launch {
            events.collect { event ->
                _state.value = _state.value + event
            }
        }
    }
}
