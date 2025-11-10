# Android 事件管理框架

一个基于 **Kotlin** 与 **Jetpack Compose** 的轻量事件管理工具模块，为业务提供统一的事件模型、智能队列调度、Compose 声明式绑定与简易日志体系。模块以工具形态存在，可独立集成到任意 Android 工程中。

## 核心能力

- **统一事件系统**：标准化事件域（UI、导航、埋点等）与类型，封装 `EventDescriptor`、`EventEnvelope` 等复用结构；支持自定义扩展与元数据。
- **智能队列管理**：
  - 调度策略：顺序、优先级、立即执行、批量、延时；
  - 内置去重（跳过/替换）、防抖、依赖管理、超时与重试；
  - 生命周期事件回调，便于监控。
- **Compose 深度集成**：提供 `rememberEventCenter`、`EventCollector`、`Modifier.emitOnClick` 等声明式 API，支持生命周期感知的事件监听与状态驱动的 UI 更新。
- **日志监控**：统一的 `EventLogger` 接口与默认控制台实现，方便接入第三方日志/监控平台。

## 快速开始

1. **在 settings.gradle 中引入模块**（示例）：

```kotlin
include(":event-framework")
project(":event-framework").projectDir = file("event-framework")
```

2. **Gradle 依赖**（Kotlin DSL 示例）：

```kotlin
dependencies {
    implementation(project(":event-framework"))
}
```

> 模块默认使用 `compileSdk = 34`、`minSdk = 24`、`jvmTarget = 17`，并启用 Compose。

3. **创建事件中心**：

```kotlin
val eventCenter = EventCenter.create(
    EventConfig(
        defaultPolicy = EventPolicy(),
        logger = ConsoleEventLogger.Default
    )
)
```

或在 Compose 中：

```kotlin
@Composable
fun HostScreen() {
    val eventCenter = rememberEventCenter()
    // ...
}
```

4. **注册处理器与发送事件**：

```kotlin
val subscription = eventCenter.registerHandler(
    name = "navigation_handler",
    priority = 10,
    filter = { it.descriptor.domain == EventDomain.NAVIGATION }
) { event ->
    navigateTo(event.payload as String)
    EventDispatchResult.Consumed
}

eventCenter.emit(
    name = "open_settings",
    domain = EventDomain.NAVIGATION,
    payload = "settings"
) {
    source("home_tab")
    tag("deeplink", "user_action")
}
```

## 队列策略示例

```kotlin
// 优先级队列
val highPriorityPolicy = EventPolicy(
    strategy = DispatchStrategy.Priority(),
    priority = EventPriority.CRITICAL
)

// 延时执行 + 重试
val delayedPolicy = EventPolicy(
    strategy = DispatchStrategy.Delayed(5.seconds),
    retryPolicy = RetryPolicy.Exponential(maxAttempts = 3, initialDelay = 1.seconds)
)

// 批量聚合
val batchPolicy = EventPolicy(
    strategy = DispatchStrategy.Batch(key = "analytics", maxItems = 10, maxWait = 2.seconds)
)
```

## Compose 绑定

```kotlin
@Composable
fun SubmitButton(eventCenter: EventCenter) {
    Button(
        modifier = Modifier.emitOnClick(
            eventCenter = eventCenter,
            name = "submit_form",
            domain = EventDomain.UI,
            payload = Unit
        ) {
            attribute("page", "register")
        }
    ) {
        Text("提交")
    }
}

@Composable
fun AnalyticsBinder(eventCenter: EventCenter) {
    EventCollector(eventCenter) { envelope ->
        // TODO: send to analytics
    }
}
```

## 生命周期与监控

- `eventCenter.lifecycle` 提供 `EventLifecycle` 流，可感知事件从入队、调度、完成、失败等状态。
- 日志可通过实现 `EventLogger` 接入 Logcat、埋点平台或监控系统。

```kotlin
class CrashlyticsLogger : EventLogger {
    override fun log(level: LogLevel, message: String, throwable: Throwable?, metadata: Map<String, Any?>) {
        // 上传到 Crashlytics
    }
}
```

## 下一步

- 根据业务需要补充自定义 `EventType`、装饰器或中间件；
- 与应用已有的导航、弹窗、埋点体系对接，实现统一事件入口；
- 结合 `EventLifecycleCollector` 实现可视化 Dashboard 或埋点监控。
