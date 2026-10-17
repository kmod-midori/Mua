package moe.reimu.mua

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import kotlinx.serialization.json.*
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import moe.reimu.displaycontrol.DisplayCapture
import moe.reimu.nekoassistant.IUserService
import java.io.ByteArrayOutputStream

/** All methods run on McpService's single control executor, outside the UI and Binder threads. */
internal class DisplayTools(
    private val context: Context,
    private val onDisplayActive: (Boolean) -> Unit,
) : AutoCloseable {
    private val thread = HandlerThread("mua-frames").apply { start() }
    private val handler = Handler(thread.looper)
    private var remote: IUserService? = null
    private var capture: DisplayCapture? = null
    private var width = 0
    private var height = 0

    fun connected(service: IUserService?) {
        clearCapture()
        remote = service
    }

    fun execute(name: String, args: JsonObject): CallToolResult {
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
        val service = checkNotNull(remote) { "Shizuku is not connected. Grant permission in mua and start Shizuku." }
        fun int(key: String) = args.getValue(key).jsonPrimitive.int
        fun coordinate(key: String, limit: Int): Float {
            val value = args.getValue(key).jsonPrimitive.float
            require(value >= 0 && value < limit) { "$key must be within the current display (0..<${limit})" }
            return value
        }
        when (name) {
            "display_start" -> {
                val w = int("width"); val h = int("height")
                require(w.toLong() * h <= 8_388_608) { "Display must be at most 8 megapixels" }
                stopDisplay(service)
                val next = DisplayCapture(w, h, handler)
                try {
                    check(service.startDisplay(w, h, int("dpi"), next.surface)) { "Failed to create display" }
                    capture = next; width = w; height = h
                    onDisplayActive(true)
                } catch (e: Exception) {
                    try { service.stopDisplay() } catch (_: Exception) { }
                    try { next.close() } finally { clearCapture() }
                    throw e
                }
            }
            "display_stop" -> stopDisplay(service)
            else -> {
                checkNotNull(capture) { "Call display_start first" }
                when (name) {
                    "screenshot" -> {
                        val bitmap = checkNotNull(capture?.bitmap()) { "No frame yet; retry screenshot shortly" }
                        val bytes = try {
                            ByteArrayOutputStream().use { out ->
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                                out.toByteArray()
                            }
                        } finally { bitmap.recycle() }
                        return CallToolResult(content = listOf(ImageContent(
                            data = Base64.encodeToString(bytes, Base64.NO_WRAP), mimeType = "image/png",
                        )))
                    }
                    "app_launch" -> {
                        val pkg = args.getValue("package").jsonPrimitive.content
                        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                            ?: error("Package has no launchable activity: $pkg")
                        service.startActivity(intent, false)
                        return textContent("Launch requested for $pkg on the virtual display")
                    }
                    "focused_app" -> return textContent(service.focusedPackageName ?: "No focused package")
                    "tap" -> check(service.injectTapEvent(coordinate("x", width), coordinate("y", height))) { "Tap failed" }
                    "swipe" -> check(service.injectSwipeEvent(coordinate("x1", width), coordinate("y1", height),
                        coordinate("x2", width), coordinate("y2", height), int("durationMs").toLong())) { "Swipe failed" }
                    "key_press" -> check(service.injectKeyPressEvent(int("keyCode"))) { "Key injection failed" }
                    else -> error("Unknown tool")
                }
            }
        }
        return textContent("OK")
    }

    private fun stopDisplay(service: IUserService) {
        try { check(service.stopDisplay()) { "Failed to stop display" } }
        finally { clearCapture() }
    }

    private fun clearCapture() {
        try { capture?.close() }
        finally {
            capture = null
            width = 0; height = 0
            onDisplayActive(false)
        }
    }

    override fun close() {
        try { remote?.let { stopDisplay(it) } }
        finally {
            try { clearCapture() }
            finally { remote = null; thread.quitSafely() }
        }
    }
}
