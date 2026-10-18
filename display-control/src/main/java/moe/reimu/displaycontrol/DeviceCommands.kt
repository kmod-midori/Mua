package moe.reimu.displaycontrol

import kotlinx.serialization.Serializable

/** Typed device operations, independent of MCP and agent frameworks. */
sealed interface DeviceCommand<R>
interface DisplayTarget { val displayId: Int }

@Serializable data object StartDisplay : DeviceCommand<DisplayInfo>
@Serializable data object ListDisplays : DeviceCommand<List<DisplayInfo>>
@Serializable data class StopDisplay(override val displayId: Int) : DeviceCommand<String>, DisplayTarget
@Serializable data object ListApps : DeviceCommand<List<AppInfo>>
@Serializable data object ListRecentTasks : DeviceCommand<List<RecentTaskInfo>>
@Serializable data class MoveRecentTask(val taskId: Int, override val displayId: Int) : DeviceCommand<String>, DisplayTarget
@Serializable data class LaunchApp(override val displayId: Int, val `package`: String) : DeviceCommand<String>, DisplayTarget
@Serializable data class FocusedApp(override val displayId: Int) : DeviceCommand<String>, DisplayTarget
@Serializable data class Screenshot(override val displayId: Int) : DeviceCommand<DeviceToolResult.Image>, DisplayTarget
@Serializable data class Tap(override val displayId: Int, val x: Float, val y: Float) : DeviceCommand<String>, DisplayTarget
@Serializable data class Swipe(override val displayId: Int, val x1: Float, val y1: Float, val x2: Float, val y2: Float, val durationMs: Long) : DeviceCommand<String>, DisplayTarget
@Serializable data class KeyPress(override val displayId: Int, val keyCode: Int) : DeviceCommand<String>, DisplayTarget
@Serializable data object ListNotifications : DeviceCommand<List<NotificationInfo>>
@Serializable data class ClearNotification(val key: String) : DeviceCommand<String>
@Serializable data class ClickNotification(val key: String, override val displayId: Int) : DeviceCommand<String>, DisplayTarget
@Serializable data class NotificationAction(val key: String, val actionIndex: Int, override val displayId: Int) : DeviceCommand<String>, DisplayTarget
@Serializable data class ReplyNotification(val key: String, val actionIndex: Int, override val displayId: Int, val resultKey: String, val text: String) : DeviceCommand<String>, DisplayTarget

@Serializable data class SearchApps(val query: String) : DeviceCommand<List<AppInfo>>
@Serializable data class InputText(override val displayId: Int, val text: String) : DeviceCommand<String>, DisplayTarget
@Serializable data class LongPress(override val displayId: Int, val x: Float, val y: Float) : DeviceCommand<String>, DisplayTarget
@Serializable data class DoubleTap(override val displayId: Int, val x: Float, val y: Float) : DeviceCommand<String>, DisplayTarget

@Serializable data class DisplayInfo(val displayId: Int, val width: Int, val height: Int, val dpi: Int, val focusedPackage: String? = null)
@Serializable data class AppInfo(val `package`: String, val label: String)
@Serializable data class RecentTaskInfo(
    val taskId: Int,
    val title: String?,
    val displayId: Int,
    val isRunning: Boolean,
    val `package`: String?,
    val baseActivity: String?,
    val topActivity: String?,
)
@Serializable data class NotificationInfo(
    val key: String, val `package`: String, val id: Int, val tag: String?, val userId: Int,
    val postTime: Long, val groupKey: String, val ongoing: Boolean, val clearable: Boolean,
    val category: String?, val title: String?, val text: String?, val bigText: String?,
    val textLines: List<String>, val contentIntent: NotificationIntentInfo?, val actions: List<NotificationActionInfo>,
)
@Serializable data class NotificationIntentInfo(val isActivity: Boolean, val creatorPackage: String?, val immutable: Boolean)
@Serializable data class NotificationActionInfo(val index: Int, val title: String, val intent: NotificationIntentInfo?, val remoteInputs: List<NotificationInputInfo>)
@Serializable data class NotificationInputInfo(val resultKey: String, val label: String, val allowFreeFormInput: Boolean, val choices: List<String>)
