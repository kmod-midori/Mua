package moe.reimu.mua

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*

internal fun textContent(text: String) = CallToolResult(content = listOf(TextContent(text)))

internal data class ToolSpec(val name: String, val description: String, val properties: JsonObject) {
    fun schema() = ToolSchema(properties = properties, required = properties.keys.toList())

    fun validate(args: JsonObject) {
        require(args.keys.containsAll(properties.keys)) { "Expected arguments: ${properties.keys.joinToString()}" }
        if (name == "display_start") require(args.keys.none { it in setOf("width", "height", "dpi") }) {
            "Display dimensions and DPI are taken from the main display"
        }
        properties.forEach { (key, schema) ->
            val rule = schema.jsonObject
            val value = args[key] as? JsonPrimitive ?: error("$key must be a primitive")
            when (rule.getValue("type").jsonPrimitive.content) {
                "string" -> require(value.isString && value.content.isNotBlank()) { "$key must be a nonempty string" }
                else -> {
                    val number = value.doubleOrNull
                    require(!value.isString && number != null && number.isFinite()) { "$key must be a number" }
                    if (rule["type"]?.jsonPrimitive?.content == "integer") {
                        require(if (key == "durationMs") value.longOrNull != null else value.intOrNull != null) { "$key must be an integer" }
                    } else require(value.floatOrNull?.isFinite() == true) { "$key must be a finite coordinate" }
                    rule["minimum"]?.let { require(number >= it.jsonPrimitive.double) { "$key is outside the allowed range" } }
                    rule["maximum"]?.let { require(number <= it.jsonPrimitive.double) { "$key is outside the allowed range" } }
                }
            }
        }
    }
}

internal object ToolCatalog {
    private fun number(min: Long? = null, max: Long? = null, integer: Boolean = false) = buildJsonObject {
        put("type", if (integer) "integer" else "number"); min?.let { put("minimum", it) }; max?.let { put("maximum", it) }
    }
    private fun spec(name: String, description: String, vararg properties: Pair<String, JsonObject>) =
        ToolSpec(name, description, JsonObject(properties.toMap()))
    private val displayId = "displayId" to number(0, Int.MAX_VALUE.toLong(), true)
    val specs = listOf(
        spec("display_start", "Create an additional background display using the main display's pixel dimensions and DPI; return displayId and dimensions."),
        spec("displays_list", "List managed display IDs, dimensions, and focused packages from Android."),
        spec("recent_tasks_list", "List the current user's Android recent tasks with taskId, title (exact task label, falling back to app label), displayId, isRunning, package and base/top activities. Historical display IDs may be inactive."),
        spec("recent_task_move", "Request moving or restoring a recent task to a virtual display and bring it to the front. Blocks only if another task of the app is in front on another display.", displayId, "taskId" to number(0, Int.MAX_VALUE.toLong(), true)),
        spec("display_stop", "Stop the specified display and release its apps and capture buffers.", displayId),
        spec("screenshot", "Return the specified display frame as a PNG image, scaled proportionally to at most 1980 pixels on its longest side (never upscaled). Input coordinates still use native display dimensions from displays_list. Retry if the first frame is not ready.", displayId),
        spec("apps_list", "List launchable packages on this device."),
        spec("app_launch", "Launch a package on the specified display. Blocks only if the app is in front on another display.", displayId, "package" to buildJsonObject { put("type", "string") }),
        spec("focused_app", "Get the focused package on the specified display.", displayId),
        spec("tap", "Tap native display coordinates.", displayId, "x" to number(), "y" to number()),
        spec("swipe", "Swipe between native display coordinates.", displayId,
            "x1" to number(), "y1" to number(), "x2" to number(), "y2" to number(),
            "durationMs" to number(0, integer = true)),
        spec("key_press", "Inject an Android key code (for example BACK=4, HOME=3, ENTER=66).", displayId, "keyCode" to number(0, Int.MAX_VALUE.toLong(), true)),
    )
}
