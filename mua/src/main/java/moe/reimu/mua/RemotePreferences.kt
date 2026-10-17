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
    return RemoteConfig(prefs.getBoolean("enabled", false), prefs.getString("url", "")!!,
        id, prefs.getString("token", "")!!, prefs.getString("name", android.os.Build.MODEL)!!)
}

internal fun Context.saveRemoteConfig(config: RemoteConfig) {
    getSharedPreferences("remote", Context.MODE_PRIVATE).edit {
        putBoolean("enabled", config.enabled)
        putString("url", config.serverUrl)
        putString("deviceId", config.deviceId)
        putString("token", config.token)
        putString("name", config.deviceName)
    }
}
