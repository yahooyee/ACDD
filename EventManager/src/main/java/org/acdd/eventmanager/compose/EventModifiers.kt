package org.acdd.eventmanager.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.acdd.eventmanager.core.*
import org.acdd.eventmanager.strategy.DebounceStrategy
import org.acdd.eventmanager.strategy.ThrottleStrategy

/**
 * 可点击修饰符，支持事件发送
 */
@Composable
fun Modifier.clickableWithEvent(
    targetId: String,
    targetName: String? = null,
    priority: EventPriority = EventPriority.NORMAL,
    debounceMs: Long? = null,
    throttleMs: Long? = null,
    onClickBefore: (() -> Unit)? = null,
    onClickAfter: (() -> Unit)? = null
): Modifier {
    val dispatcher = rememberEventDispatcher()
    val scope = rememberCoroutineScope()
    
    return this.clickable {
        onClickBefore?.invoke()
        
        val event = UIClickEvent(
            targetId = targetId,
            targetName = targetName,
            priority = priority
        )
        
        when {
            debounceMs != null -> {
                dispatcher.dispatch(event, DebounceStrategy(debounceMs))
            }
            throttleMs != null -> {
                dispatcher.dispatch(event, ThrottleStrategy(throttleMs))
            }
            else -> {
                dispatcher.dispatch(event)
            }
        }
        
        onClickAfter?.invoke()
    }
}

/**
 * 长按事件修饰符
 */
@Composable
fun Modifier.longClickableWithEvent(
    targetId: String,
    priority: EventPriority = EventPriority.NORMAL,
    onLongClick: (() -> Unit)? = null
): Modifier {
    val dispatcher = rememberEventDispatcher()
    var pressStartTime by remember { mutableStateOf(0L) }
    
    return this.pointerInput(targetId) {
        detectTapGestures(
            onLongPress = {
                val duration = System.currentTimeMillis() - pressStartTime
                
                val event = UILongClickEvent(
                    targetId = targetId,
                    duration = duration,
                    priority = priority
                )
                
                dispatcher.dispatch(event)
                onLongClick?.invoke()
            },
            onPress = {
                pressStartTime = System.currentTimeMillis()
                tryAwaitRelease()
            }
        )
    }
}

/**
 * 双击事件
 */
@Composable
fun Modifier.doubleClickableWithEvent(
    targetId: String,
    priority: EventPriority = EventPriority.NORMAL,
    onDoubleClick: (() -> Unit)? = null
): Modifier {
    val dispatcher = rememberEventDispatcher()
    val scope = rememberCoroutineScope()
    var lastClickTime by remember { mutableStateOf(0L) }
    var clickJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    return this.clickable {
        val currentTime = System.currentTimeMillis()
        
        if (currentTime - lastClickTime < 300) {
            // 双击
            clickJob?.cancel()
            
            val event = CustomEvent(
                eventName = "ui_double_click",
                eventData = mapOf("targetId" to targetId),
                priority = priority
            )
            
            dispatcher.dispatch(event)
            onDoubleClick?.invoke()
            
            lastClickTime = 0L
        } else {
            // 单击，等待可能的第二次点击
            clickJob?.cancel()
            clickJob = scope.launch {
                delay(300)
                lastClickTime = 0L
            }
            lastClickTime = currentTime
        }
    }
}

/**
 * 滑动事件监听
 */
@Composable
fun Modifier.swipeableWithEvent(
    targetId: String,
    priority: EventPriority = EventPriority.NORMAL,
    onSwipe: ((SwipeDirection) -> Unit)? = null
): Modifier {
    val dispatcher = rememberEventDispatcher()
    var startX by remember { mutableStateOf(0f) }
    var startY by remember { mutableStateOf(0f) }
    
    return this.pointerInput(targetId) {
        detectTapGestures(
            onPress = { offset ->
                startX = offset.x
                startY = offset.y
                tryAwaitRelease()
            }
        )
    }.pointerInput(targetId) {
        detectTapGestures { offset ->
            val deltaX = offset.x - startX
            val deltaY = offset.y - startY
            val distance = kotlin.math.sqrt(deltaX * deltaX + deltaY * deltaY)
            
            if (distance > 100) {
                val direction = when {
                    kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) -> {
                        if (deltaX > 0) SwipeDirection.RIGHT else SwipeDirection.LEFT
                    }
                    else -> {
                        if (deltaY > 0) SwipeDirection.DOWN else SwipeDirection.UP
                    }
                }
                
                val event = UISwipeEvent(
                    direction = direction,
                    distance = distance,
                    priority = priority
                )
                
                dispatcher.dispatch(event)
                onSwipe?.invoke(direction)
            }
        }
    }
}
