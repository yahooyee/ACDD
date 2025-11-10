# EventManager 使用说明

## 在其他模块中使用EventManager

### 第一步：添加模块依赖

在需要使用EventManager的模块的`build.gradle`中添加依赖：

```gradle
dependencies {
    implementation project(':EventManager')
}
```

### 第二步：在Application中初始化

```kotlin
class MyApplication : Application() {
    lateinit var eventManager: EventManager
    
    override fun onCreate() {
        super.onCreate()
        
        // 使用默认配置初始化
        eventManager = EventManager.getInstance()
        
        // 或使用自定义配置
        eventManager = eventManager {
            queueConfig {
                maxSize(2000)
                enableDeduplication(true)
                enableRetry(true)
                maxRetryCount(3)
            }
            logger(DefaultEventLogger(LogLevel.DEBUG))
        }
    }
}
```

### 第三步：在Compose中使用

#### 1. 提供EventManager

在根Composable中使用CompositionLocalProvider：

```kotlin
@Composable
fun App() {
    val eventManager = remember { 
        (application as MyApplication).eventManager 
    }
    
    CompositionLocalProvider(LocalEventManager provides eventManager) {
        // 你的应用内容
        MainScreen()
    }
}
```

#### 2. 发送事件

```kotlin
@Composable
fun MyButton() {
    val dispatcher = rememberEventDispatcher()
    
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

#### 3. 监听事件

```kotlin
@Composable
fun MyScreen() {
    ObserveEvent<UIClickEvent>("ui_click") { event ->
        println("按钮被点击: ${event.targetId}")
        // 处理点击事件
    }
}
```

#### 4. 使用事件修饰符（推荐）

```kotlin
@Composable
fun SmartButton() {
    Box(
        modifier = Modifier
            .clickableWithEvent(
                targetId = "smart_button",
                targetName = "智能按钮",
                debounceMs = 300  // 防抖300ms
            )
    ) {
        Text("智能按钮")
    }
}
```

### 第四步：在传统View中使用

```kotlin
class MyActivity : AppCompatActivity() {
    private lateinit var eventManager: EventManager
    private val clickHandler = EventHandler<UIClickEvent> { event ->
        println("按钮被点击: ${event.targetId}")
        EventResult.Success()
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        eventManager = (application as MyApplication).eventManager
        
        // 注册处理器
        eventManager.registerHandler("ui_click", clickHandler)
        
        // 发送事件
        button.setOnClickListener {
            eventManager.dispatch(
                UIClickEvent(
                    targetId = "button_1",
                    targetName = "按钮1"
                )
            )
        }
    }
    
    override fun onDestroy() {
        // 取消注册
        eventManager.unregisterHandler("ui_click", clickHandler)
        super.onDestroy()
    }
}
```

## 常用场景示例

### 场景1：全局弹窗管理

```kotlin
// 在Application中注册全局处理器
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        eventManager.registerHandler<DialogEvent>("business_dialog") { event ->
            when (event.action) {
                DialogAction.SHOW -> {
                    // 显示全局弹窗
                    showGlobalDialog(event.title, event.message)
                }
                DialogAction.DISMISS -> {
                    // 关闭弹窗
                    dismissGlobalDialog()
                }
            }
            EventResult.Success()
        }
    }
}

// 在任何地方发送弹窗事件
dispatcher.dispatch(
    DialogEvent(
        dialogId = "error_dialog",
        title = "错误",
        message = "网络请求失败",
        action = DialogAction.SHOW
    )
)
```

### 场景2：统一埋点管理

```kotlin
// 注册埋点处理器
eventManager.registerHandler<PageViewEvent>("analytics_page_view") { event ->
    // 发送到Analytics服务
    Analytics.logPageView(event.pageName, event.pageParams)
    EventResult.Success()
}

eventManager.registerHandler<UserActionEvent>("analytics_user_action") { event ->
    // 发送到Analytics服务
    Analytics.logUserAction(event.actionName, event.actionParams)
    EventResult.Success()
}

// 在页面中自动发送埋点
@Composable
fun ProductDetailScreen(productId: String) {
    LaunchedEffect(Unit) {
        dispatcher.dispatch(
            PageViewEvent(
                pageName = "product_detail",
                pageParams = mapOf("product_id" to productId)
            )
        )
    }
}
```

### 场景3：页面导航

```kotlin
// 注册导航处理器
eventManager.registerHandler<NavigateEvent>("nav_navigate") { event ->
    navController.navigate(event.route) {
        if (event.clearBackStack) {
            popUpTo(0)
        }
    }
    EventResult.Success()
}

// 发送导航事件
dispatcher.dispatch(
    NavigateEvent(
        route = "/product/detail",
        params = mapOf("id" to productId)
    )
)
```

### 场景4：防止重复点击

```kotlin
@Composable
fun SubmitButton() {
    Box(
        modifier = Modifier
            .clickableWithEvent(
                targetId = "submit_button",
                debounceMs = 500,  // 500ms内只响应一次
                onClickAfter = {
                    submitForm()
                }
            )
    ) {
        Text("提交")
    }
}
```

### 场景5：批量上报数据

```kotlin
import org.acdd.eventmanager.strategy.BatchStrategy

// 使用批量策略
val batchStrategy = BatchStrategy(
    batchSize = 10,      // 每10条数据批量上报
    timeWindowMs = 5000  // 或5秒超时后上报
)

// 发送埋点事件
dispatcher.dispatch(
    event = analyticsEvent,
    strategy = batchStrategy
)
```

## 调试技巧

### 1. 开启详细日志

```kotlin
eventManager.setLogLevel(LogLevel.DEBUG)
```

### 2. 监控队列状态

```kotlin
@Composable
fun DebugPanel() {
    val queueState by rememberQueueState()
    
    Text("队列大小: ${queueState.size}")
    Text("已处理: ${queueState.processedCount}")
    Text("待处理依赖: ${queueState.pendingDependencies}")
}
```

### 3. 添加日志处理器

```kotlin
eventManager.registerHandler<Event>("*") { event ->
    Log.d("EventDebug", "事件: ${event.type.name}, ID: ${event.id}")
    EventResult.Success()
}
```

## 性能优化建议

### 1. 合理使用优先级

```kotlin
// 关键业务使用HIGH
DialogEvent(..., priority = EventPriority.HIGH)

// 埋点使用LOW
PageViewEvent(..., priority = EventPriority.LOW)
```

### 2. 使用批量策略

对于埋点等非实时事件，使用批量策略减少处理次数

### 3. 避免在处理器中执行耗时操作

```kotlin
eventManager.registerHandler<MyEvent>("my_event") { event ->
    // 不好：阻塞处理
    Thread.sleep(1000)
    
    // 好：使用协程
    withContext(Dispatchers.IO) {
        performNetworkRequest()
    }
    EventResult.Success()
}
```

### 4. 及时清理资源

在Compose中使用`ObserveEvent`和`RegisterEventHandler`会自动清理，无需手动处理。
在传统View中记得在`onDestroy`中取消注册。

## 故障排查

### 问题1：事件没有被处理

**原因**：
- 没有注册对应的处理器
- 事件类型名称不匹配

**解决**：
```kotlin
// 确保事件类型名称匹配
eventManager.registerHandler<UIClickEvent>("ui_click") { ... }
dispatcher.dispatch(UIClickEvent(...))  // UIClickEvent的type.name是"ui_click"
```

### 问题2：防抖/节流不生效

**原因**：
- 策略配置错误
- 事件类型不一致

**解决**：
```kotlin
// 确保使用相同的事件类型
modifier = Modifier.clickableWithEvent(
    targetId = "button",
    debounceMs = 300  // 确认配置了延迟时间
)
```

### 问题3：内存泄漏

**原因**：
- 处理器没有取消注册
- 持有Activity/Context引用

**解决**：
```kotlin
// 在Compose中使用声明式API（自动管理）
@Composable
fun MyScreen() {
    ObserveEvent<UIClickEvent>("ui_click") { ... }  // 自动清理
}

// 在传统View中手动清理
override fun onDestroy() {
    eventManager.unregisterHandler("ui_click", handler)
    super.onDestroy()
}
```

## 更多资源

- [README.md](README.md) - 完整功能文档
- [QUICKSTART.md](QUICKSTART.md) - 5分钟快速入门
- [ARCHITECTURE.md](ARCHITECTURE.md) - 架构设计文档
- [Examples.kt](src/main/java/org/acdd/eventmanager/example/Examples.kt) - 完整示例代码

---

祝你使用愉快！如有问题，请查看文档或提交Issue。
