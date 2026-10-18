package moe.reimu.mua

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import androidx.core.graphics.scale
import kotlinx.serialization.json.*
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import moe.reimu.displaycontrol.AgentNotificationListener
import moe.reimu.displaycontrol.DisplayCapture
import moe.reimu.nekoassistant.IUserService
import java.io.ByteArrayOutputStream

/** All methods run on McpService's single control executor, outside the UI and Binder threads. */
internal class DisplayTools(
    private val context: Context,
    private val onDisplayCountChanged: (Int) -> Unit,
) : AutoCloseable {
    private data class Capture(val frames: DisplayCapture, val width: Int, val height: Int, val dpi: Int)
    private val thread = HandlerThread("mua-frames").apply { start() }
    private val handler = Handler(thread.looper)
    private var remote: IUserService? = null
    // Capture resources only. App/task placement is always queried through the privileged system API.
    private val captures = linkedMapOf<Int, Capture>()

    fun connected(service: IUserService?) {
        if (remote?.asBinder() == service?.asBinder()) return
        clearCaptures()
        remote = service
    }

    fun execute(name: String, args: JsonObject): CallToolResult {
        if (name == "notifications_list") return textContent(AgentNotificationListener.list())
        if (name == "notification_clear") return textContent(AgentNotificationListener.clear(args.getValue("key").jsonPrimitive.content))
        when (name) {
            "notification_click", "notification_action", "notification_reply" -> {
                val service = checkNotNull(remote) { "Shizuku is not connected" }
                val key = args.getValue("key").jsonPrimitive.content
                val displayId = args.getValue("displayId").jsonPrimitive.int
                val result = when (name) {
                    "notification_click" -> AgentNotificationListener.click(key, displayId, service)
                    "notification_reply" -> AgentNotificationListener.action(
                        key, args.getValue("actionIndex").jsonPrimitive.int, displayId, service,
                        args.getValue("resultKey").jsonPrimitive.content, args.getValue("text").jsonPrimitive.content,
                    )
                    else -> AgentNotificationListener.action(
                        key, args.getValue("actionIndex").jsonPrimitive.int, displayId, service,
                    )
                }
                return textContent(result)
            }
        }
        if (name == "apps_list") {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = context.packageManager.queryIntentActivities(intent, 0)
                .distinctBy { it.activityInfo.packageName }.sortedBy { it.activityInfo.packageName }
            return textContent(buildJsonArray {
                apps.forEach { info -> add(buildJsonObject {
                    put("package", info.activityInfo.packageName)
                    put("label", info.loadLabel(context.packageManager).toString())
                }) }
            }.toString())
        }
        val service = checkNotNull(remote) { "Shizuku is not connected. Grant permission in Mua and start Shizuku." }
        if (name == "recent_tasks_list") return textContent(service.recentTasksJson)
        reconcile(service)
        fun int(key: String) = args.getValue(key).jsonPrimitive.int
        if (name == "display_start") {
            val metrics = service.mainDisplayMetrics
            check(metrics.size == 3) { "Invalid main display metrics" }
            val w = metrics[0]; val h = metrics[1]; val dpi = metrics[2]
            val next = DisplayCapture(w, h, handler)
            var id = -1
            try {
                id = service.createDisplay(next.surface)
                check(id >= 0) { "Failed to create display" }
                captures[id] = Capture(next, w, h, dpi)
                onDisplayCountChanged(captures.size)
            } catch (e: Exception) {
                if (id >= 0) {
                    try { service.releaseDisplay(id) } catch (_: Exception) { }
                    captures.remove(id)
                }
                try { next.close() } finally { onDisplayCountChanged(captures.size) }
                throw e
            }
            return textContent(buildJsonObject { put("displayId", id); put("width", w); put("height", h); put("dpi", dpi) }.toString())
        }
        if (name == "displays_list") {
            return textContent(buildJsonArray {
                captures.forEach { (id, capture) -> add(buildJsonObject {
                    put("displayId", id); put("width", capture.width); put("height", capture.height); put("dpi", capture.dpi)
                    put("focusedPackage", service.getFocusedPackageOnDisplay(id)?.let { JsonPrimitive(it) } ?: JsonNull)
                }) }
            }.toString())
        }
        val id = int("displayId")
        fun coordinate(key: String) = args.getValue(key).jsonPrimitive.float
        when (name) {
            "recent_task_move" -> {
                val taskId = int("taskId")
                service.moveRecentTaskToDisplay(taskId, id)
                return textContent("Move requested for task $taskId to display $id")
            }
            "display_stop" -> {
                check(service.releaseDisplay(id)) { "Failed to stop display $id" }
                removeCapture(id)
            }
            "screenshot" -> {
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
                return CallToolResult(content = listOf(ImageContent(data = Base64.encodeToString(bytes, Base64.NO_WRAP), mimeType = "image/png")))
            }
            "app_launch" -> {
                val pkg = args.getValue("package").jsonPrimitive.content
                val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                    ?: error("Package has no launchable activity: $pkg")
                service.startActivityOnDisplay(id, intent)
                return textContent("Launch requested for $pkg on display $id")
            }
            "focused_app" -> return textContent(service.getFocusedPackageOnDisplay(id) ?: "No focused package")
            "tap" -> check(service.injectTapOnDisplay(id, coordinate("x"), coordinate("y"))) { "Tap failed" }
            "swipe" -> check(service.injectSwipeOnDisplay(id, coordinate("x1"), coordinate("y1"),
                coordinate("x2"), coordinate("y2"), args.getValue("durationMs").jsonPrimitive.long)) { "Swipe failed" }
            "key_press" -> check(service.injectKeyOnDisplay(id, int("keyCode"))) { "Key injection failed" }
            else -> error("Unknown tool")
        }
        return textContent("OK")
    }

    fun refreshDisplays() { remote?.let(::reconcile) }

    fun previews(): List<DisplayPreview> {
        val service = remote ?: return emptyList()
        reconcile(service)
        return captures.map { (id, capture) ->
            val bitmap = capture.frames.previewBitmap(480)
            DisplayPreview(id, capture.width, capture.height, bitmap)
        }
    }

    private fun reconcile(service: IUserService) {
        val ids = service.displayIds.toSet()
        captures.keys.filter { it !in ids }.forEach(::removeCapture)
    }

    private fun removeCapture(id: Int) {
        try { captures.remove(id)?.frames?.close() }
        catch (e: Exception) { android.util.Log.w("MuaMcp", "Could not close capture for display $id", e) }
        finally { onDisplayCountChanged(captures.size) }
    }

    private fun clearCaptures() {
        try { captures.keys.toList().forEach(::removeCapture) }
        finally { onDisplayCountChanged(captures.size) }
    }

    override fun close() {
        try {
            captures.keys.toList().forEach { id ->
                try { remote?.releaseDisplay(id) }
                catch (e: Exception) { android.util.Log.w("MuaMcp", "Could not release display $id", e) }
                finally { removeCapture(id) }
            }
        } finally {
            try { clearCaptures() }
            finally { remote = null; thread.quitSafely() }
        }
    }
}
