package moe.reimu.mua

import java.net.URI

internal data class RemoteConfig(
    val enabled: Boolean = false,
    val serverUrl: String = "",
    val deviceId: String = "",
    val token: String = "",
    val deviceName: String = "Android device",
) {
    enum class ValidationError(val resource: Int, val message: String) {
        URL(R.string.invalid_url, "Enter a ws:// or wss:// server URL"),
        NAME(R.string.invalid_name, "Enter a device name"),
        ID(R.string.invalid_device_id, "Enter a device ID without slashes"),
        TOKEN(R.string.invalid_token, "Enter the device token"),
    }

    fun validationError(): ValidationError? {
        if (!enabled) return null
        val uri = runCatching { URI(serverUrl) }.getOrNull() ?: return ValidationError.URL
        if (uri.scheme !in listOf("ws", "wss") || uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            (uri.port != -1 && uri.port !in 1..65535)) return ValidationError.URL
        if (deviceName.isBlank()) return ValidationError.NAME
        if (deviceId.isBlank() || '/' in deviceId) return ValidationError.ID
        if (token.isBlank() || token.any { it == '\r' || it == '\n' }) return ValidationError.TOKEN
        return null
    }

    fun validate() {
        validationError()?.let { throw IllegalArgumentException(it.message) }
    }

    fun endpoint(): String {
        validate()
        val path = URI(null, null, "/devices/$deviceId/ws", null).rawPath
        return serverUrl.trimEnd('/') + path
    }
}
