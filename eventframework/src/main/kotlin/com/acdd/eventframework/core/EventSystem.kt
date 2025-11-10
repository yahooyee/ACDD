package com.acdd.eventframework.core

import com.acdd.eventframework.logging.CompositeEventLogger
import com.acdd.eventframework.logging.EventLogger
import com.acdd.eventframework.logging.InMemoryEventLogStore
import kotlinx.coroutines.CoroutineScope

/**
 * 封装常用初始化逻辑，方便模块直接接入。
 */
class EventSystem private constructor(
    val dispatcher: EventDispatcher,
    val logStore: InMemoryEventLogStore,
    val logger: EventLogger
) {

    companion object {
        fun create(
            scope: CoroutineScope,
            baseLogger: EventLogger = EventLogger.None,
            enableInMemoryLog: Boolean = true
        ): EventSystem {
            val logStore = InMemoryEventLogStore()
            val logger = if (enableInMemoryLog) {
                CompositeEventLogger(logStore, baseLogger)
            } else {
                baseLogger
            }
            val dispatcher = EventDispatcher(scope, logger = logger)
            return EventSystem(dispatcher, logStore, logger)
        }
    }
}
