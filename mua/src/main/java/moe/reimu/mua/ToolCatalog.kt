package moe.reimu.mua

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*

internal fun textContent(text: String) = CallToolResult(content = listOf(TextContent(text)))

internal data class ToolSpec(val name: String, val description: String, val properties: JsonObject) {
    fun schema() = ToolSchema(properties = properties, required = properties.keys.toList())

    fun validate(args: JsonObject) {
        require(args.keys == properties.keys) { "Expected arguments: ${properties.keys.joinToString()}" }
        properties.forEach { (key, schema) ->
            val rule = schema.jsonObject
            val value = args[key] as? JsonPrimitive ?: error("$key must be a primitive")
            when (rule.getValue("type").jsonPrimitive.content) {
                "string" -> require(value.isString && value.content.isNotBlank() && value.content.length <= 255) { "$key must be a nonempty string" }
                else -> {
                    val number = value.doubleOrNull
                    require(!value.isString && number != null && number.isFinite()) { "$key must be a number" }
                    if (rule["type"]?.jsonPrimitive?.content == "integer") require(value.intOrNull != null) { "$key must be an integer" }
                    require(number >= rule.getValue("minimum").jsonPrimitive.double &&
                        number <= rule.getValue("maximum").jsonPrimitive.double) { "$key is outside the allowed range" }
                }
            }
        }
    }
}

internal object ToolCatalog {
    private fun number(min: Int, max: Int, integer: Boolean = false) = buildJsonObject {
        put("type", if (integer) "integer" else "number"); put("minimum", min); put("maximum", max)
    }
    private fun spec(name: String, description: String, vararg properties: Pair<String, JsonObject>) =
        ToolSpec(name, description, JsonObject(properties.toMap()))
    val specs = listOf(
        spec("display_start", "Create a background display, replacing any existing display. Dimensions are native pixels.",
            "width" to number(64, 4096, true), "height" to number(64, 4096, true), "dpi" to number(72, 640, true)),
        spec("display_stop", "Stop the display and release its apps and capture buffers."),
        spec("screenshot", "Return the latest display frame as a PNG image. Retry if the first frame is not ready."),
        spec("apps_list", "List launchable packages on this device."),
        spec("app_launch", "Launch a package on the background display.", "package" to buildJsonObject { put("type", "string") }),
        spec("focused_app", "Get the focused package on the background display."),
        spec("tap", "Tap native display coordinates.", "x" to number(0, 4095), "y" to number(0, 4095)),
        spec("swipe", "Swipe between native display coordinates.",
            "x1" to number(0, 4095), "y1" to number(0, 4095), "x2" to number(0, 4095), "y2" to number(0, 4095),
            "durationMs" to number(1, 5000, true)),
        spec("key_press", "Inject an Android key code (for example BACK=4, HOME=3, ENTER=66).", "keyCode" to number(0, 1000, true)),
    )
}
