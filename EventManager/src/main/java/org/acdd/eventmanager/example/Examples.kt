package org.acdd.eventmanager.example

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.acdd.eventmanager.EventManager
import org.acdd.eventmanager.compose.*
import org.acdd.eventmanager.core.*
import org.acdd.eventmanager.strategy.DebounceStrategy
import org.acdd.eventmanager.strategy.ThrottleStrategy

/**
 * EventManager使用示例
 */

// ========== 示例1：基础事件发送和监听 ==========

@Composable
fun BasicEventExample() {
    val dispatcher = rememberEventDispatcher()
    var clickCount by remember { mutableStateOf(0) }
    
    // 监听点击事件
    ObserveEvent<UIClickEvent>("ui_click") { event ->
        clickCount++
        println("按钮被点击: ${event.targetId}, 总计: $clickCount 次")
    }
    
    Column(
        modifier = Modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = {
                dispatcher.dispatch(
                    UIClickEvent(
                        targetId = "example_button",
                        targetName = "示例按钮"
                    )
                )
            }
        ) {
            Text("点击我")
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        Text("点击次数: $clickCount")
    }
}

// ========== 示例2：使用事件修饰符 ==========

@Composable
fun EventModifierExample() {
    var message by remember { mutableStateOf("等待点击...") }
    
    // 监听事件并更新UI
    ObserveEvent<UIClickEvent>("ui_click") { event ->
        message = "点击了: ${event.targetName}"
    }
    
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(message)
        
        // 普通点击
        Button(
            modifier = Modifier.clickableWithEvent(
                targetId = "btn_normal",
                targetName = "普通按钮"
            ),
            onClick = {}
        ) {
            Text("普通点击")
        }
        
        // 防抖点击
        Button(
            modifier = Modifier.clickableWithEvent(
                targetId = "btn_debounce",
                targetName = "防抖按钮",
                debounceMs = 500
            ),
            onClick = {}
        ) {
            Text("防抖点击(500ms)")
        }
        
        // 节流点击
        Button(
            modifier = Modifier.clickableWithEvent(
                targetId = "btn_throttle",
                targetName = "节流按钮",
                throttleMs = 1000
            ),
            onClick = {}
        ) {
            Text("节流点击(1000ms)")
        }
    }
}

// ========== 示例3：业务事件 - 弹窗管理 ==========

@Composable
fun DialogEventExample() {
    val dispatcher = rememberEventDispatcher()
    var showDialog by remember { mutableStateOf(false) }
    var dialogMessage by remember { mutableStateOf("") }
    
    // 监听弹窗事件
    ObserveEvent<DialogEvent>("business_dialog") { event ->
        when (event.action) {
            DialogAction.SHOW -> {
                dialogMessage = event.message ?: "默认消息"
                showDialog = true
            }
            DialogAction.DISMISS -> {
                showDialog = false
            }
            else -> {}
        }
    }
    
    Column(modifier = Modifier.padding(16.dp)) {
        Button(
            onClick = {
                dispatcher.dispatch(
                    DialogEvent(
                        dialogId = "example_dialog",
                        title = "提示",
                        message = "这是一个通过事件显示的弹窗",
                        action = DialogAction.SHOW
                    )
                )
            }
        ) {
            Text("显示弹窗")
        }
        
        if (showDialog) {
            AlertDialog(
                onDismissRequest = {
                    dispatcher.dispatch(
                        DialogEvent(
                            dialogId = "example_dialog",
                            action = DialogAction.DISMISS
                        )
                    )
                },
                title = { Text("提示") },
                text = { Text(dialogMessage) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            dispatcher.dispatch(
                                DialogEvent(
                                    dialogId = "example_dialog",
                                    action = DialogAction.CONFIRM
                                )
                            )
                            showDialog = false
                        }
                    ) {
                        Text("确认")
                    }
                }
            )
        }
    }
}

// ========== 示例4：导航事件 ==========

@Composable
fun NavigationEventExample() {
    val dispatcher = rememberEventDispatcher()
    var currentRoute by remember { mutableStateOf("home") }
    
    // 监听导航事件
    ObserveEvent<NavigateEvent>("nav_navigate") { event ->
        currentRoute = event.route
        println("导航到: ${event.route}, 参数: ${event.params}")
    }
    
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("当前页面: $currentRoute", style = MaterialTheme.typography.titleMedium)
        
        Button(
            onClick = {
                dispatcher.dispatch(
                    NavigateEvent(
                        route = "detail",
                        params = mapOf("id" to "123")
                    )
                )
            }
        ) {
            Text("导航到详情页")
        }
        
        Button(
            onClick = {
                dispatcher.dispatch(
                    NavigateEvent(
                        route = "settings",
                        params = mapOf("tab" to "account")
                    )
                )
            }
        ) {
            Text("导航到设置页")
        }
        
        Button(
            onClick = {
                dispatcher.dispatch(BackEvent())
            }
        ) {
            Text("返回")
        }
    }
}

// ========== 示例5：埋点事件 ==========

@Composable
fun AnalyticsEventExample() {
    val dispatcher = rememberEventDispatcher()
    
    // 注册埋点处理器
    RegisterEventHandler<PageViewEvent>("analytics_page_view") { event ->
        // 发送到埋点服务
        println("📊 页面浏览: ${event.pageName}, 参数: ${event.pageParams}")
        EventResult.Success()
    }
    
    RegisterEventHandler<UserActionEvent>("analytics_user_action") { event ->
        println("📊 用户行为: ${event.actionName}, 参数: ${event.actionParams}")
        EventResult.Success()
    }
    
    LaunchedEffect(Unit) {
        // 页面打开时自动发送页面浏览事件
        dispatcher.dispatch(
            PageViewEvent(
                pageName = "example_page",
                pageParams = mapOf("source" to "demo")
            )
        )
    }
    
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("埋点示例", style = MaterialTheme.typography.titleMedium)
        
        Button(
            onClick = {
                dispatcher.dispatch(
                    UserActionEvent(
                        actionName = "button_click",
                        actionParams = mapOf(
                            "button_name" to "purchase",
                            "product_id" to "prod_123"
                        )
                    )
                )
            }
        ) {
            Text("点击购买（会发送埋点）")
        }
    }
}

// ========== 示例6：自定义事件 ==========

// 自定义事件类型
data class UserLoginEvent(
    val userId: String,
    val loginMethod: String,
    override val priority: EventPriority = EventPriority.HIGH
) : BaseEvent(
    type = object : EventType {
        override val name = "user_login"
        override val category = EventCategory.BUSINESS
    },
    priority = priority
)

@Composable
fun CustomEventExample() {
    val dispatcher = rememberEventDispatcher()
    var loginStatus by remember { mutableStateOf("未登录") }
    
    // 注册自定义事件处理器
    RegisterEventHandler<UserLoginEvent>("user_login") { event ->
        loginStatus = "用户 ${event.userId} 已通过 ${event.loginMethod} 登录"
        EventResult.Success()
    }
    
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(loginStatus)
        
        Button(
            onClick = {
                dispatcher.dispatch(
                    UserLoginEvent(
                        userId = "user_12345",
                        loginMethod = "微信"
                    )
                )
            }
        ) {
            Text("微信登录")
        }
        
        Button(
            onClick = {
                dispatcher.dispatch(
                    UserLoginEvent(
                        userId = "user_67890",
                        loginMethod = "手机号"
                    )
                )
            }
        ) {
            Text("手机号登录")
        }
    }
}

// ========== 示例7：队列状态监控 ==========

@Composable
fun QueueStateExample() {
    val queueState by rememberQueueState()
    val dispatcher = rememberEventDispatcher()
    
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("队列状态监控", style = MaterialTheme.typography.titleMedium)
        
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("队列大小: ${queueState.size}")
                Text("已处理事件: ${queueState.processedCount}")
                Text("待处理依赖: ${queueState.pendingDependencies}")
            }
        }
        
        Button(
            onClick = {
                // 批量发送事件测试
                repeat(10) { i ->
                    dispatcher.dispatch(
                        CustomEvent(
                            eventName = "test_event_$i",
                            eventData = mapOf("index" to i)
                        )
                    )
                }
            }
        ) {
            Text("批量发送10个事件")
        }
    }
}

// ========== 示例8：完整应用示例 ==========

@Composable
fun CompleteExample() {
    val eventManager = remember {
        org.acdd.eventmanager.eventManager {
            queueConfig {
                maxSize(1000)
                enableDeduplication(true)
                enableRetry(true)
            }
        }
    }
    
    CompositionLocalProvider(LocalEventManager provides eventManager) {
        var selectedTab by remember { mutableStateOf(0) }
        
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("EventManager示例") })
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding)) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                        Text("基础", modifier = Modifier.padding(16.dp))
                    }
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                        Text("修饰符", modifier = Modifier.padding(16.dp))
                    }
                    Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) {
                        Text("业务", modifier = Modifier.padding(16.dp))
                    }
                }
                
                when (selectedTab) {
                    0 -> BasicEventExample()
                    1 -> EventModifierExample()
                    2 -> DialogEventExample()
                }
            }
        }
    }
}
