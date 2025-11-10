package org.acdd.eventmanager.utils

import org.acdd.eventmanager.core.Event
import org.acdd.eventmanager.core.EventType

/**
 * 事件工具类
 */
object EventUtils {
    
    /**
     * 生成事件签名用于去重
     */
    fun generateEventSignature(event: Event): String {
        return "${event.type.name}_${event.javaClass.simpleName}_${event.hashCode()}"
    }
    
    /**
     * 检查两个事件是否相同类型
     */
    fun isSameType(event1: Event, event2: Event): Boolean {
        return event1.type.name == event2.type.name &&
               event1.type.category == event2.type.category
    }
    
    /**
     * 比较事件优先级
     */
    fun comparePriority(event1: Event, event2: Event): Int {
        return event2.priority.value.compareTo(event1.priority.value)
    }
}

/**
 * Event扩展函数
 */

/**
 * 转换为Map
 */
fun Event.toMap(): Map<String, Any> {
    return mapOf(
        "id" to id,
        "type" to type.name,
        "category" to type.category.name,
        "priority" to priority.name,
        "timestamp" to timestamp,
        "metadata" to metadata
    )
}

/**
 * 获取事件年龄（毫秒）
 */
fun Event.getAge(): Long {
    return System.currentTimeMillis() - timestamp
}

/**
 * 检查事件是否过期
 */
fun Event.isExpired(maxAgeMs: Long = 60000): Boolean {
    return getAge() > maxAgeMs
}

/**
 * EventType扩展函数
 */

/**
 * 创建自定义事件类型
 */
fun createEventType(name: String, category: org.acdd.eventmanager.core.EventCategory): EventType {
    return object : EventType {
        override val name = name
        override val category = category
    }
}
