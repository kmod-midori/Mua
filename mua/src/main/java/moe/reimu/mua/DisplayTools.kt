package moe.reimu.mua

import android.content.Context
import java.util.Base64
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import kotlinx.serialization.json.JsonObject
import moe.reimu.displaycontrol.DeviceToolExecutor
import moe.reimu.displaycontrol.DeviceToolResult
import moe.reimu.nekoassistant.IUserService

/** MCP content adapter; execution and capture ownership live in display-control. */
internal class DisplayTools(context: Context, onDisplayCountChanged: (Int) -> Unit) : AutoCloseable {
    private val executor = DeviceToolExecutor(context, onDisplayCountChanged)

    fun connected(service: IUserService?) = executor.connected(service)
    fun refreshDisplays() = executor.refreshDisplays()
    fun previews(): List<DisplayPreview> = executor.previews()

    fun execute(name: String, args: JsonObject): CallToolResult = executor.execute(name, args.toString()).toMcpResult()

    override fun close() = executor.close()
}

internal fun DeviceToolResult.toMcpResult(): CallToolResult = when (this) {
    is DeviceToolResult.Text -> textContent(text)
    is DeviceToolResult.Image -> CallToolResult(content = listOf(
        ImageContent(data = Base64.getEncoder().encodeToString(bytes), mimeType = mimeType),
    ))
}
