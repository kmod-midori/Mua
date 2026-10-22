package moe.reimu.mua

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

internal fun Context.pushPreferences() = getSharedPreferences("fcm", Context.MODE_PRIVATE)

class MuaMessagingService : FirebaseMessagingService() {
    @Suppress("OVERRIDE_DEPRECATION") // Older registration events still use this callback.
    override fun onNewToken(token: String) = saveToken(token)

    // onRegistered delivers a Firebase Installation ID, not a legacy FCM token.
    // Our HTTP v1 sender uses message.token, so only onNewToken/getToken supply it.

    private fun saveToken(token: String) {
        pushPreferences().edit { putString("token", token) }
        PushRegistration.schedule(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!pushPreferences().getBoolean("enabled", false) ||
            message.data["action"] != "wake" ||
            message.priority != RemoteMessage.PRIORITY_HIGH) return
        // The push only wakes the service; normal transport authentication still applies.
        if (!loadListenerConfig().enabled && !loadRemoteConfig().enabled) return
        try {
            startForegroundService(Intent(this, McpService::class.java).setAction(McpService.ACTION_FCM_WAKE))
        } catch (e: IllegalStateException) {
            // Android can withdraw the background-start exemption or downgrade FCM.
            Log.w("MuaFcm", "Background service start was denied", e)
        } catch (e: SecurityException) {
            Log.w("MuaFcm", "Service start permission was denied", e)
        }
    }
}
