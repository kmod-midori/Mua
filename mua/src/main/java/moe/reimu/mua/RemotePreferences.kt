package moe.reimu.mua

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import androidx.core.content.edit

@SuppressLint("HardwareIds") // Default server device identity; editable by the user, not used for tracking.
internal fun Context.loadRemoteConfig(): RemoteConfig {
    val prefs = getSharedPreferences("remote", Context.MODE_PRIVATE)
    val id = prefs.getString("deviceId", null) ?: Settings.Secure.getString(
        contentResolver, Settings.Secure.ANDROID_ID,
    ).orEmpty().also {
        prefs.edit { putString("deviceId", it) }
    }
    val storedUrl = prefs.getString("url", "").orEmpty()
    val serverUrl = when {
        storedUrl.startsWith("wss:") -> "https:" + storedUrl.removePrefix("wss:")
        storedUrl.startsWith("ws:") -> "http:" + storedUrl.removePrefix("ws:")
        else -> storedUrl
    }
    if (serverUrl != storedUrl) prefs.edit { putString("url", serverUrl) }
    return RemoteConfig(prefs.getBoolean("enabled", false), serverUrl,
        id, prefs.getString("token", "")!!, prefs.getString("name", android.os.Build.MODEL)!!)
}

internal fun Context.saveRemoteConfig(config: RemoteConfig) {
    val previous = loadRemoteConfig()
    getSharedPreferences("remote", Context.MODE_PRIVATE).edit {
        putBoolean("enabled", config.enabled)
        putString("url", config.serverUrl)
        putString("deviceId", config.deviceId)
        putString("token", config.token)
        putString("name", config.deviceName)
    }
    if (previous.serverUrl != config.serverUrl || previous.deviceId != config.deviceId) {
        PushRegistration.schedule(this, previous)
    }
    PushRegistration.schedule(this, config)
}
