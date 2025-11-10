## 事件管理框架（Event Framework）

一个面向 Compose 的 Kotlin 事件管理工具模块，提供统一的业务事件收拢、智能队列调度、生命周期感知以及日志观测能力。可作为独立工具依赖给其他模块使用。

### 核心能力
- **统一事件模型**：通过 `AppEvent` 及 DSL 描述弹窗、导航、埋点、系统等常见业务事件，支持扩展自定义类型与元信息。
- **智能队列系统**：封装顺序、优先级、立即、批量、延迟五种策略，内建去重、防抖、依赖关系判断、超时重试和退避策略。
- **Compose 深度集成**：提供 `rememberEventDispatcher`、`EventEffect`、`rememberEventState` 等声明式 API，使用状态驱动 UI 更新，并自动感知生命周期。
- **日志与观测**：`InMemoryEventLogStore` 配合 `CompositeEventLogger` 实现内存日志池，便于调试面板展示；亦可接入自定义日志。

### 目录结构
```
eventframework/
 ├── build.gradle                 // 模块构建配置（需配合现代 AGP/Kotlin/Compose 环境）
 ├── src/main/
 │   ├── AndroidManifest.xml
 │   ├── kotlin/com/acdd/eventframework/
 │   │   ├── core/                // 事件模型、调度中心、DSL
 │   │   ├── queue/               // 队列与调度策略实现
 │   │   ├── compose/             // Compose 集成 API
 │   │   ├── logging/             // 日志接口与内存实现
 │   │   └── samples/             // 使用示例
 └── README.md
```

### 快速开始
1. **初始化**
   ```kotlin
   val system = EventSystem.create(scope = viewModelScope)
   val dispatcher = system.dispatcher
   ```

2. **注册事件处理器**
   ```kotlin
   dispatcher.register(AppEvent.Navigation::class) { event, scope ->
       router.navigate(event.destination)
       EventResult.Success(
           key = event.key,
           startedAt = event.metadata.createdAt,
           finishedAt = Clock.System.now()
       )
   }
   ```

3. **派发事件**
   ```kotlin
   val context = EventContext(module = "home", screen = "dashboard")
   val event = EventDSL.ui(
       context = context,
       interaction = UiInteraction.Click,
       payload = Unit,
       strategy = DispatchStrategy.IMMEDIATE,
       dedup = DedupConfig(debounce = 300.milliseconds)
   )
   dispatcher.dispatch(event)
   ```

4. **Compose 中使用**
   ```kotlin
   @Composable
   fun Sample() {
       val dispatcher = rememberEventDispatcher()
       EventEffect(dispatcher, key = "welcome") {
           EventDSL.ui(EventContext("home"), UiInteraction.Click)
       }
   }
   ```

### 队列策略说明
| 策略             | 说明 |
|------------------|------|
| `SEQUENTIAL`     | 默认顺序执行，保证先进先出 |
| `PRIORITY`       | 基于 `EventPriority` 权重的优先级队列 |
| `IMMEDIATE`      | 插队执行，常用于关键 UI 反馈 |
| `BATCH`          | 按 `batchKey` 聚合，支持延时自动 flush |
| `DELAYED`        | 结合 `delayBy` 在指定时间后入队 |

- 去重范围通过 `DedupScope` 控制（全局/模块/上下文）。
- 防抖在队列入口实现，避免短时间内重复触发。
- 依赖关系可声明为必须成功或仅等待完成，超时会自动产生 `Timeout` 结果。
- 重试支持线性/指数退避策略。

### 日志与调试
```kotlin
val system = EventSystem.create(
    scope = viewModelScope,
    baseLogger = SimpleEventLogger(),
    enableInMemoryLog = true
)
system.logStore.logs.collect { entries ->
    // 显示在调试面板
}
```

### 注意事项
- 本模块需搭配 **AGP 8.x+ / Kotlin 1.9+ / Compose Compiler 1.5+** 的构建环境；若当前工程仍使用旧版 Gradle，请先升级或单独以 Maven/Gradle 子项目集成。
- 事件处理器应返回 `EventResult`，便于队列决策重试或依赖。
- 调度使用 `CoroutineScope`，建议基于 `viewModelScope` 或自定义 `SupervisorJob`。

### 后续扩展建议
- 根据业务需要扩展更多策略（如并发窗口、速率限制）。
- 编写调试 UI 组件展示 `InMemoryEventLogStore` 和队列快照。
- 在导航事件中对接 Android Navigation 或自定义路由。

欢迎按需裁剪或扩展，框架力求在保持简单的同时覆盖主流事件场景。
