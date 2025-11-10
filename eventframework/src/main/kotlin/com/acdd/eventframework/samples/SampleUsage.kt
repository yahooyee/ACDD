package com.acdd.eventframework.samples

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.acdd.eventframework.compose.rememberEventDispatcher
import com.acdd.eventframework.core.AppEvent
import com.acdd.eventframework.core.DedupConfig
import com.acdd.eventframework.core.DispatchStrategy
import com.acdd.eventframework.core.EventContext
import com.acdd.eventframework.core.EventPriority
import com.acdd.eventframework.core.EventResult
import com.acdd.eventframework.core.UiInteraction
import com.acdd.eventframework.core.register
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * 提供一个简单示例，展示如何在 Compose 中使用事件框架。
 */
object SampleEventContract {
    data class ShowDialogPayload(val title: String, val body: String)

    fun dialogEvent(context: EventContext, payload: ShowDialogPayload): AppEvent.Ui<ShowDialogPayload> {
        return AppEvent.Ui(
            metadata = com.acdd.eventframework.core.EventMetadata(
                category = com.acdd.eventframework.core.EventCategory.UI,
                priority = EventPriority.HIGH,
                strategy = DispatchStrategy.IMMEDIATE,
                dedup = DedupConfig(dedupWithin = 1.seconds),
                tags = setOf("dialog")
            ),
            payload = payload,
            interaction = UiInteraction.Click,
            context = context
        )
    }
}

@Composable
fun SampleEventButton() {
    val dispatcher = rememberEventDispatcher()
    val context = remember {
        EventContext(module = "home", screen = "dashboard")
    }
    val scope = rememberCoroutineScope()

    // 注册处理器
    remember(dispatcher) {
        dispatcher.register(AppEvent.Ui::class, handler = { event, scope ->
            // 这里可以连接实际的弹窗系统
            println("Show dialog: ${event.payload.title}")
            EventResult.Success(
                key = event.key,
                startedAt = event.metadata.createdAt,
                finishedAt = event.metadata.createdAt,
                message = "Dialog shown"
            )
        }) { metadata ->
            "dialog" in metadata.tags
        }
    }

    Button(onClick = {
        scope.launch {
            dispatcher.dispatch(
                SampleEventContract.dialogEvent(
                    context,
                    SampleEventContract.ShowDialogPayload(
                        title = "欢迎",
                        body = "您已成功接入事件框架"
                    )
                )
            )
        }
    }) {
        Text("展示弹窗")
    }
}
