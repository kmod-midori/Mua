package moe.reimu.mua

import java.net.URI

internal data class RemoteConfig(
    val enabled: Boolean = false,
    val serverUrl: String = "",
    val deviceId: String = "",
    val token: String = "",
    val deviceName: String = "Android device",
) {
    fun validate() {
        if (!enabled) return
        val uri = URI(serverUrl)
        require(uri.scheme in listOf("ws", "wss") && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65535)) { "Enter a ws:// or wss:// server URL" }
        require(deviceName.isNotBlank()) { "Enter a device name" }
        require(deviceId.isNotBlank() && '/' !in deviceId) { "Enter a device ID without slashes" }
        require(token.isNotBlank() && token.none { it == '\r' || it == '\n' }) { "Enter the device token" }
    }

    fun endpoint(): String {
        validate()
        val path = URI(null, null, "/devices/$deviceId/ws", null).rawPath
        return serverUrl.trimEnd('/') + path
    }
}
