package com.acdd.eventframework.queue

import com.acdd.eventframework.core.AppEvent
import com.acdd.eventframework.core.EventKey
import com.acdd.eventframework.core.EventStatus

data class EventStatusUpdate(
    val key: EventKey,
    val status: EventStatus,
    val event: AppEvent<*>,
    val attempt: Int,
    val message: String? = null
)
