# EventManager - Android事件管理框架

一个基于Kotlin和Jetpack Compose的完整Android事件管理框架，支持统一事件管理、智能队列调度和深度Compose集成。

## 特性

### 🎯 核心功能

- **统一事件系统**：管理所有业务事件类型（弹窗、导航、埋点等），支持扩展
- **完整UI交互事件**：支持点击、长按、双击、滑动等常用UI交互
- **智能队列管理**：多策略队列系统（顺序、优先级、立即、批量、延迟）
- **事件去重与防抖**：自动去重、防抖、节流机制
- **依赖关系管理**：支持事件依赖和执行顺序控制
- **超时重试机制**：自动重试失败事件，支持指数退避
- **深度Compose集成**：声明式API、状态驱动、生命周期感知
- **完善日志系统**：分级日志、可扩展日志输出

## 快速开始

### 1. 添加依赖

在项目的 `settings.gradle` 中添加：

```groovy
include ':EventManager'
```

在模块的 `build.gradle` 中添加：

```groovy
dependencies {
    implementation project(':EventManager')
}
```

### 2. 初始化

```kotlin
// 使用默认配置
val eventManager = EventManager.getInstance()

// 或使用DSL配置
val eventManager = eventManager {
    queueConfig {
        maxSize(2000)
        enableDeduplication(true)
        enableRetry(true)
        maxRetryCount(3)
        timeoutMs(30000)
    }
    logger(DefaultEventLogger(LogLevel.DEBUG))
}
```

### 3. 基础使用

#### 发送事件

```kotlin
// 发送UI点击事件
eventManager.dispatch(
    UIClickEvent(
        targetId = "button_submit",
        targetName = "提交按钮",
        priority = EventPriority.NORMAL
    )
)

// 发送导航事件
eventManager.dispatch(
    NavigateEvent(
        route = "/detail",
        params = mapOf("id" to "123")
    )
)

// 发送自定义事件
eventManager.dispatch(
    CustomEvent(
        eventName = "user_login",
        eventData = mapOf("userId" to "user123")
    )
)
```

#### 注册事件处理器

```kotlin
// 注册处理器
eventManager.registerHandler<UIClickEvent>("ui_click") { event ->
    println("按钮被点击: ${event.targetId}")
    // 处理点击逻辑
    EventResult.Success()
}

// 使用简单监听器
eventManager.registerListener<NavigateEvent>("nav_navigate") { event ->
    println("导航到: ${event.route}")
    // 执行导航
}
```

## Compose集成

### 1. 提供EventManager

```kotlin
@Composable
fun App() {
    val eventManager = remember { EventManager.getInstance() }
    
    CompositionLocalProvider(LocalEventManager provides eventManager) {
        MainScreen()
    }
}
```

### 2. 使用声明式API

#### 监听事件

```kotlin
@Composable
fun MyScreen() {
    // 观察事件
    ObserveEvent<DialogEvent>("business_dialog") { event ->
        when (event.action) {
            DialogAction.SHOW -> showDialog(event.message)
            DialogAction.DISMISS -> dismissDialog()
        }
    }
    
    // 收集事件为State
    val latestClick by collectEventAsState<UIClickEvent>("ui_click")
    
    Text("最后点击: ${latestClick?.targetName}")
}
```

#### 注册处理器

```kotlin
@Composable
fun MyScreen() {
    RegisterEventHandler<UIClickEvent>("ui_click") { event ->
        // 自动在组件销毁时取消注册
        handleClick(event)
        EventResult.Success()
    }
}
```

#### 发送事件

```kotlin
@Composable
fun MyButton() {
    val dispatcher = rememberEventDispatcher()
    
    Button(
        onClick = {
            dispatcher.dispatch(
                UIClickEvent("my_button")
            )
        }
    ) {
        Text("点击我")
    }
}
```

### 3. 使用事件修饰符

```kotlin
@Composable
fun InteractiveButton() {
    Box(
        modifier = Modifier
            // 带防抖的点击
            .clickableWithEvent(
                targetId = "button_1",
                targetName = "提交按钮",
                debounceMs = 300
            )
            // 或带节流的点击
            .clickableWithEvent(
                targetId = "button_2",
                throttleMs = 1000
            )
            // 长按事件
            .longClickableWithEvent(
                targetId = "button_3",
                onLongClick = { showMenu() }
            )
            // 双击事件
            .doubleClickableWithEvent(
                targetId = "button_4",
                onDoubleClick = { zoom() }
            )
            // 滑动事件
            .swipeableWithEvent(
                targetId = "card_1",
                onSwipe = { direction ->
                    when (direction) {
                        SwipeDirection.LEFT -> deleteItem()
                        SwipeDirection.RIGHT -> markRead()
                    }
                }
            )
    ) {
        Text("交互按钮")
    }
}
```

## 高级功能

### 1. 执行策略

```kotlin
import org.acdd.eventmanager.strategy.*

// 立即执行
eventManager.dispatch(event, ImmediateStrategy)

// 顺序执行（队列）
eventManager.dispatch(event, SequentialStrategy)

// 优先级执行
eventManager.dispatch(event, PriorityStrategy)

// 延迟执行
eventManager.dispatch(event, DelayStrategy(delayMs = 2000))

// 批量执行
eventManager.dispatch(event, BatchStrategy(batchSize = 10, timeWindowMs = 1000))

// 防抖
eventManager.dispatch(event, DebounceStrategy(delayMs = 300))

// 节流
eventManager.dispatch(event, ThrottleStrategy(intervalMs = 1000))
```

### 2. 事件依赖

```kotlin
// 事件A
val eventA = CustomEvent("task_a")
eventManager.dispatch(eventA)

// 事件B依赖A完成后执行
val eventB = CustomEvent("task_b")
eventManager.dispatch(
    event = eventB,
    dependsOn = listOf(eventA.id)
)
```

### 3. 自定义事件处理器

```kotlin
// 带过滤的处理器
val handler = EventHandler<UIClickEvent> { event ->
    if (event.targetId.startsWith("btn_")) {
        // 处理按钮点击
        EventResult.Success()
    } else {
        EventResult.Skipped
    }
}.filter { it.priority == EventPriority.HIGH }
 .retry(maxRetries = 3)
 .timeout(5000)

eventManager.registerHandler("ui_click", handler)
```

### 4. 队列状态监控

```kotlin
@Composable
fun QueueMonitor() {
    val queueState by rememberQueueState()
    
    Column {
        Text("队列大小: ${queueState.size}")
        Text("已处理: ${queueState.processedCount}")
        Text("待处理依赖: ${queueState.pendingDependencies}")
    }
}
```

## 预定义事件类型

### UI交互事件

- `UIClickEvent` - 点击事件
- `UILongClickEvent` - 长按事件
- `UISwipeEvent` - 滑动事件
- `UIScrollEvent` - 滚动事件
- `UIInputEvent` - 输入事件

### 业务事件

- `DialogEvent` - 弹窗事件
- `ToastEvent` - Toast提示
- `LoadingEvent` - 加载状态
- `DataRefreshEvent` - 数据刷新
- `StateChangeEvent` - 状态变更

### 导航事件

- `NavigateEvent` - 导航跳转
- `BackEvent` - 返回事件
- `ReplaceEvent` - 替换页面
- `DeepLinkEvent` - 深度链接

### 埋点事件

- `PageViewEvent` - 页面浏览
- `UserActionEvent` - 用户行为
- `CustomAnalyticsEvent` - 自定义埋点

## 最佳实践

### 1. 事件命名规范

```kotlin
// 使用清晰的命名
UIClickEvent(targetId = "home_tab_1")  // 好
UIClickEvent(targetId = "btn1")        // 不好

// 使用有意义的事件名
CustomEvent("user_purchase_completed") // 好
CustomEvent("event1")                   // 不好
```

### 2. 合理使用优先级

```kotlin
// 关键业务使用HIGH或CRITICAL
DialogEvent(action = DialogAction.SHOW, priority = EventPriority.HIGH)

// 埋点使用LOW
PageViewEvent(pageName = "home", priority = EventPriority.LOW)

// 普通交互使用NORMAL
UIClickEvent(targetId = "like_button", priority = EventPriority.NORMAL)
```

### 3. 生命周期管理

```kotlin
@Composable
fun MyScreen() {
    // 使用ObserveEvent和RegisterEventHandler
    // 它们会自动在组件销毁时清理资源
    
    ObserveEvent<UIClickEvent>("ui_click") { event ->
        // 自动管理生命周期
    }
}

// 在非Compose环境中，记得手动清理
class MyActivity : AppCompatActivity() {
    private val handler = EventHandler<UIClickEvent> { ... }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        eventManager.registerHandler("ui_click", handler)
    }
    
    override fun onDestroy() {
        eventManager.unregisterHandler("ui_click", handler)
        super.onDestroy()
    }
}
```

### 4. 错误处理

```kotlin
eventManager.registerHandler<CustomEvent>("my_event") { event ->
    try {
        // 执行可能失败的操作
        performTask()
        EventResult.Success()
    } catch (e: Exception) {
        // 返回失败结果，触发重试机制
        EventResult.Failure(e, "任务执行失败")
    }
}
```

## API参考

详细API文档请参考各个类的KDoc注释。

## 许可证

MIT License - 参见 LICENSE 文件

## 贡献

欢迎提交Issue和Pull Request！
