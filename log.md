# 变更记录

## 2025-11-10
- 新增 `event-framework` 模块，提供 Kotlin/Compose 事件管理框架所需的 Gradle 配置与空 consumer proguard 文件。
- 实现核心代码：`EventModels.kt`、`EventConfig.kt`、`EventLogging.kt`、`EventLifecycle.kt`、`EventCenter.kt`、`EventQueueManager.kt`，涵盖统一事件模型、智能队列、日志与生命周期跟踪。
- 增加 Compose 集成支持 `ComposeIntegration.kt`，实现声明式事件绑定与生命周期感知收集能力。
- 编写模块级文档 `event-framework/README.md`，说明能力、用法与集成示例。
