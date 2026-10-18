package moe.reimu.displaycontrol

/** Transport-independent tool output. Image bytes belong to the caller. */
sealed interface DeviceToolResult {
    data class Text(val text: String) : DeviceToolResult
    data class Image(val bytes: ByteArray, val mimeType: String) : DeviceToolResult
}
