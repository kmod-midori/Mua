package moe.reimu.displaycontrol

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import androidx.core.graphics.scale
import kotlinx.serialization.decodeFromString
import moe.reimu.displaycontrol.DeviceToolCatalog.json
import moe.reimu.nekoassistant.IUserService
import java.io.ByteArrayOutputStream

/** Shared device execution and capture ownership. Call blocking operations off the UI thread. */
class DeviceToolExecutor(
    private val context: Context,
    private val onDisplayCountChanged: (Int) -> Unit = {},
    private val onFrameAvailable: () -> Unit = {},
) : AutoCloseable {
    private data class Capture(val frames: DisplayCapture, val width: Int, val height: Int, val dpi: Int)
    private val thread = HandlerThread("device-tool-frames").apply { start() }
    private val handler = Handler(thread.looper)
    private var remote: IUserService? = null
    private var closed = false
    // Capture resources only. App/task placement is always queried through the privileged system API.
    private val captures = linkedMapOf<Int, Capture>()

    @Synchronized
    fun connected(service: IUserService?) {
        if (closed) return
        if (remote?.asBinder() == service?.asBinder()) return
        clearCaptures()
        remote = service
    }

    private val dispatcher = DeviceToolDispatcher(
        handle<GetClipboard, String> { service().clipboardText },
        handle<SetClipboard, String> { service().setClipboardTextVerified(it.text); "Clipboard text set and verified" },
        handle<FocusedField, FocusedFieldResult> { AgentTextAccessibilityService.focusedField(it.displayId) },
        handle<StartDisplay, DisplayInfo>(displayOperation { service, _ -> startDisplay(service) }),
        handle<ListDisplays, List<DisplayInfo>>(displayOperation { service, _ -> listDisplays(service) }),
        handle<StopDisplay, String>(displayOperation { service, args ->
            check(service.releaseDisplay(args.displayId)) { "Failed to stop display ${args.displayId}" }
            removeCapture(args.displayId)
            "OK"
        }),
        handle<ListApps, List<AppInfo>> { listApps() },
        handle<ListRecentTasks, List<RecentTaskInfo>> {
            json.decodeFromString(service().recentTasksJson)
        },
        handle<MoveRecentTask, String>(displayOperation { service, args ->
            service.moveRecentTaskToDisplay(args.taskId, args.displayId)
            "Move requested for task ${args.taskId} to display ${args.displayId}"
        }),
        handle<LaunchApp, String>(displayOperation { service, args ->
            val intent = context.packageManager.getLaunchIntentForPackage(args.`package`)
                ?: error("Package has no launchable activity: ${args.`package`}")
            service.startActivityOnDisplay(args.displayId, intent)
            "Launch requested for ${args.`package`} on display ${args.displayId}"
        }),
        handle<FocusedApp, String>(displayOperation { service, args ->
            service.getFocusedPackageOnDisplay(args.displayId) ?: "No focused package"
        }),
        handle<Screenshot, DeviceToolResult.Image>(displayOperation { _, args -> screenshot(args.displayId) }),
        handle<Tap, String>(displayOperation { service, args ->
            check(service.injectTapOnDisplay(args.displayId, args.x, args.y)) { "Tap failed" }
            "OK"
        }),
        handle<Swipe, String>(displayOperation { service, args ->
            check(service.injectSwipeOnDisplay(args.displayId, args.x1, args.y1, args.x2, args.y2, args.durationMs)) { "Swipe failed" }
            "OK"
        }),
        handle<KeyPress, String>(displayOperation { service, args ->
            check(service.injectKeyOnDisplay(args.displayId, args.keyCode)) { "Key injection failed" }
            "OK"
        }),
        handle<ListNotifications, List<NotificationInfo>> { AgentNotificationListener.list() },
        handle<ClearNotification, String> { AgentNotificationListener.clear(it.key) },
        handle<ClickNotification, String> { AgentNotificationListener.click(it.key, it.displayId, service()) },
        handle<NotificationAction, String> {
            AgentNotificationListener.action(it.key, it.actionIndex, it.displayId, service())
        },
        handle<ReplyNotification, String> {
            AgentNotificationListener.action(it.key, it.actionIndex, it.displayId, service(), it.resultKey, it.text)
        },
        handle<SearchApps, List<AppInfo>> { searchApps(it.query) },
        handle<InputText, String> { args ->
            AgentTextAccessibilityService.setText(args.displayId, args.text)
            "Text replaced in the focused field"
        },
        handle<LongPress, String>(displayOperation { service, args ->
            check(service.injectSwipeOnDisplay(args.displayId, args.x, args.y, args.x, args.y, 950)) { "Long press failed" }
            "OK"
        }),
        handle<DoubleTap, String>(displayOperation { service, args ->
            check(service.injectTapOnDisplay(args.displayId, args.x, args.y)) { "First tap failed" }
            android.os.SystemClock.sleep(130)
            check(service.injectTapOnDisplay(args.displayId, args.x, args.y)) { "Second tap failed" }
            "OK"
        }),
    )

    init {
        check(dispatcher.names == DeviceToolCatalog.specs.map { it.name }.toSet()) { "Missing device-tool implementation" }
    }

    @Synchronized
    fun execute(name: String, arguments: String): DeviceToolResult {
        check(!closed) { "Device tools are closed" }
        return dispatcher.execute(name, arguments)
    }

    @Synchronized
    fun <R> execute(command: DeviceCommand<R>): R {
        check(!closed) { "Device tools are closed" }
        return dispatcher.execute(command)
    }

    private fun service(): IUserService = checkNotNull(remote) { "Shizuku is not connected. Grant permission and start Shizuku." }

    private fun <C, R> displayOperation(action: (IUserService, C) -> R): (C) -> R = { command ->
        val service = service()
        reconcile(service)
        action(service, command)
    }

    private inline fun <reified C : DeviceCommand<R>, R> handle(noinline action: (C) -> R) =
        DeviceToolDispatcher.Handler(DeviceToolCatalog.definition(C::class), action)

    private fun listApps(): List<AppInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, 0)
            .distinctBy { it.activityInfo.packageName }.sortedBy { it.activityInfo.packageName }
            .map { AppInfo(it.activityInfo.packageName, it.loadLabel(context.packageManager).toString()) }
    }

    private fun startDisplay(service: IUserService): DisplayInfo {
        val metrics = service.mainDisplayMetrics
        check(metrics.size == 3) { "Invalid main display metrics" }
        val (width, height, dpi) = metrics
        val next = DisplayCapture(width, height, handler, onFrameAvailable)
        var id = -1
        try {
            id = service.createDisplay(next.surface)
            check(id >= 0) { "Failed to create display" }
            captures[id] = Capture(next, width, height, dpi)
            onDisplayCountChanged(captures.size)
        } catch (e: Exception) {
            if (id >= 0) {
                try { service.releaseDisplay(id) } catch (_: Exception) { }
                captures.remove(id)
            }
            try { next.close() } finally { onDisplayCountChanged(captures.size) }
            throw e
        }
        return DisplayInfo(id, width, height, dpi)
    }

    /** Local capture metadata, without additional Binder calls after an operation succeeds. */
    @Synchronized
    fun managedDisplays(): List<DisplayInfo> = captures.map { (id, capture) ->
        DisplayInfo(id, capture.width, capture.height, capture.dpi)
    }

    private fun listDisplays(service: IUserService): List<DisplayInfo> = managedDisplays().map {
        it.copy(focusedPackage = service.getFocusedPackageOnDisplay(it.displayId))
    }

    private fun screenshot(id: Int): DeviceToolResult.Image {
        val capture = checkNotNull(captures[id]) { "No capture available for display $id" }
        var bitmap = checkNotNull(capture.frames.bitmap()) { "No frame yet; retry screenshot shortly" }
        val bytes = try {
            val longest = maxOf(bitmap.width, bitmap.height)
            if (longest > 1980) {
                val scaled = bitmap.scale(
                    (bitmap.width.toLong() * 1980 / longest).toInt().coerceAtLeast(1),
                    (bitmap.height.toLong() * 1980 / longest).toInt().coerceAtLeast(1), true,
                )
                bitmap.recycle()
                bitmap = scaled
            }
            ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                out.toByteArray()
            }
        } finally { bitmap.recycle() }
        return DeviceToolResult.Image(bytes, "image/png")
    }

    @Synchronized
    fun refreshDisplays() { remote?.let(::reconcile) }

    @Synchronized
    fun previews(): List<DisplayPreview> {
        val service = remote ?: return emptyList()
        reconcile(service)
        return captures.map { (id, capture) ->
            val bitmap = capture.frames.previewBitmap(480)
            DisplayPreview(id, capture.width, capture.height, bitmap)
        }
    }

    @Synchronized
    fun bitmap(displayId: Int): Bitmap? = captures[displayId]?.frames?.bitmap()

    private fun searchApps(query: String): List<AppInfo> {
        val installed = context.packageManager.getInstalledApplications(0).map { app ->
            AppInfo(app.packageName, context.packageManager.getApplicationLabel(app).toString())
        }
        // Some OEMs filter the installed-app query more aggressively than launcher queries.
        return (listApps() + installed).distinctBy { it.`package` }.filter {
            it.label.contains(query, ignoreCase = true) || it.`package`.contains(query, ignoreCase = true)
        }
    }

    private fun reconcile(service: IUserService) {
        val ids = service.displayIds.toSet()
        captures.keys.filter { it !in ids }.forEach(::removeCapture)
    }

    private fun removeCapture(id: Int) {
        try { captures.remove(id)?.frames?.close() }
        catch (e: Exception) { android.util.Log.w("DeviceTools", "Could not close capture for display $id", e) }
        finally { onDisplayCountChanged(captures.size) }
    }

    private fun clearCaptures() {
        try { captures.keys.toList().forEach(::removeCapture) }
        finally { onDisplayCountChanged(captures.size) }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            captures.keys.toList().forEach { id ->
                try { remote?.releaseDisplay(id) }
                catch (e: Exception) { android.util.Log.w("DeviceTools", "Could not release display $id", e) }
                finally { removeCapture(id) }
            }
        } finally {
            try { clearCaptures() }
            finally { remote = null; thread.quitSafely() }
        }
    }
}
