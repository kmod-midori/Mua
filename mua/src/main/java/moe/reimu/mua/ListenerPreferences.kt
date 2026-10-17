package moe.reimu.mua

import android.content.Context
import androidx.core.content.edit

internal fun Context.loadListenerConfig(): ListenerConfig {
    val prefs = getSharedPreferences("listener", Context.MODE_PRIVATE)
    return runCatching {
        ListenerConfig(prefs.getString("address", "127.0.0.1")!!, prefs.getInt("port", 8765))
    }.getOrDefault(ListenerConfig())
}

internal fun Context.saveListenerConfig(config: ListenerConfig) {
    getSharedPreferences("listener", Context.MODE_PRIVATE).edit {
        putString("address", config.address)
        putInt("port", config.port)
    }
}
