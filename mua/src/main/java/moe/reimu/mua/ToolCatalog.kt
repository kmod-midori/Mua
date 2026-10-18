package moe.reimu.mua

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import moe.reimu.displaycontrol.DeviceToolCatalog
import moe.reimu.displaycontrol.DeviceToolSpec

internal fun textContent(text: String) = CallToolResult(content = listOf(TextContent(text)))

/** SDK adapter only; metadata, schemas and validation belong to the shared library. */
internal class ToolSpec(private val shared: DeviceToolSpec) {
    val name get() = shared.name
    val description get() = shared.description
    val properties get() = Json.encodeToJsonElement(shared.inputSchema.properties).jsonObject
    val annotations get() = shared.annotations?.let { ToolAnnotations(readOnlyHint = it.readOnlyHint) }
    fun schema() = ToolSchema(properties = properties, required = shared.inputSchema.required)
    fun validate(args: JsonObject) = shared.validate(args)
}

internal object ToolCatalog {
    val specs = DeviceToolCatalog.specs.map(::ToolSpec)
}
