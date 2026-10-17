package moe.reimu.mua

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*

internal fun textContent(text: String) = CallToolResult(content = listOf(TextContent(text)))

internal data class ToolSpec(val name: String, val description: String, val properties: JsonObject, val annotations: ToolAnnotations? = null) {
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
    // Shared with the central server; packaged as a Java resource by Gradle.
    val specs: List<ToolSpec> = checkNotNull(ToolCatalog::class.java.getResourceAsStream("/device_tools.json")) {
        "Missing device tool catalog"
    }.bufferedReader().use { reader ->
        Json.parseToJsonElement(reader.readText()).jsonArray.map { entry ->
            val tool = entry.jsonObject
            ToolSpec(tool.getValue("name").jsonPrimitive.content,
                tool.getValue("description").jsonPrimitive.content,
                tool.getValue("inputSchema").jsonObject.getValue("properties").jsonObject,
                tool["annotations"]?.let { Json.decodeFromJsonElement<ToolAnnotations>(it) })
        }
    }
}
