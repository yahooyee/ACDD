# EventManager 快速入门指南

## 5分钟上手

### 第一步：初始化EventManager

在Application或主Activity中初始化：

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // 使用默认配置
        val eventManager = EventManager.getInstance()
        
        // 或使用自定义配置
        val customEventManager = eventManager {
            queueConfig {
                maxSize(2000)
                enableDeduplication(true)
                enableRetry(true)
            }
            logger(DefaultEventLogger(LogLevel.DEBUG))
        }
    }
}
```

### 第二步：在Compose中提供EventManager

```kotlin
@Composable
fun App() {
    val eventManager = remember { EventManager.getInstance() }
    
    CompositionLocalProvider(LocalEventManager provides eventManager) {
        MainScreen()
    }
}
```

### 第三步：发送和监听事件

```kotlin
@Composable
fun MyScreen() {
    val dispatcher = rememberEventDispatcher()
    
    // 监听事件
    ObserveEvent<UIClickEvent>("ui_click") { event ->
        Toast.makeText(context, "点击了: ${event.targetId}", Toast.LENGTH_SHORT).show()
    }
    
    // 发送事件
    Button(
        onClick = {
            dispatcher.dispatch(
                UIClickEvent(
                    targetId = "my_button",
                    targetName = "我的按钮"
                )
            )
        }
    ) {
        Text("点击我")
    }
}
```

### 第四步：使用事件修饰符（更简单！）

```kotlin
@Composable
fun SimpleButton() {
    Box(
        modifier = Modifier
            .clickableWithEvent(
                targetId = "simple_button",
                targetName = "简单按钮",
                debounceMs = 300  // 自动防抖
            )
    ) {
        Text("点击我")
    }
}
```

## 常见场景

### 场景1：防止重复点击

```kotlin
Button(
    modifier = Modifier.clickableWithEvent(
        targetId = "submit_button",
        debounceMs = 500  // 500ms内只响应一次
    ),
    onClick = { submitForm() }
) {
    Text("提交")
}
```

### 场景2：显示弹窗

```kotlin
// 发送弹窗事件
dispatcher.dispatch(
    DialogEvent(
        dialogId = "confirm_dialog",
        title = "确认",
        message = "确定要删除吗？",
        action = DialogAction.SHOW
    )
)

// 监听弹窗事件
ObserveEvent<DialogEvent>("business_dialog") { event ->
    when (event.action) {
        DialogAction.SHOW -> showDialog(event.message)
        DialogAction.DISMISS -> dismissDialog()
    }
}
```

### 场景3：页面导航

```kotlin
// 发送导航事件
dispatcher.dispatch(
    NavigateEvent(
        route = "/detail",
        params = mapOf("id" to productId)
    )
)

// 监听导航事件
ObserveEvent<NavigateEvent>("nav_navigate") { event ->
    navController.navigate(event.route)
}
```

### 场景4：用户行为埋点

```kotlin
// 自动发送埋点
Button(
    onClick = {
        // 业务逻辑
        purchaseItem()
        
        // 发送埋点事件
        dispatcher.dispatch(
            UserActionEvent(
                actionName = "purchase_button_click",
                actionParams = mapOf(
                    "product_id" to productId,
                    "price" to price
                )
            )
        )
    }
) {
    Text("购买")
}
```

## 进阶技巧

### 技巧1：使用依赖关系

```kotlin
// 事件A
val eventA = CustomEvent("task_download")
dispatcher.dispatch(eventA)

// 事件B依赖A完成后执行
val eventB = CustomEvent("task_install")
dispatcher.dispatch(
    event = eventB,
    dependsOn = listOf(eventA.id)
)
```

### 技巧2：批量处理

```kotlin
import org.acdd.eventmanager.strategy.BatchStrategy

// 埋点事件批量发送
dispatcher.dispatch(
    event = analyticsEvent,
    strategy = BatchStrategy(
        batchSize = 10,        // 每10个事件批量处理
        timeWindowMs = 5000    // 或5秒超时后处理
    )
)
```

### 技巧3：自定义处理器

```kotlin
// 注册处理器并添加重试逻辑
eventManager.registerHandler<CustomEvent>("my_event") { event ->
    try {
        performNetworkRequest()
        EventResult.Success()
    } catch (e: Exception) {
        EventResult.Failure(e)  // 自动重试
    }
}
```

## 完整示例

```kotlin
@Composable
fun ProductDetailScreen(productId: String) {
    val dispatcher = rememberEventDispatcher()
    var showDialog by remember { mutableStateOf(false) }
    
    // 页面浏览埋点
    LaunchedEffect(Unit) {
        dispatcher.dispatch(
            PageViewEvent(
                pageName = "product_detail",
                pageParams = mapOf("product_id" to productId)
            )
        )
    }
    
    // 监听弹窗事件
    ObserveEvent<DialogEvent>("business_dialog") { event ->
        showDialog = event.action == DialogAction.SHOW
    }
    
    Column {
        // 产品信息
        ProductInfo()
        
        // 购买按钮（带防抖）
        Button(
            modifier = Modifier.clickableWithEvent(
                targetId = "buy_button",
                targetName = "购买按钮",
                debounceMs = 500,
                onClickAfter = {
                    // 显示确认弹窗
                    dispatcher.dispatch(
                        DialogEvent(
                            dialogId = "confirm",
                            message = "确认购买？",
                            action = DialogAction.SHOW
                        )
                    )
                }
            ),
            onClick = {}
        ) {
            Text("立即购买")
        }
        
        // 分享按钮（带节流）
        Button(
            modifier = Modifier.clickableWithEvent(
                targetId = "share_button",
                throttleMs = 2000  // 2秒内只能点击一次
            ),
            onClick = { shareProduct() }
        ) {
            Text("分享")
        }
    }
    
    // 确认弹窗
    if (showDialog) {
        ConfirmDialog(
            onConfirm = {
                dispatcher.dispatch(UserActionEvent("purchase_confirmed"))
                purchase()
            },
            onDismiss = {
                dispatcher.dispatch(
                    DialogEvent(dialogId = "confirm", action = DialogAction.DISMISS)
                )
            }
        )
    }
}
```

## 最佳实践

1. **使用有意义的事件ID**：`targetId = "home_tab_products"` 而不是 `"btn1"`
2. **合理设置优先级**：关键业务用HIGH，埋点用LOW
3. **善用Compose生命周期**：使用`ObserveEvent`和`RegisterEventHandler`自动管理生命周期
4. **统一事件管理**：在Application层注册全局事件处理器
5. **日志分级**：开发用DEBUG，生产用ERROR

## 故障排查

### 问题1：事件没有被处理
- 检查是否注册了对应的处理器
- 检查事件类型名称是否匹配
- 查看日志输出

### 问题2：防抖/节流不生效
- 确认使用了正确的策略
- 检查延迟时间设置
- 验证事件类型是否一致

### 问题3：内存泄漏
- 使用Compose的`ObserveEvent`会自动清理
- 手动注册的处理器记得在onDestroy中取消注册

## 更多资源

- [完整文档](README.md)
- [示例代码](src/main/java/org/acdd/eventmanager/example/Examples.kt)
- [API参考](各类的KDoc注释)

---

现在开始使用EventManager，让事件管理变得简单！🚀
