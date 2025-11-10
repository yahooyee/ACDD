package org.acdd.eventmanager.core

import java.util.UUID

/**
 * 事件基类 - 所有事件的基础接口
 */
interface Event {
    /** 事件唯一标识 */
    val id: String
    
    /** 事件类型 */
    val type: EventType
    
    /** 事件创建时间戳 */
    val timestamp: Long
    
    /** 事件优先级 */
    val priority: EventPriority
    
    /** 事件元数据 */
    val metadata: Map<String, Any>
}

/**
 * 抽象事件基类 - 提供默认实现
 */
abstract class BaseEvent(
    override val type: EventType,
    override val priority: EventPriority = EventPriority.NORMAL,
    override val metadata: Map<String, Any> = emptyMap()
) : Event {
    override val id: String = UUID.randomUUID().toString()
    override val timestamp: Long = System.currentTimeMillis()
}

/**
 * 事件优先级
 */
enum class EventPriority(val value: Int) {
    LOW(1),
    NORMAL(5),
    HIGH(10),
    CRITICAL(20)
}

/**
 * 事件类型基础接口
 */
interface EventType {
    val name: String
    val category: EventCategory
}

/**
 * 事件分类
 */
enum class EventCategory {
    UI,          // UI交互事件
    BUSINESS,    // 业务事件
    NAVIGATION,  // 导航事件
    ANALYTICS,   // 埋点事件
    SYSTEM,      // 系统事件
    CUSTOM       // 自定义事件
}
