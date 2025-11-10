package org.acdd.eventmanager.core

/**
 * 预定义的事件类型
 */

// ============ UI交互事件 ============
sealed class UIEventType(override val name: String) : EventType {
    override val category = EventCategory.UI
    
    object Click : UIEventType("ui_click")
    object LongClick : UIEventType("ui_long_click")
    object DoubleClick : UIEventType("ui_double_click")
    object Swipe : UIEventType("ui_swipe")
    object Scroll : UIEventType("ui_scroll")
    object Input : UIEventType("ui_input")
    object Focus : UIEventType("ui_focus")
    object Blur : UIEventType("ui_blur")
}

data class UIClickEvent(
    val targetId: String,
    val targetName: String? = null,
    val x: Float? = null,
    val y: Float? = null,
    override val priority: EventPriority = EventPriority.NORMAL
) : BaseEvent(UIEventType.Click, priority)

data class UILongClickEvent(
    val targetId: String,
    val duration: Long,
    override val priority: EventPriority = EventPriority.NORMAL
) : BaseEvent(UIEventType.LongClick, priority)

data class UISwipeEvent(
    val direction: SwipeDirection,
    val distance: Float,
    override val priority: EventPriority = EventPriority.NORMAL
) : BaseEvent(UIEventType.Swipe, priority)

enum class SwipeDirection {
    LEFT, RIGHT, UP, DOWN
}

// ============ 业务事件 ============
sealed class BusinessEventType(override val name: String) : EventType {
    override val category = EventCategory.BUSINESS
    
    object Dialog : BusinessEventType("business_dialog")
    object Toast : BusinessEventType("business_toast")
    object Loading : BusinessEventType("business_loading")
    object DataRefresh : BusinessEventType("business_data_refresh")
    object StateChange : BusinessEventType("business_state_change")
}

data class DialogEvent(
    val dialogId: String,
    val title: String? = null,
    val message: String? = null,
    val action: DialogAction = DialogAction.SHOW,
    override val priority: EventPriority = EventPriority.HIGH
) : BaseEvent(BusinessEventType.Dialog, priority)

enum class DialogAction {
    SHOW, DISMISS, CONFIRM, CANCEL
}

data class ToastEvent(
    val message: String,
    val duration: ToastDuration = ToastDuration.SHORT,
    override val priority: EventPriority = EventPriority.NORMAL
) : BaseEvent(BusinessEventType.Toast, priority)

enum class ToastDuration {
    SHORT, LONG
}

data class LoadingEvent(
    val isLoading: Boolean,
    val message: String? = null,
    override val priority: EventPriority = EventPriority.HIGH
) : BaseEvent(BusinessEventType.Loading, priority)

// ============ 导航事件 ============
sealed class NavigationEventType(override val name: String) : EventType {
    override val category = EventCategory.NAVIGATION
    
    object Navigate : NavigationEventType("nav_navigate")
    object Back : NavigationEventType("nav_back")
    object Replace : NavigationEventType("nav_replace")
    object DeepLink : NavigationEventType("nav_deeplink")
}

data class NavigateEvent(
    val route: String,
    val params: Map<String, Any> = emptyMap(),
    val clearBackStack: Boolean = false,
    override val priority: EventPriority = EventPriority.HIGH
) : BaseEvent(NavigationEventType.Navigate, priority)

data class BackEvent(
    val result: Any? = null,
    override val priority: EventPriority = EventPriority.HIGH
) : BaseEvent(NavigationEventType.Back, priority)

// ============ 埋点事件 ============
sealed class AnalyticsEventType(override val name: String) : EventType {
    override val category = EventCategory.ANALYTICS
    
    object PageView : AnalyticsEventType("analytics_page_view")
    object UserAction : AnalyticsEventType("analytics_user_action")
    object CustomEvent : AnalyticsEventType("analytics_custom")
}

data class PageViewEvent(
    val pageName: String,
    val pageParams: Map<String, Any> = emptyMap(),
    override val priority: EventPriority = EventPriority.LOW
) : BaseEvent(AnalyticsEventType.PageView, priority)

data class UserActionEvent(
    val actionName: String,
    val actionParams: Map<String, Any> = emptyMap(),
    override val priority: EventPriority = EventPriority.LOW
) : BaseEvent(AnalyticsEventType.UserAction, priority)

// ============ 自定义事件 ============
data class CustomEvent(
    val eventName: String,
    val eventData: Map<String, Any> = emptyMap(),
    override val priority: EventPriority = EventPriority.NORMAL
) : BaseEvent(
    object : EventType {
        override val name = eventName
        override val category = EventCategory.CUSTOM
    },
    priority
)
