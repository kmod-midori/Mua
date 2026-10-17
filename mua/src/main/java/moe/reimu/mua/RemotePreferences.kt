package moe.reimu.mua

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

internal fun Context.loadRemoteConfig(): RemoteConfig {
    val prefs = getSharedPreferences("remote", Context.MODE_PRIVATE)
    val id = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
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
